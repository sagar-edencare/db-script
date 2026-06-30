package com.eden.dbmigration.util;

import java.util.ArrayList;
import java.util.List;

import static com.eden.dbmigration.util.TenantMigrationResult.Status.FAILED;
import static com.eden.dbmigration.util.TenantMigrationResult.Status.SUCCESS;

/** Accumulates per-tenant results and renders a final summary. */
public class MigrationReport {

    private final List<TenantMigrationResult> results = new ArrayList<>();

    public void add(TenantMigrationResult result) {
        results.add(result);
    }

    public long countSuccess() {
        return results.stream().filter(r -> r.status() == SUCCESS).count();
    }

    public long countFailed() {
        return results.stream().filter(r -> r.status() == FAILED).count();
    }

    public boolean hasFailures() {
        return countFailed() > 0;
    }

    /** Renders an aligned text table for logs / CI output. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========================= MIGRATION REPORT =========================\n");
        sb.append(String.format("%-15s %-22s %-9s %10s%n",
                "TENANT_ID", "NAME", "STATUS", "TIME(ms)"));
        sb.append("--------------------------------------------------------------------\n");
        for (TenantMigrationResult r : results) {
            sb.append(String.format("%-15s %-22s %-9s %10d%n",
                    r.tenantId(), truncate(r.tenantName(), 22), r.status(), r.durationMs()));
            if (r.errorMessage() != null) {
                sb.append("    -> ").append(r.errorMessage()).append('\n');
            }
        }
        sb.append("--------------------------------------------------------------------\n");
        sb.append(String.format("TOTAL=%d  SUCCESS=%d  FAILED=%d%n",
                results.size(), countSuccess(), countFailed()));
        sb.append("====================================================================\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
