package com.eden.dbmigration.service;

import com.eden.dbmigration.repository.TenantRepository;
import com.eden.dbmigration.runner.MigrationRequest;
import com.eden.dbmigration.tenant.Tenant;
import com.eden.dbmigration.tenant.Tenant.TenantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source of tenants. Reads the list DYNAMICALLY from the master database
 * (tenant_registry) and decides which tenants a given run should touch.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /** All ACTIVE tenants from the master registry. */
    public List<Tenant> getActiveTenants() {
        List<Tenant> tenants = tenantRepository.findByStatusOrderByTenantId(TenantStatus.ACTIVE);
        log.info("Loaded {} active tenant(s) from master registry", tenants.size());
        return tenants;
    }

    /**
     * Resolves the request into the concrete list of tenants to migrate.
     *  - no tenant named     -> NOTHING (we never migrate everyone by accident)
     *  - --tenants=all       -> every ACTIVE tenant
     *  - specific ids named  -> only those, in the order requested
     * Unknown / inactive ids are logged and skipped.
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
            log.info("Explicit --tenants=all -> targeting every active tenant");
            return active;
        }

        Map<String, Tenant> byId = new LinkedHashMap<>();
        for (Tenant t : active) {
            byId.put(t.getTenantId(), t);
        }

        List<Tenant> selected = new ArrayList<>();
        for (String id : request.tenantIds()) {
            Tenant tenant = byId.get(id);
            if (tenant == null) {
                log.warn("Requested tenant '{}' is not an ACTIVE tenant — skipping", id);
            } else {
                selected.add(tenant);
            }
        }
        log.info("Selected {} of {} active tenant(s) for this run",
                selected.size(), active.size());
        return selected;
    }
}
