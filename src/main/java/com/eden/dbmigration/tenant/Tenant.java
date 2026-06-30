package com.eden.dbmigration.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row in the MASTER database's {@code tenant_registry} table.
 *
 * This table holds everything needed to connect to a tenant and run Liquibase:
 * id, name, db url, username, password, and status. Tenants are read DYNAMICALLY
 * from here at runtime — add a row and the next run picks it up, no redeploy.
 */
@Entity
@Table(name = "tenant_registry")
public class Tenant {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;            // e.g. "tenant_a"

    @Column(name = "tenant_name", nullable = false)
    private String tenantName;          // e.g. "Acme Hospital"

    @Column(name = "db_url", nullable = false)
    private String dbUrl;               // jdbc:postgresql://host:5432/tenant_a_db

    @Column(name = "db_username", nullable = false)
    private String dbUsername;

    @Column(name = "db_password", nullable = false)
    private String dbPassword;          // stored in the table per requirement

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantStatus status;        // ACTIVE / SUSPENDED / DECOMMISSIONED

    protected Tenant() { }              // required by JPA

    public String getTenantId()     { return tenantId; }
    public String getTenantName()   { return tenantName; }
    public String getDbUrl()        { return dbUrl; }
    public String getDbUsername()   { return dbUsername; }
    public String getDbPassword()   { return dbPassword; }
    public TenantStatus getStatus() { return status; }

    public enum TenantStatus { ACTIVE, SUSPENDED, DECOMMISSIONED }
}
