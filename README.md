# db-migration-service

A **standalone, multi-tenant database migration service**. It is a dedicated
repository whose *only* job is to apply database schema changes (via Liquibase)
to **every tenant's PostgreSQL database** — completely decoupled from your
Spring Boot application's deployment.

> One repo. You add one changelog file. It updates all tenant databases. The app
> just uses the new schema.

---

## Table of contents
1. [Overall architecture](#1-overall-architecture)
2. [Why each component exists](#2-why-each-component-exists)
3. [Project structure](#3-project-structure)
4. [Spring Boot design (every class)](#4-spring-boot-design)
5. [Liquibase structure](#5-liquibase-structure)
6. [Complete flow + sequence diagram](#6-complete-flow)
7. [Code walkthrough](#7-code-walkthrough)
8. [GitHub Actions pipeline](#8-github-actions-pipeline)
9. [Docker & Kubernetes](#9-docker--kubernetes)
10. [Best practices](#10-best-practices)
11. [Security](#11-security)
12. [End-to-end enterprise example](#12-end-to-end-example)

---

## 1. Overall architecture

```
   ┌────────────┐
   │ Developer  │  adds ONE changelog file (e.g. 002-add-patient-phone.xml)
   └─────┬──────┘
         │ git push
         ▼
   ┌──────────────────────┐
   │ GitHub repo          │   db-migration-service  (this repo)
   │  db-script           │
   └─────┬────────────────┘
         │ triggers
         ▼
   ┌──────────────────────┐
   │ GitHub Actions       │   build → test → docker build → push image
   └─────┬────────────────┘
         │ runs container
         ▼
   ┌──────────────────────┐
   │ Migration Service    │   (Spring Boot batch job, runs once, then exits)
   │  (Docker container)  │
   └─────┬────────────────┘
         │ 1. connect & read tenants
         ▼
   ┌──────────────────────┐
   │ MASTER Database      │   tenant_registry: who/where each tenant DB is
   └─────┬────────────────┘
         │ 2. for each ACTIVE tenant
         ▼
   ┌─────────────────────────────────────────────────────────┐
   │ Tenant Databases                                         │
   │   ┌────────────┐  ┌────────────┐  ┌────────────┐         │
   │   │ tenant_a_db│  │ tenant_b_db│  │ tenant_c_db│  ...     │
   │   └─────┬──────┘  └─────┬──────┘  └─────┬──────┘         │
   └─────────┼───────────────┼───────────────┼────────────────┘
             ▼               ▼               ▼
        ┌──────────────────────────────────────────┐
        │ Liquibase (runs inside the service)       │
        │   applies only NOT-yet-applied changesets │
        └─────────────────────┬─────────────────────┘
                              ▼
        ┌──────────────────────────────────────────┐
        │ DATABASECHANGELOG (one per tenant DB)     │
        │   records which changesets already ran    │
        └──────────────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ Migration Report │  SUCCESS/FAILED per tenant + exit code
                    └──────────────────┘
```

---

## 2. Why each component exists

### Why a separate repository?
Embedding Liquibase in the app means **database changes can only ship when the
app ships**, and the app *cannot start* if a migration fails. Separating them
gives you:
- **Independent lifecycle** — migrate the DB before or after deploying the app.
- **Blast-radius control** — a bad changeset fails the *migration job*, not
  every app pod's startup.
- **Clear ownership & review** — DBAs/seniors review schema PRs in one place.
- **Reusability** — the same service migrates dev, staging, and all prod tenants.

### Why separate Liquibase from the app?
With Liquibase on app startup, 50 app pods starting at once all race for the
`DATABASECHANGELOGLOCK`. With a dedicated service, migrations run **once, in a
controlled job**, before the app rolls out. App startup becomes fast and
predictable.

### Why a Master Database?
The service must know **which tenants exist and how to reach them** *without a
code change every time you onboard a customer*. The master DB's
`tenant_registry` table is that dynamic list. Add a row → next run migrates the
new tenant. No redeploy.

### How tenant discovery works
On startup the service connects to the master DB and runs
`SELECT … WHERE status='ACTIVE'`. That list is what the loop iterates.

### How tenant connections are created
For each tenant row, `DataSourceFactory` builds a small HikariCP pool from
`db_url` + `db_username` + the password resolved from a secret store. The pool
is **closed immediately after** that tenant is done — we never hold hundreds
open.

### How Liquibase tracks executed changes
Each tenant DB gets two Liquibase-managed tables:
- **`DATABASECHANGELOG`** — one row per *applied* changeset (id, author,
  filename, checksum, timestamp). Liquibase compares your changelog against
  this table and runs only what's missing.
- **`DATABASECHANGELOGLOCK`** — a single-row mutex so two runners can't migrate
  the same DB simultaneously.

### How rollbacks work
Every changeset declares a `<rollback>`. You can roll back by **count**
(`rollbackCount 1`), by **tag** (`rollback v1.2`), or by **date**. Liquibase
executes the rollback SQL and removes the row from `DATABASECHANGELOG`.

### Why this is better than embedding Liquibase in the app
| Embedded in app | Dedicated service |
|---|---|
| DB change requires app deploy | DB and app deploy independently |
| Migration failure blocks app startup | Failure isolated to migration job |
| N pods race for the lock | One controlled run |
| Schema scattered across app repos | Single source of truth |
| Hard to migrate many tenants | Loop is built for it |

---

## 3. Project structure

```
db-migration-service/
├── pom.xml                         Maven build (Spring Boot + Liquibase + Postgres)
├── Dockerfile                      Multi-stage build → tiny runtime image
├── README.md
├── .gitignore
│
├── scripts/
│   └── master-db-setup.sql         One-time: creates tenant_registry + seed rows
│
├── .github/workflows/
│   └── migrate.yml                 CI/CD: build → test → image → run migration
│
└── src/main/
    ├── java/com/eden/dbmigration/
    │   ├── DbMigrationApplication.java     main(); boots Spring, sets exit code
    │   │
    │   ├── config/                         configuration & typed properties
    │   │   └── MigrationProperties.java    binds migration.* from application.yml
    │   │
    │   ├── tenant/                          the domain model
    │   │   └── Tenant.java                  JPA entity for tenant_registry
    │   │
    │   ├── repository/                      data access (master DB only)
    │   │   └── TenantRepository.java        Spring Data: findByStatus(ACTIVE)
    │   │
    │   ├── service/                         business logic / orchestration
    │   │   ├── TenantService.java           "which tenants do we migrate?"
    │   │   ├── SecretResolver.java          password-ref → real secret
    │   │   └── MigrationService.java        the loop: migrate each, isolate failures
    │   │
    │   ├── liquibase/                        the migration engine wiring
    │   │   ├── DataSourceFactory.java        build a per-tenant connection pool
    │   │   └── LiquibaseRunner.java          run Liquibase against one DataSource
    │   │
    │   ├── runner/                           the entry behaviour
    │   │   └── MigrationCommandLineRunner.java  runs once on boot, prints report
    │   │
    │   └── util/                             helpers
    │       ├── TenantMigrationResult.java    per-tenant outcome (record)
    │       └── MigrationReport.java          aggregates + renders the report
    │
    └── resources/
        ├── application.yml                  config (env-var driven)
        └── db/changelog/
            ├── master.xml                   ROOT changelog: only <include>s
            └── changes/
                ├── 001-initial-schema.xml   one file per feature/change
                └── 002-add-patient-phone.xml
```

**Why these folders?** Each maps to one responsibility so the codebase reads
like its own architecture diagram: `tenant` = what, `repository` = read it,
`service` = decide & orchestrate, `liquibase` = do the migration, `runner` =
when it happens, `util` = reporting, `config` = settings.

---

## 4. Spring Boot design

| Class | Responsibility | Talks to |
|---|---|---|
| `DbMigrationApplication` | Boots Spring (no web), propagates exit code to OS/CI | Spring |
| `MigrationProperties` | Immutable typed config (`migration.*`) | application.yml |
| `Tenant` | JPA entity = one `tenant_registry` row | master DB |
| `TenantRepository` | `findByStatusOrderByTenantId(ACTIVE)` | master DB |
| `TenantService` | Encapsulates "active tenant" rule | TenantRepository |
| `SecretResolver` | Turns `db_password_ref` into the real password | Key Vault / env |
| `DataSourceFactory` | Builds & returns a per-tenant Hikari pool | SecretResolver |
| `LiquibaseRunner` | Runs Liquibase on one DataSource, with `@Retryable` | Liquibase, tenant DB |
| `MigrationService` | The loop; isolates failures; builds report | all of the above |
| `MigrationCommandLineRunner` | Runs once on boot; prints report; sets exit code | MigrationService |
| `MigrationReport` / `TenantMigrationResult` | Collect & render outcomes | — |

**Interaction order:** `CommandLineRunner` → `MigrationService.migrateAllTenants()`
→ `TenantService.getActiveTenants()` → for each tenant: `DataSourceFactory`
(→ `SecretResolver`) → `LiquibaseRunner.runMigrations()` → record result →
`MigrationReport.render()` → exit code.

**Exception handling philosophy:** failures are caught *per tenant* inside the
loop. One tenant blowing up is recorded as `FAILED` and the loop continues
(configurable via `continue-on-error`). Transient errors are retried by
`@Retryable` before they ever reach the loop's catch.

**Logging:** SLF4J + MDC. We push `tenantId` into the MDC so every log line is
prefixed `[tenant_a]`, making multi-tenant logs readable.

---

## 5. Liquibase structure

```
db/changelog/
├── master.xml            <- ROOT. Contains ONLY <include> lines, never changesets.
└── changes/
    ├── 001-initial-schema.xml
    └── 002-add-patient-phone.xml   <- one file per change; add an <include> in master.xml
```

- **changeSet** — the atomic unit. Identity = `id` + `author` + filename.
  Once it's applied anywhere, **never edit it** (the checksum is stored). Add a
  new changeset instead.
- **context** — environment tag. `context="prod"` + running with
  `LIQUIBASE_CONTEXTS=prod` means "only run prod changesets". Great for
  seed/test-only data.
- **labels** — orthogonal tags for ad-hoc selection (e.g. `labels="hotfix"`),
  filtered with a label expression at runtime.
- **rollback** — the reverse operation, declared inside each changeset.
- **DATABASECHANGELOG** — Liquibase's ledger of what already ran (per tenant DB).
- **DATABASECHANGELOGLOCK** — the mutex preventing concurrent runs.

Example changeset (see `changes/002-add-patient-phone.xml`):

```xml
<changeSet id="002-add-patient-phone" author="sagar" context="prod">
    <preConditions onFail="MARK_RAN">
        <not><columnExists tableName="patient" columnName="phone"/></not>
    </preConditions>
    <addColumn tableName="patient">
        <column name="phone" type="VARCHAR(20)"/>
    </addColumn>
    <rollback>
        <dropColumn tableName="patient" columnName="phone"/>
    </rollback>
</changeSet>
```

---

## 6. Complete flow

```
Developer adds 002-add-patient-phone.xml + <include> in master.xml
        │ git push / open PR
        ▼
GitHub Actions: build-test job  (mvn clean verify)
        │ merge to main
        ▼
docker job: build image, push to ghcr.io
        │
        ▼
migrate job (gated by 'production' environment approval)
        │ docker run … (secrets injected as env vars)
        ▼
Service boots → reads tenant_registry (3 ACTIVE tenants)
        ▼
┌── loop ───────────────────────────────────────────────┐
│  tenant_a: open pool → Liquibase update → 002 applied  │
│            → DATABASECHANGELOG += row → close pool      │
│  tenant_b: same                                         │
│  tenant_c: same                                         │
└─────────────────────────────────────────────────────────┘
        ▼
Print MIGRATION REPORT, exit 0 (all ok) or 1 (any failed)
        ▼
notify job reports result
```

### Sequence diagram

```
Dev   GitHub   Actions   Service   MasterDB   TenantDB(n)   Liquibase
 │       │        │          │         │           │            │
 │ push  │        │          │         │           │            │
 │──────▶│ trigger│          │         │           │            │
 │       │───────▶│ build+img│         │           │            │
 │       │        │─────────▶│ start   │           │            │
 │       │        │          │ getActiveTenants()  │            │
 │       │        │          │────────▶│           │            │
 │       │        │          │◀────────│ [a,b,c]   │            │
 │       │        │          │   loop over tenants │            │
 │       │        │          │ createDataSource(a) │            │
 │       │        │          │────────────────────▶│            │
 │       │        │          │ runMigrations(a) ───────────────▶│
 │       │        │          │          │          │  update()  │
 │       │        │          │          │          │◀───────────│ writes DATABASECHANGELOG
 │       │        │          │◀─────────────────── close pool   │
 │       │        │          │  …repeat b, c…      │            │
 │       │        │◀─────────│ report + exit code  │            │
 │       │◀───────│ notify   │         │           │            │
```

---

## 7. Code walkthrough

The fully-commented source lives under `src/main/java`. The key ideas:

- **`MigrationService.migrateAllTenants()`** — the loop. Wraps each tenant in
  try/catch so a failure is recorded and the loop continues. Uses `MDC` for
  per-tenant logging and `finally` to always close the pool.
- **`LiquibaseRunner.runMigrations()`** — `@Retryable` for transient errors;
  `try-with-resources` on both the JDBC `Connection` and the `Liquibase` object
  so the lock is always released. Supports a **dry-run** mode that prints SQL
  instead of executing.
- **`DataSourceFactory.createFor()`** — small pool (max 2), fail-fast timeouts,
  `sslmode=require`, password resolved at the last moment.
- **`SecretResolver`** — the single seam where secrets enter; swap its body for
  the Azure Key Vault / AWS Secrets Manager SDK without touching anything else.

---

## 8. GitHub Actions pipeline

See `.github/workflows/migrate.yml`. Four jobs:

1. **build-test** — `mvn clean verify` on every PR and push. Gate for merge.
2. **docker** — on `main`, build the image and push to GitHub Container Registry,
   tagged with both the commit SHA (immutable) and `latest`.
3. **migrate** — on `main`, `docker run` the image with secrets injected as env
   vars. Protected by the `production` GitHub *environment*, which can require a
   manual reviewer approval before migrations touch real tenant data.
4. **notify** — always runs; reports the result (wire to Slack/Teams).

`workflow_dispatch` exposes a **dry-run** toggle so you can preview SQL safely.

---

## 9. Docker & Kubernetes

### Dockerfile
Multi-stage: a Maven stage builds the jar, then a slim `temurin-17-jre-alpine`
runtime stage runs it as a **non-root** user. No `EXPOSE` — it isn't a server;
it runs once and exits.

### Environment variables (all config is external)
| Var | Purpose |
|---|---|
| `MASTER_DB_URL` / `MASTER_DB_USERNAME` / `MASTER_DB_PASSWORD` | master DB connection |
| `TENANT_<X>_DB_PASSWORD` | resolved by `SecretResolver` from each `db_password_ref` |
| `LIQUIBASE_CONTEXTS` | e.g. `prod` |
| `DRY_RUN` | `true` to preview SQL |
| `CONTINUE_ON_ERROR` | keep going after a tenant fails (default `true`) |

### Run locally
```bash
mvn clean package
docker build -t db-migration-service .
docker run --rm \
  -e MASTER_DB_URL=jdbc:postgresql://host.docker.internal:5432/master_db \
  -e MASTER_DB_USERNAME=master_user \
  -e MASTER_DB_PASSWORD=secret \
  -e TENANT_A_DB_PASSWORD=secret \
  -e LIQUIBASE_CONTEXTS=prod \
  db-migration-service
```

### Kubernetes
Run as a **Job** (not a Deployment) — it must run to completion, not stay up.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: db-migration
spec:
  backoffLimit: 0                 # we handle retries inside the app
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: migration
          image: ghcr.io/your-org/db-migration-service:<sha>
          env:
            - name: MASTER_DB_URL
              value: jdbc:postgresql://master-pg:5432/master_db
          envFrom:
            - secretRef:
                name: db-migration-secrets   # master + tenant passwords
```

For app deployments, gate the app rollout on this Job succeeding (e.g. an
ArgoCD sync wave or a Helm pre-install/pre-upgrade hook).

---

## 10. Best practices

- **Versioning / naming:** `NNN-short-description.xml`, sequential. One feature
  per file. Reference the ticket in `<comment>`.
- **One changeset per logical change** — small, reviewable, individually
  rollback-able.
- **Never edit an applied changeset** — checksums are recorded; create a new one.
- **Idempotency** — use `<preConditions onFail="MARK_RAN">` so reruns are safe.
- **Rollback** — declare `<rollback>` on every changeset; tag releases
  (`liquibase tag v1.4`) so you can `rollback v1.4`.
- **Transactions** — Liquibase wraps each changeset in a transaction (on
  Postgres, DDL is transactional, so a failed changeset rolls itself back). Keep
  one DDL statement per changeset to avoid partial states.
- **Performance / parallelism** — for many tenants, migrate in parallel batches
  (e.g. a bounded thread pool over the tenant list). Start sequential; add
  parallelism once individual runs are proven safe.
- **Monitoring / audit** — the report + exit code feed CI; persist results to a
  table or push metrics. `DATABASECHANGELOG` itself is your audit trail.
- **Failure recovery** — `continue-on-error` lets healthy tenants finish; re-run
  the job to retry only the failed ones (already-applied changesets are skipped).
- **Backups** — snapshot tenant DBs before destructive migrations; test the
  rollback path in staging first.

---

## 11. Security

- **Never store passwords in Git.** The registry stores a *reference*, not a
  secret. `application.yml` uses `${ENV_VAR}` placeholders only.
- **Secret stores:** in prod, `SecretResolver` should pull from **Azure Key
  Vault** or **AWS Secrets Manager** (the class is the only place to change).
- **GitHub Secrets** hold CI-time values, injected as env vars at `docker run`.
- **TLS to the database:** `sslmode=require` (use `verify-full` with a CA cert
  in production).
- **Least privilege:** the migration DB user needs DDL on its tenant schema but
  not superuser; the master user needs only `SELECT` on `tenant_registry`.
- **Non-root container**, immutable image tags (SHA-pinned), and a
  manual-approval gate on the `production` environment.

---

## 12. End-to-end example

**Goal:** add a `phone` column to the `patient` table across tenants A, B, C.

1. **Developer** creates `src/main/resources/db/changelog/changes/002-add-patient-phone.xml`
   (the changeset shown in §5) and adds `<include file=".../002-add-patient-phone.xml"/>`
   to `master.xml`. Commits, opens a PR.
2. **CI build-test** runs `mvn clean verify`. Reviewer approves & merges to `main`.
3. **docker** job builds `ghcr.io/.../db-migration-service:<sha>` and pushes it.
4. **migrate** job (after `production` approval) runs the container with secrets.
5. Service connects to the **master DB**, reads 3 ACTIVE tenants `[a, b, c]`.
6. **tenant_a_db:** Liquibase sees `001` already in `DATABASECHANGELOG`, sees
   `002` is missing → runs `ALTER TABLE patient ADD COLUMN phone VARCHAR(20)` →
   inserts a `002` row into `DATABASECHANGELOG`. Pool closed.
7. **tenant_b_db** and **tenant_c_db:** identical.
8. Report printed:

```
========================= MIGRATION REPORT =========================
TENANT_ID       NAME                   STATUS      TIME(ms)
--------------------------------------------------------------------
tenant_a        Acme Hospital          SUCCESS          142
tenant_b        Beta Clinic            SUCCESS          118
tenant_c        Gamma Care             SUCCESS          131
--------------------------------------------------------------------
TOTAL=3  SUCCESS=3  FAILED=0
====================================================================
```

9. Exit code `0` → pipeline green → app (which already expects `phone`) is
   deployed/used. If tenant_b had failed, it would show `FAILED`, the others
   would still succeed, exit code `1`, and you'd re-run after fixing — A and C
   are skipped automatically because their `002` row already exists.

---

## Getting started

```bash
# 1. Create the master registry (once)
psql "$MASTER_DB_URL" -f scripts/master-db-setup.sql

# 2. Build & run
mvn clean package
java -jar target/db-migration-service.jar   # uses env vars / application.yml
```
```
