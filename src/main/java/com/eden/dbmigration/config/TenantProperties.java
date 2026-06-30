package com.eden.dbmigration.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Binds the {@code app.tenants} list from application.yml.
 *
 * Example:
 * <pre>
 * app:
 *   tenants:
 *     - id: tenant_a
 *       name: Acme Hospital
 *       url: jdbc:postgresql://localhost:5432/tenant_a_db
 *       username: tenant_a_user
 *       password: ${TENANT_A_DB_PASSWORD:secret}
 *       enabled: true
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record TenantProperties(List<TenantDef> tenants) {

    public record TenantDef(
            @NotBlank String id,
            String name,
            @NotBlank String url,
            @NotBlank String username,
            String password,
            @DefaultValue("true") boolean enabled) {
    }
}
