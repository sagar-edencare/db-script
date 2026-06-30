package com.eden.dbmigration.repository;

import com.eden.dbmigration.tenant.Tenant;
import com.eden.dbmigration.tenant.Tenant.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Reads tenants from the MASTER database. Spring Data generates the
 * implementation at runtime — no SQL to maintain here.
 */
public interface TenantRepository extends JpaRepository<Tenant, String> {

    /** Only ACTIVE tenants are migrated; suspended/decommissioned are skipped. */
    List<Tenant> findByStatusOrderByTenantId(TenantStatus status);
}
