package com.eden.dbmigration.runner;

import com.eden.dbmigration.runner.MigrationRequest.Command;
import com.eden.dbmigration.service.MigrationService;
import com.eden.dbmigration.util.MigrationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The thing Spring Boot calls automatically after the context is ready.
 *
 * It reads the command-line arguments, turns them into a {@link MigrationRequest}
 * (which tenants? which Liquibase command?), runs it, prints the report, and
 * records an exit code (0 = ok, 1 = a tenant failed) for the CI/Kubernetes job.
 *
 * We implement {@link ApplicationRunner} (not CommandLineRunner) because it
 * gives us {@link ApplicationArguments} with built-in parsing of "--key=value".
 */
@Component
public class MigrationCommandLineRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MigrationCommandLineRunner.class);

    private final MigrationService migrationService;
    private int exitCode = 0;

    public MigrationCommandLineRunner(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationRequest request = parse(args);

        // Refuse to run blind: require an explicit target.
        if (!request.hasSelection()) {
            System.out.println("""
                No target selected. This service never applies to all tenants by default.
                  Run for EVERY tenant : --tenants=all   (or --all)
                  Run for specific ones: --tenants=tenant_a,tenant_c
                  Preview only         : --command=status --tenants=tenant_a
                """);
            return;   // exit code stays 0; nothing was changed
        }

        log.info("=== db-migration-service starting | command={} | tenants={} ===",
                request.command(), request.allTenants() ? "ALL" : request.tenantIds());

        MigrationReport report = migrationService.migrate(request);

        // stdout so it lands in CI logs even if log level is raised.
        System.out.println(report.render());

        if (report.hasFailures()) {
            exitCode = 1;   // non-zero => pipeline / k8s Job marks this RED
            log.error("Finished with {} failure(s)", report.countFailed());
        } else {
            log.info("Finished successfully ({} tenant(s))", report.countSuccess());
        }
    }

    /** Translate raw CLI args into a typed request. */
    private MigrationRequest parse(ApplicationArguments args) {
        // --tenants=a,b  and/or  --tenant=a  (both supported, merged)
        List<String> tenantIds = new ArrayList<>();
        collectTenantOption(args, "tenants", tenantIds);
        collectTenantOption(args, "tenant", tenantIds);
        // --all is a convenience alias for --tenants=all
        if (args.containsOption("all")) {
            tenantIds.add("all");
        }

        // --command=update|status|validate|rollback  (default UPDATE)
        Command command = Command.UPDATE;
        if (args.containsOption("command")) {
            command = Command.valueOf(firstValue(args, "command").toUpperCase());
        }

        // --count=N  (only used by rollback; default 1)
        int rollbackCount = 1;
        if (args.containsOption("count")) {
            rollbackCount = Integer.parseInt(firstValue(args, "count"));
        }

        return new MigrationRequest(tenantIds, command, rollbackCount);
    }

    /** Splits comma-separated values, e.g. --tenants=a,b,c -> [a, b, c]. */
    private void collectTenantOption(ApplicationArguments args, String name, List<String> out) {
        if (!args.containsOption(name)) {
            return;
        }
        for (String value : args.getOptionValues(name)) {
            for (String id : value.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
    }

    private String firstValue(ApplicationArguments args, String name) {
        return args.getOptionValues(name).get(0);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
