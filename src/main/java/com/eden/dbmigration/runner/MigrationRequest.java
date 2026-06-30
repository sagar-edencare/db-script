package com.eden.dbmigration.runner;

import java.util.List;

/**
 * The parsed command the operator asked for, built from CLI arguments.
 *
 * IMPORTANT: nothing runs unless you explicitly pick a target. Applying to
 * every tenant is an OPT-IN (--tenants=all), never the silent default.
 *
 * Examples:
 *   (no args)                          -> NOTHING runs; prints how to select
 *   --tenants=all                      -> command=UPDATE, EVERY active tenant
 *   --tenants=tenant_a                 -> command=UPDATE, only tenant_a
 *   --tenants=tenant_a,tenant_c        -> command=UPDATE, A and C
 *   --command=status --tenants=tenant_a-> show pending changes, apply nothing
 *   --command=rollback --count=1 --tenants=tenant_a -> roll back last changeset
 */
public record MigrationRequest(
        List<String> tenantIds,
        Command command,
        int rollbackCount) {

    public enum Command { UPDATE, STATUS, VALIDATE, ROLLBACK }

    /** True ONLY when the operator explicitly asked for every tenant. */
    public boolean allTenants() {
        return tenantIds != null && tenantIds.contains("all");
    }

    /** True when the operator named at least one target (specific or "all"). */
    public boolean hasSelection() {
        return tenantIds != null && !tenantIds.isEmpty();
    }
}
