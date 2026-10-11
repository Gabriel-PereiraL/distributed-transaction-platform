package dev.dtp.api;

import dev.dtp.adapters.persistence.PostgresPosting;
import dev.dtp.adapters.persistence.SpringTransactions;
import dev.dtp.application.PostingRepository;
import dev.dtp.application.ProcessTransfer;
import dev.dtp.domain.PostingPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RuntimePrivilegesIT {
    @Container @SuppressWarnings("resource") static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String SOURCE = "20000000-0000-0000-0000-000000000001", DESTINATION = "20000000-0000-0000-0000-000000000002";
    @BeforeAll static void bootstrap() throws Exception {
        org.flywaydb.core.Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        try (var connection = PG.createConnection(""); var statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("../docs/local-seed.sql")));
            statement.execute(Files.readString(Path.of("../infra/postgres/runtime-grants.sql")));
            for (String role : java.util.List.of("api", "worker", "audit")) {
                statement.execute("CREATE USER test_" + role + " PASSWORD '" + PASSWORD + "'");
                statement.execute("GRANT dtp_" + role + " TO test_" + role);
            }
        }
    }
    @Test void restrictedApiAdmitsAndRestrictedWorkerPostsRealTransfer() throws Exception {
        try (var issuer = new TestIssuer(); var legacy = new LegacyFixture(); var context = new SpringApplicationBuilder(LedgerApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + PG.getJdbcUrl(), "--spring.datasource.username=test_api", "--spring.datasource.password=" + PASSWORD,
                "--spring.flyway.enabled=false", "--dtp.quota.enabled=false", "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer.url(),
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer.url() + "/jwks", "--dtp.legacy.enabled=true", "--dtp.legacy.origin=" + legacy.origin())) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            context.getBean(dev.dtp.application.AdmitTransfer.class).execute(new dev.dtp.application.Actor(TENANT, "alice", "test-client"), "restricted-admission",
                    new dev.dtp.application.TransferCommand(UUID.fromString(SOURCE), UUID.fromString(DESTINATION), new dev.dtp.domain.Money(100, "BRL")), "restricted-test", null);
            String bearer = issuer.token(TENANT, "alice", "test-client", "dtp-ledger", "transfer:write transfer:read");
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/transfers"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + bearer).header("Idempotency-Key", "restricted-admission")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"sourceAccountId\":\"" + SOURCE + "\",\"destinationAccountId\":\"" + DESTINATION + "\",\"amountCents\":100,\"currency\":\"BRL\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode(), response::body);
            var json = JsonMapper.builder().build(); UUID transfer = UUID.fromString(json.readTree(response.body()).path("transferId").stringValue());
            var worker = source("worker"); var jdbc = new JdbcTemplate(worker);
            UUID event = jdbc.queryForObject("SELECT event_id FROM canonical_events WHERE aggregate_id=? AND aggregate_version=1", UUID.class, transfer);
            String operator = issuer.token(TENANT, "operator-user", "test-client", "dtp-ledger", "transfer:operate", java.util.List.of("operator"));
            var requeue = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/transfers/" + transfer + "/requeue"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + operator).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"restricted operator recovery\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, requeue.statusCode(), requeue::body);
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM operational_audit WHERE transfer_id=? AND actor='operator-user'", Long.class, transfer));
            var process = new ProcessTransfer(new SpringTransactions(new DataSourceTransactionManager(worker)), new PostgresPosting(jdbc, json), new PostingPolicy(), Clock.systemUTC());
            assertEquals("POSTED", process.execute(new PostingRepository.Request(event, TENANT, transfer)).status());
            assertEquals(99900L, jdbc.queryForObject("SELECT balance_cents FROM accounts WHERE id=?", Long.class, UUID.fromString(SOURCE)));
            assertEquals(0L, jdbc.queryForObject("SELECT reserved_cents FROM accounts WHERE id=?", Long.class, UUID.fromString(SOURCE)));
            var client = HttpClient.newHttpClient();
            var metadata = read(client, port, SOURCE, bearer);
            assertEquals(200, metadata.statusCode());
            Files.createDirectories(Path.of("target", "artifacts")); Files.writeString(Path.of("target", "artifacts", "legacy-metadata.json"), metadata.body());
            assertEquals("LEGACY_SYNTHETIC", json.readTree(metadata.body()).path("externalReference").stringValue());
            assertEquals(1, legacy.calls.get());
            assertFalse(legacy.sawTransaction.get(), "Remote I/O must occur after API SQL commit");
            assertEquals(404, read(client, port, DESTINATION, bearer).statusCode());
            assertEquals(1, legacy.calls.get(), "Unauthorized read must never reach legacy");
            legacy.status.set(503);
            assertEquals(503, read(client, port, SOURCE, bearer).statusCode());
            assertEquals(99900L, jdbc.queryForObject("SELECT balance_cents FROM accounts WHERE id=?", Long.class, UUID.fromString(SOURCE)), "Legacy failure cannot alter financial state");
        }
    }
    @Test void runtimeIdentitiesCannotPerformDdlOrCrossRoleWrites() {
        for (String role : java.util.List.of("api", "worker", "audit")) {
            var jdbc = new JdbcTemplate(source(role));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("CREATE TABLE runtime_ddl(id integer)"));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("ALTER TABLE transfers DISABLE TRIGGER ALL"));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("SELECT * FROM flyway_schema_history"));
        }
        var api = new JdbcTemplate(source("api"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> api.execute("UPDATE accounts SET balance_cents=balance_cents"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> api.execute("INSERT INTO journals(id) VALUES (gen_random_uuid())"));
        var audit = new JdbcTemplate(source("audit"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> audit.execute("UPDATE accounts SET reserved_cents=reserved_cents"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> audit.execute("DELETE FROM transfers"));
    }
    private static DriverManagerDataSource source(String role) { return new DriverManagerDataSource(PG.getJdbcUrl(), "test_" + role, PASSWORD); }
    private static HttpResponse<String> read(HttpClient client, int port, String account, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/accounts/" + account + "/legacy"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static final class LegacyFixture implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger(200);
        private final java.util.concurrent.atomic.AtomicBoolean sawTransaction = new java.util.concurrent.atomic.AtomicBoolean();
        LegacyFixture() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/accounts/", exchange -> {
                var owner = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
                if (owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename='test_api' AND state='idle in transaction'", Long.class) > 0) sawTransaction.set(true);
                calls.incrementAndGet(); var bytes = "{\"externalReference\":\"LEGACY_SYNTHETIC\",\"state\":\"ACTIVE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status.get(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            }); server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
