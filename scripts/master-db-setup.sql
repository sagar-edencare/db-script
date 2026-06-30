-- Run ONCE against the MASTER database to create the dynamic tenant registry.
-- The migration service reads this table at startup to discover tenants.
--
--   psql "postgresql://postgres:postgres@HOST:5432/master_db" -f scripts/master-db-setup.sql

CREATE TABLE IF NOT EXISTS tenant_registry (
    tenant_id    VARCHAR(64)  PRIMARY KEY,           -- e.g. tenant_a
    tenant_name  VARCHAR(200) NOT NULL,              -- e.g. Acme Hospital
    db_url       VARCHAR(500) NOT NULL,              -- jdbc:postgresql://host:5432/tenant_a_db
    db_username  VARCHAR(100) NOT NULL,
    db_password  VARCHAR(200) NOT NULL,              -- password (consider a secret store in prod)
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE','SUSPENDED','DECOMMISSIONED')),
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Seed the three tenants. To onboard a new tenant later: just INSERT a row
-- (no code change, no redeploy — the next run will migrate it).
INSERT INTO tenant_registry (tenant_id, tenant_name, db_url, db_username, db_password, status)
VALUES
  ('tenant_a', 'Acme Hospital', 'jdbc:postgresql://localhost:5433/tenant_a_db', 'postgres', 'postgres', 'ACTIVE'),
  ('tenant_b', 'Beta Clinic',   'jdbc:postgresql://localhost:5433/tenant_b_db', 'postgres', 'postgres', 'ACTIVE'),
  ('tenant_c', 'Gamma Care',    'jdbc:postgresql://localhost:5433/tenant_c_db', 'postgres', 'postgres', 'ACTIVE')
ON CONFLICT (tenant_id) DO NOTHING;
