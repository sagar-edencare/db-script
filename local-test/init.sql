-- Runs automatically the first time the Postgres container starts.
-- Creates one empty database per tenant. Liquibase will build the schema.
CREATE DATABASE tenant_a_db;
CREATE DATABASE tenant_b_db;
CREATE DATABASE tenant_c_db;
