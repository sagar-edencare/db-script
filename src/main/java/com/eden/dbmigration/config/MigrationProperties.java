package com.eden.dbmigration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed, immutable view of the {@code migration.*} block in
 * application.yml. Bound at startup; validated by Spring.
 *
 * The bean name "migrationProperties" is referenced by SpEL in @Retryable.
 */
@ConfigurationProperties(prefix = "migration")
public record MigrationProperties(

        /** Classpath location of the root changelog. */
        @DefaultValue("db/changelog/master.xml") String changelogMaster,

        /** Comma-separated Liquibase contexts, e.g. "prod" or "prod,seed". */
        @DefaultValue("") String contexts,

        /** Comma-separated Liquibase labels. */
        @DefaultValue("") String labels,

        /** If true, print SQL instead of executing it. */
        @DefaultValue("false") boolean dryRun,

        /** Keep migrating remaining tenants after one fails. */
        @DefaultValue("true") boolean continueOnError,

        Retry retry) {

    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("2000") long initialDelayMs) { }
}
