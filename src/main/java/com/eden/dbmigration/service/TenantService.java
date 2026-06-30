package com.eden.dbmigration.service;

import com.eden.dbmigration.config.TenantProperties;
import com.eden.dbmigration.runner.MigrationRequest;
import com.eden.dbmigration.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source of tenants. Reads the list straight from application.yml (TenantProperties)
 * and decides which tenants a given run should touch.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantProperties tenantProperties;

    public TenantService(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    /** All ENABLED tenants from config. */
    public List<Tenant> getActiveTenants() {
        List<Tenant> tenants = tenantProperties.tenants().stream()
                .filter(TenantProperties.TenantDef::enabled)
                .map(d -> new Tenant(d.id(), d.name(), d.url(), d.username(), d.password()))
                .toList();
        log.info("Loaded {} enabled tenant(s) from configuration", tenants.size());
        return tenants;
    }

    /**
     * Resolves the request into the concrete list of tenants to migrate.
     *  - no tenant named        -> NOTHING (we never migrate everyone by accident)
     *  - --tenants=all          -> every enabled tenant
     *  - specific ids named     -> only those, in the order requested
     * Unknown / disabled ids are logged and skipped.
     */
    public List<Tenant> resolveTenants(MigrationRequest request) {
        if (!request.hasSelection()) {
            log.warn("No tenant selected — nothing will run. "
                    + "Pass --tenants=all to target EVERY tenant, "
                    + "or --tenants=tenant_a[,tenant_b] to target specific ones.");
            return List.of();
        }

        List<Tenant> active = getActiveTenants();

        if (request.allTenants()) {
            log.info("Explicit --tenants=all -> targeting every enabled tenant");
            return active;
        }

        Map<String, Tenant> byId = new LinkedHashMap<>();
        for (Tenant t : active) {
            byId.put(t.id(), t);
        }

        List<Tenant> selected = new ArrayList<>();
        for (String id : request.tenantIds()) {
            Tenant tenant = byId.get(id);
            if (tenant == null) {
                log.warn("Requested tenant '{}' is not an enabled tenant — skipping", id);
            } else {
                selected.add(tenant);
            }
        }
        log.info("Selected {} of {} enabled tenant(s) for this run",
                selected.size(), active.size());
        return selected;
    }
}
