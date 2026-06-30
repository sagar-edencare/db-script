package com.eden.dbmigration.util;

/** Outcome of migrating one tenant. Immutable. */
public record TenantMigrationResult(
        String tenantId,
        String tenantName,
        Status status,
        long durationMs,
        String errorMessage) {   // null when status == SUCCESS

    public enum Status { SUCCESS, FAILED, SKIPPED }

    public static TenantMigrationResult success(String id, String name, long ms) {
        return new TenantMigrationResult(id, name, Status.SUCCESS, ms, null);
    }

    public static TenantMigrationResult failed(String id, String name, long ms, String error) {
        return new TenantMigrationResult(id, name, Status.FAILED, ms, error);
    }
}
