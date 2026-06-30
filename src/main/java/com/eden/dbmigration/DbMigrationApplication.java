package com.eden.dbmigration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Entry point of the standalone migration service.
 *
 * This is NOT a web server. It boots Spring, runs every active tenant's
 * migrations once (via {@code MigrationCommandLineRunner}), then exits with
 * code 0 (all good) or 1 (at least one tenant failed). That exit code is what
 * the CI/CD pipeline uses to mark the job green or red.
 */
// The single auto-configured DataSource is the MASTER db (tenant_registry).
// Per-tenant DataSources are built by hand in DataSourceFactory.
@SpringBootApplication
@ConfigurationPropertiesScan   // picks up @ConfigurationProperties beans
@EnableRetry                   // enables @Retryable on the Liquibase runner
public class DbMigrationApplication {

    public static void main(String[] args) {
        // exitCode propagates the CommandLineRunner result to the OS / pipeline.
        int exitCode = SpringApplication.exit(
                SpringApplication.run(DbMigrationApplication.class, args));
        System.exit(exitCode);
    }
}
