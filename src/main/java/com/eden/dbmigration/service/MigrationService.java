package com.eden.dbmigration.service;

import com.eden.dbmigration.config.MigrationProperties;
import com.eden.dbmigration.liquibase.DataSourceFactory;
import com.eden.dbmigration.liquibase.LiquibaseRunner;
import com.eden.dbmigration.runner.MigrationRequest;
import com.eden.dbmigration.tenant.Tenant;
import com.eden.dbmigration.util.MigrationReport;
import com.eden.dbmigration.util.TenantMigrationResult;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the whole run: resolve which tenants to touch, run the requested
 * Liquibase command on each, isolate failures, and produce a report.
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final TenantService tenantService;
    private final DataSourceFactory dataSourceFactory;
    private final LiquibaseRunner liquibaseRunner;
    private final MigrationProperties properties;

    public MigrationService(TenantService tenantService,
                            DataSourceFactory dataSourceFactory,
                            LiquibaseRunner liquibaseRunner,
                            MigrationProperties properties) {
        this.tenantService = tenantService;
        this.dataSourceFactory = dataSourceFactory;
        this.liquibaseRunner = liquibaseRunner;
        this.properties = properties;
    }

    /** Run the requested command against the selected tenants. */
    public MigrationReport migrate(MigrationRequest request) {
        List<Tenant> tenants = tenantService.resolveTenants(request);
        MigrationReport report = new MigrationReport();

        if (tenants.isEmpty()) {
            log.warn("No tenants matched the request — nothing to do");
            return report;
        }

        for (Tenant tenant : tenants) {
            // MDC puts the tenant id on every log line for this iteration.
            MDC.put("tenantId", tenant.getTenantId());
            long start = System.nanoTime();
            try {
                migrateOne(tenant, request);
                long ms = elapsedMs(start);
                report.add(TenantMigrationResult.success(
                        tenant.getTenantId(), tenant.getTenantName(), ms));
                log.info("SUCCESS in {} ms", ms);
            } catch (Exception ex) {
                long ms = elapsedMs(start);
                report.add(TenantMigrationResult.failed(
                        tenant.getTenantId(), tenant.getTenantName(), ms, ex.getMessage()));
                log.error("FAILED after {} ms: {}", ms, ex.getMessage(), ex);

                // One tenant's failure must not stop the others (unless disabled).
                if (!properties.continueOnError()) {
                    log.error("continueOnError=false -> aborting remaining tenants");
                    break;
                }
            } finally {
                MDC.remove("tenantId");
            }
        }
        return report;
    }

    /** Migrate a single tenant; the per-tenant pool is always closed. */
    private void migrateOne(Tenant tenant, MigrationRequest request) throws Exception {
        HikariDataSource dataSource = null;
        try {
            dataSource = dataSourceFactory.createFor(tenant);
            liquibaseRunner.execute(tenant.getTenantId(), dataSource, request);
        } finally {
            if (dataSource != null) {
                dataSource.close();   // release connections back to the tenant DB
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
