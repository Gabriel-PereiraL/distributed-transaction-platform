package dev.dtp.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalMigrationsTest {
    private Map<String, String> local() {
        return new HashMap<>(Map.of("DTP_ENVIRONMENT", "local", "DTP_ALLOW_MIGRATE", "true", "DTP_DATABASE_URL", "jdbc:postgresql://postgres:5432/dtp",
                "DTP_DATABASE_USER", "dtp_owner", "DTP_DATABASE_PASSWORD", "synthetic-test-only"));
    }
    @Test void explicitLocalOwnerIsAcceptedWithoutConnecting() { assertThatCode(() -> LocalMigrations.validate(local())).doesNotThrowAnyException(); }
    @Test void implicitMigrationIsRejected() {
        var env = local(); env.remove("DTP_ALLOW_MIGRATE");
        assertThatThrownBy(() -> LocalMigrations.validate(env)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void externalDatabaseAndOtherDatabaseNamesAreRejected() {
        for (String url : java.util.List.of("jdbc:postgresql://example.com:5432/dtp", "jdbc:postgresql://postgres:5432/fulfillment", "jdbc:postgresql://postgres:5432/dtp?user=admin")) {
            var env = local(); env.put("DTP_DATABASE_URL", url);
            assertThatThrownBy(() -> LocalMigrations.validate(env)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void runtimeIdentityCannotMigrate() {
        var env = local(); env.put("DTP_DATABASE_USER", "dtp_api_login");
        assertThatThrownBy(() -> LocalMigrations.validate(env)).isInstanceOf(IllegalArgumentException.class);
    }
}
