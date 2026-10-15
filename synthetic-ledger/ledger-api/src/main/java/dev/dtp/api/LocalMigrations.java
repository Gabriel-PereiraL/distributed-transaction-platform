package dev.dtp.api;

import java.util.Map;
import org.flywaydb.core.Flyway;

/** Dedicated local job: no HTTP, application context, runtime identity or cloud target. */
public final class LocalMigrations {
    private LocalMigrations() { }
    public static void main(String[] args) {
        var environment = System.getenv();
        validate(environment);
        var result = Flyway.configure().dataSource(environment.get("DTP_DATABASE_URL"),
                environment.get("DTP_DATABASE_USER"), environment.get("DTP_DATABASE_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).validateMigrationNaming(true)
                .connectRetries(10).load().migrate();
        System.out.println("Local ledger migrations completed: " + result.migrationsExecuted);
    }
    static void validate(Map<String, String> environment) {
        if (!"local".equals(environment.get("DTP_ENVIRONMENT")) || !"true".equals(environment.get("DTP_ALLOW_MIGRATE"))) {
            throw new IllegalArgumentException("Explicit local migration gate required");
        }
        String url = environment.getOrDefault("DTP_DATABASE_URL", "");
        if (!url.matches("jdbc:postgresql://(?:postgres|localhost|127\\.0\\.0\\.1):[0-9]{1,5}/dtp")) {
            throw new IllegalArgumentException("Only the dedicated local dtp database is allowed");
        }
        if (!"dtp_owner".equals(environment.get("DTP_DATABASE_USER")) || environment.getOrDefault("DTP_DATABASE_PASSWORD", "").isBlank()) {
            throw new IllegalArgumentException("Dedicated local schema owner credentials required");
        }
    }
}
