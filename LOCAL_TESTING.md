# Local testing

Run the whole multi-tenant migration on your machine — no cloud, no master DB.
One local Postgres container hosts 3 tenant databases (`tenant_a_db`,
`tenant_b_db`, `tenant_c_db`). The tenant list lives in `application.yml`.

## Prerequisites
- Docker + Docker Compose
- Java 17, Maven

## 1. Start the local databases
```bash
docker compose -f local-test/docker-compose.yml up -d
```
This starts Postgres on host port **5433** and creates the 3 tenant DBs
(see `local-test/init.sql`). The defaults in `application.yml` already point here.

Wait until healthy:
```bash
docker inspect -f '{{.State.Health.Status}}' db-migration-local-pg   # -> healthy
```

## 2. Build the jar
```bash
mvn clean package -DskipTests
```

## 3. Run migrations

```bash
# Apply to EVERY tenant (explicit opt-in)
java -jar target/db-migration-service.jar --tenants=all

# Apply to ONE tenant
java -jar target/db-migration-service.jar --tenants=tenant_a

# Apply to a FEW
java -jar target/db-migration-service.jar --tenants=tenant_a,tenant_c

# Preview only (no changes) for one tenant
java -jar target/db-migration-service.jar --command=status --tenants=tenant_b

# Roll back the last changeset on one tenant
java -jar target/db-migration-service.jar --command=rollback --count=1 --tenants=tenant_a

# No target -> refuses and prints help (safety)
java -jar target/db-migration-service.jar
```

Expected report:
```
========================= MIGRATION REPORT =========================
TENANT_ID       NAME                   STATUS      TIME(ms)
--------------------------------------------------------------------
tenant_a        Acme Hospital          SUCCESS         ...
tenant_b        Beta Clinic            SUCCESS         ...
tenant_c        Gamma Care             SUCCESS         ...
--------------------------------------------------------------------
TOTAL=3  SUCCESS=3  FAILED=0
====================================================================
```
Exit code is `0` on success, `1` if any tenant failed.

## 4. Verify the schema landed
```bash
# patient table now has the phone column
docker exec db-migration-local-pg psql -U postgres -d tenant_a_db -c "\d patient"

# Liquibase's ledger of applied changesets
docker exec db-migration-local-pg psql -U postgres -d tenant_a_db \
  -c "SELECT id, author, filename FROM databasechangelog ORDER BY orderexecuted;"
```

## 5. Prove idempotency
Run step 3 again — the report still says SUCCESS, but Liquibase applies
**0** changesets (`Run: 0, Previously run: 2`) because each tenant's
`DATABASECHANGELOG` already records them. Safe to re-run anytime.

## Add a new change and test it
1. Create `src/main/resources/db/changelog/changes/003-something.xml`.
2. Add `<include file="db/changelog/changes/003-something.xml"/>` to `master.xml`.
3. `mvn clean package -DskipTests`
4. `java -jar target/db-migration-service.jar --tenants=all`
   → only the new changeset 003 runs on each tenant.

## Tear down
```bash
docker compose -f local-test/docker-compose.yml down -v   # -v wipes the data
```
