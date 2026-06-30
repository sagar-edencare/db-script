package com.eden.dbmigration.liquibase;

import com.eden.dbmigration.tenant.Tenant;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

/**
 * Builds a short-lived connection pool for ONE tenant on demand, using the
 * url/username/password read from the master registry row.
 *
 * We create and CLOSE a pool per tenant rather than holding many open at once.
 */
@Component
public class DataSourceFactory {

    public HikariDataSource createFor(Tenant tenant) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("migration-" + tenant.getTenantId());
        config.setJdbcUrl(tenant.getDbUrl());
        config.setUsername(tenant.getDbUsername());
        config.setPassword(tenant.getDbPassword());

        config.setMaximumPoolSize(2);          // migrations are single-threaded per tenant
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);   // fail fast if a tenant DB is unreachable
        config.setInitializationFailTimeout(10_000);

        return new HikariDataSource(config);
    }
}
