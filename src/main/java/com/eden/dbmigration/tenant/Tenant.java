package com.eden.dbmigration.tenant;

/**
 * A tenant and how to reach its database. Built from application.yml — there is
 * no master database. Plain immutable record; no JPA.
 */
public record Tenant(
        String id,
        String name,
        String url,
        String username,
        String password) {
}
