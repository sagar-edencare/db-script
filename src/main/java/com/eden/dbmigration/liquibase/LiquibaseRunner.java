package com.eden.dbmigration.liquibase;

import com.eden.dbmigration.config.MigrationProperties;
import com.eden.dbmigration.runner.MigrationRequest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.Connection;

/**
 * Runs a Liquibase command against a single, already-built DataSource.
 *
 * @Retryable: a transient failure (e.g. a network blip) re-invokes this method
 * up to maxAttempts with exponential backoff. A genuine SQL error (bad
 * changeset) fails every attempt and then propagates to MigrationService.
 */
@Component
public class LiquibaseRunner {

    private static final Logger log = LoggerFactory.getLogger(LiquibaseRunner.class);

    private final MigrationProperties properties;

    public LiquibaseRunner(MigrationProperties properties) {
        this.properties = properties;
    }

    @Retryable(
            retryFor = Exception.class,
            // ${...} placeholders are resolved from application.yml at runtime.
            maxAttemptsExpression = "${migration.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${migration.retry.initial-delay-ms:2000}",
                    multiplier = 2.0))
    public void execute(String tenantId, DataSource dataSource, MigrationRequest request)
            throws Exception {
        log.info("[{}] Acquiring connection for command {}", tenantId, request.command());

        // try-with-resources guarantees the connection AND Liquibase are closed,
        // which also releases the DATABASECHANGELOGLOCK row.
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            try (Liquibase liquibase = new Liquibase(
                    properties.changelogMaster(),
                    new ClassLoaderResourceAccessor(),
                    database)) {

                Contexts contexts = new Contexts(properties.contexts());
                LabelExpression labels = new LabelExpression(properties.labels());
                Writer out = new OutputStreamWriter(System.out);

                switch (request.command()) {
                    case UPDATE -> {
                        if (properties.dryRun()) {
                            // print the SQL it WOULD run, change nothing
                            liquibase.update(contexts, labels, out);
                        } else {
                            liquibase.update(contexts, labels);
                        }
                    }
                    case STATUS -> liquibase.reportStatus(true, contexts, out);
                    case VALIDATE -> liquibase.validate();
                    case ROLLBACK -> {
                        log.warn("[{}] Rolling back last {} changeset(s)",
                                tenantId, request.rollbackCount());
                        liquibase.rollback(request.rollbackCount(), contexts, labels);
                    }
                }
            }
        }
        log.info("[{}] Command {} completed", tenantId, request.command());
    }
}
