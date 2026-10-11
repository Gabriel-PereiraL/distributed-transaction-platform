package dev.dtp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.dtp.adapters.persistence.PostgresLedger;
import dev.dtp.application.Actor;
import dev.dtp.application.AdmitTransfer;
import dev.dtp.application.TransferCommand;
import dev.dtp.domain.Money;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FoundationIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final String ISSUER;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("foundation-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            JWKS.start();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("dtp.quota.enabled", () -> false);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("management.tracing.sampling.probability", () -> 1.0);
    }
    @AfterAll static void stopIssuer() { JWKS.stop(0); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired AdmitTransfer admit;
    @MockitoSpyBean PostgresLedger persistence;
    @MockitoSpyBean dev.dtp.adapters.persistence.PostgresPosting posting;
    @Autowired dev.dtp.application.ProcessTransfer process;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    record Fixture(UUID tenant, UUID source, UUID destination) { }

    @Test void admissionCommitsReservationIdentityEventAndOutboxTogether() throws Exception {
        var f = fixture(1000);
        var response = post(f, "first", 250, token(f.tenant(), "alice", "transfer:write transfer:read"));
        assertThat(response.statusCode()).isEqualTo(202);
        var body = json.readTree(response.body());
        assertThat(body.get("status").stringValue()).isEqualTo("ACCEPTED");
        assertThat(response.headers().firstValue("Location")).contains("/v1/transfers/" + body.get("transferId").stringValue());
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(250);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(1000);
        for (var table : List.of("transfers", "admission_keys", "holds", "canonical_events")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE tenant_id=?", f.tenant())).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM outbox_deliveries o JOIN canonical_events e USING(event_id) WHERE e.tenant_id=?", f.tenant())).isEqualTo(1);
        var event = jdbc.queryForObject("SELECT payload::text FROM canonical_events WHERE tenant_id=?", String.class, f.tenant());
        assertThat(json.readTree(event).get("payload").get("amount").get("cents").longValue()).isEqualTo(250);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "artifacts", "accepted-event.json"), event);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM canonical_events WHERE tenant_id=?", String.class, f.tenant())).isEqualTo("foundation-test");
    }

    @Test void replayIsStableAndPayloadMismatchCannotReserveAgain() throws Exception {
        var f = fixture(1000); var bearer = token(f.tenant(), "alice", "transfer:write");
        var original = post(f, "key", 100, bearer);
        var replay = post(f, "key", 100, bearer);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(post(f, "key", 101, bearer).statusCode()).isEqualTo(422);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
    }

    @Test void twentyConcurrentSameKeyRequestsAdmitExactlyOnce() throws Exception {
        var f = fixture(1000); var bearer = token(f.tenant(), "alice", "transfer:write");
        var responses = concurrent(20, i -> post(f, "concurrent-key", 100, bearer));
        assertThat(responses).allMatch(r -> r.statusCode() == 202);
        assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transfers WHERE tenant_id=?", f.tenant())).isEqualTo(1);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
    }

    @Test void twentyBuyersCannotReserveTheSameLastFunds() throws Exception {
        var f = fixture(100); var bearer = token(f.tenant(), "alice", "transfer:write");
        var responses = concurrent(20, i -> post(f, "buyer-" + i, 100, bearer));
        assertThat(responses.stream().filter(r -> r.statusCode() == 202).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409).count()).isEqualTo(19);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM admission_keys WHERE tenant_id=?", f.tenant())).isEqualTo(1);
    }

    @Test void failedAdmissionRollsBackClaimAndAllowsSameKeyWithCorrectedRequest() throws Exception {
        var f = fixture(100); var bearer = token(f.tenant(), "alice", "transfer:write");
        assertThat(post(f, "retry", 101, bearer).statusCode()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM admission_keys WHERE tenant_id=?", f.tenant())).isZero();
        assertThat(post(f, "retry", 100, bearer).statusCode()).isEqualTo(202);
    }

    @Test void failureWhileRecordingEventRollsBackAllAdmissionWrites() throws Exception {
        var f = fixture(1000);
        doThrow(new IllegalStateException("injected event failure")).when(persistence).append(any());
        var response = post(f, "rollback", 100, token(f.tenant(), "alice", "transfer:write"));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("injected event failure");
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
        for (var table : List.of("transfers", "holds", "admission_keys", "canonical_events")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE tenant_id=?", f.tenant())).isZero();
        }
    }

    @Test void committedAdmissionCanBeRecoveredWithoutOriginalHttpResponse() throws Exception {
        var f = fixture(1000);
        var committed = admit.execute(new Actor(f.tenant(), "alice", "test-client"), "lost-response",
                new TransferCommand(f.source(), f.destination(), new Money(100, "BRL")), "test", null);
        var recovered = post(f, "lost-response", 100, token(f.tenant(), "alice", "transfer:write"));
        assertThat(recovered.statusCode()).isEqualTo(202);
        assertThat(json.readTree(recovered.body()).get("transferId").stringValue()).isEqualTo(committed.admission().transferId().toString());
        assertThat(count("SELECT count(*) FROM transfers WHERE tenant_id=?", f.tenant())).isEqualTo(1);
    }

    @Test void identityScopeSeparatesClientKeysWithoutLeakingTransfers() throws Exception {
        var f = fixture(1000);
        assertThat(post(f, "shared", 100, token(f.tenant(), "alice", "transfer:write")).statusCode()).isEqualTo(202);
        assertThat(post(f, "shared", 100, signed(f.tenant(), "alice", "other-client", "dtp-ledger", "transfer:write", ISSUER, Instant.now().plusSeconds(300))).statusCode()).isEqualTo(202);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(200);
    }

    @Test void wrongTenantAndForeignOwnerCannotAdmitOrRead() throws Exception {
        var f = fixture(1000);
        assertThat(post(f, "foreign-owner", 100, token(f.tenant(), "mallory", "transfer:write")).statusCode()).isEqualTo(404);
        assertThat(post(f, "foreign-tenant", 100, token(UUID.randomUUID(), "alice", "transfer:write")).statusCode()).isEqualTo(404);
        assertThat(get("/v1/accounts/" + f.source(), token(f.tenant(), "mallory", "transfer:read")).statusCode()).isEqualTo(404);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
    }

    @Test void crossTenantDestinationIsRejectedByAdmissionAndDatabase() throws Exception {
        var f = fixture(1000); var other = fixture(1000);
        var cross = new Fixture(f.tenant(), f.source(), other.destination());
        assertThat(post(cross, "cross", 100, token(f.tenant(), "alice", "transfer:write")).statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> jdbc.update("""
                INSERT INTO transfers(id,tenant_id,initiated_by,source_account_id,destination_account_id,amount_cents,currency,status,accepted_at,rule_version,aggregate_version)
                VALUES (?,?, 'alice',?,?,100,'BRL','ACCEPTED',now(),'synthetic-v1',1)
                """, UUID.randomUUID(), f.tenant(), f.source(), other.destination()))).isInstanceOf(RuntimeException.class);
    }

    @Test void scopesAreRequiredAndUnauthenticatedMutationIsRejected() throws Exception {
        var f = fixture(1000);
        assertThat(post(f, "no-auth", 100, null).statusCode()).isEqualTo(401);
        assertThat(post(f, "read-only", 100, token(f.tenant(), "alice", "transfer:read")).statusCode()).isEqualTo(403);
        assertThat(get("/v1/accounts/" + f.source(), token(f.tenant(), "alice", "transfer:write")).statusCode()).isEqualTo(403);
    }

    @Test void expiredWrongIssuerAndWrongAudienceAreRejected() throws Exception {
        var f = fixture(1000);
        for (var bearer : List.of(
                signed(f.tenant(), "alice", "test-client", "wrong-audience", "transfer:write", ISSUER, Instant.now().plusSeconds(300)),
                signed(f.tenant(), "alice", "test-client", "dtp-ledger", "transfer:write", "https://wrong.example", Instant.now().plusSeconds(300)),
                signed(f.tenant(), "alice", "test-client", "dtp-ledger", "transfer:write", ISSUER, Instant.now().minusSeconds(300)))) {
            assertThat(post(f, "bad-token", 100, bearer).statusCode()).isEqualTo(401);
        }
    }

    @Test void missingTenantClaimIs401InsteadOfServerError() throws Exception {
        var f = fixture(1000);
        var bearer = signed(null, "alice", "test-client", "dtp-ledger", "transfer:write", ISSUER, Instant.now().plusSeconds(300));
        assertThat(post(f, "missing-tenant", 100, bearer).statusCode()).isEqualTo(401);
    }

    @Test void validationAndBodyLimitNeverReserve() throws Exception {
        var f = fixture(1000); var bearer = token(f.tenant(), "alice", "transfer:write");
        assertThat(post(f, "invalid", 0, bearer).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/transfers", "{}", null, bearer).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/transfers", "{invalid", "bad-json", bearer).statusCode()).isEqualTo(400);
        var validBody = "{\"sourceAccountId\":\"" + f.source() + "\",\"destinationAccountId\":\"" + f.destination() + "\",\"amountCents\":1,\"currency\":\"BRL\"}";
        assertThat(send("POST", "/v1/transfers", validBody.replace("\"amountCents\":1", "\"amountCents\":1.5"), "fraction", bearer).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/transfers", validBody.replace("\"amountCents\":1", "\"amountCents\":\"1\""), "string-amount", bearer).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/transfers", validBody.replace("}", ",\"tenantId\":\"" + UUID.randomUUID() + "\"}"), "unknown-field", bearer).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/transfers", "x".repeat(17000), "oversize", bearer).statusCode()).isEqualTo(413);
        assertThat(post(new Fixture(f.tenant(), f.source(), f.source()), "same-account", 1, bearer).statusCode()).isEqualTo(400);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
    }

    @Test void authoritativeQueriesRespectOwnershipAndExposeReservation() throws Exception {
        var f = fixture(1000); var bearer = token(f.tenant(), "alice", "transfer:write transfer:read");
        var accepted = post(f, "read", 300, bearer);
        var id = json.readTree(accepted.body()).get("transferId").stringValue();
        var account = get("/v1/accounts/" + f.source(), bearer);
        assertThat(account.statusCode()).isEqualTo(200);
        assertThat(json.readTree(account.body()).get("availableCents").longValue()).isEqualTo(700);
        assertThat(get("/v1/transfers/" + id, bearer).statusCode()).isEqualTo(200);
        assertThat(get("/v1/transfers/" + id, token(f.tenant(), "bob", "transfer:read")).statusCode()).isEqualTo(404);
        assertThat(account.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(account.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test void sqlRejectsUnrecordedBalanceAndOrphanReservation() {
        var f = fixture(1000);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> jdbc.update("UPDATE accounts SET balance_cents=balance_cents+1 WHERE id=?", f.source()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> jdbc.update("UPDATE accounts SET reserved_cents=1 WHERE id=?", f.source()))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(1000);
    }

    @Test void sqlRejectsUnbalancedJournalAndMutationOfFinancialEntry() {
        var f = fixture(1000);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var journal = UUID.randomUUID();
            jdbc.update("INSERT INTO journals(id,tenant_id,purpose,currency,created_at) VALUES (?,?,'SYNTHETIC_FUNDING','BRL',now())", journal, f.tenant());
            jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,1)", UUID.randomUUID(), f.tenant(), journal, f.source());
        })).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET signed_cents=signed_cents+1 WHERE tenant_id=?", f.tenant())).isInstanceOf(RuntimeException.class);
    }

    @Test void migrationsAreCompleteAndVersioned() {
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success=true")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank", String.class)).containsExactly("1", "2");
    }

    @Test void explicitLocalSeedIsBalancedAndIdempotent() throws Exception {
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("..", "docs", "local-seed.sql"));
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(script);
            statement.execute(script);
        }
        var tenant = UUID.fromString("10000000-0000-0000-0000-000000000001");
        assertThat(count("SELECT count(*) FROM accounts WHERE tenant_id=?", tenant)).isEqualTo(3);
        assertThat(count("SELECT sum(signed_cents) FROM journal_entries WHERE tenant_id=?", tenant)).isZero();
        assertThat(count("SELECT count(*) FROM journals WHERE tenant_id=?", tenant)).isEqualTo(1);
        var technical = UUID.fromString("20000000-0000-0000-0000-000000000003");
        assertThat(get("/v1/accounts/" + technical, token(tenant, "system", "transfer:read")).statusCode()).isEqualTo(404);
    }

    @Test void healthEndpointsArePublicButOtherActuatorPathsAreDenied() throws Exception {
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(401);
    }

    @Test void postingIsDoubleEntryAndReplayReturnsOriginalAdmission() throws Exception {
        var f = fixture(1000); var bearer = token(f.tenant(), "alice", "transfer:write transfer:read");
        var admission = post(f, "posting", 250, bearer); var request = processing(f);
        assertThat(process.execute(request)).isEqualTo(new dev.dtp.application.ProcessTransfer.Result("POSTED", false));
        assertThat(process.execute(request)).isEqualTo(new dev.dtp.application.ProcessTransfer.Result("POSTED", true));
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(750);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.destination())).isEqualTo(250);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
        assertThat(count("SELECT count(*) FROM holds WHERE tenant_id=?", f.tenant())).isZero();
        assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", request.transferId())).isEqualTo(1);
        assertThat(count("SELECT sum(signed_cents) FROM journal_entries WHERE journal_id=(SELECT id FROM journals WHERE transfer_id=?)", request.transferId())).isZero();
        assertThat(count("SELECT count(*) FROM processed_events WHERE transfer_id=?", request.transferId())).isEqualTo(1);
        assertThat(post(f, "posting", 250, bearer).body()).isEqualTo(admission.body());
        var view = json.readTree(get("/v1/transfers/" + request.transferId(), bearer).body());
        assertThat(view.get("status").stringValue()).isEqualTo("POSTED");
        assertThat(view.get("aggregateVersion").longValue()).isEqualTo(2);
        assertThat(get("/v1/transfers/" + request.transferId(), token(f.tenant(), "bob", "transfer:read")).statusCode()).isEqualTo(404);
        var event = jdbc.queryForObject("SELECT payload::text FROM canonical_events WHERE aggregate_id=? AND aggregate_version=2", String.class, request.transferId());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "artifacts", "posted-event.json"), event);
    }

    @Test void syntheticPolicyRejectsAndReleasesWithoutMovingMoney() throws Exception {
        var f = fixture(600000); post(f, "limit", 500001, token(f.tenant(), "alice", "transfer:write"));
        var request = processing(f);
        assertThat(process.execute(request).status()).isEqualTo("REJECTED");
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(600000);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.destination())).isZero();
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
        assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", request.transferId())).isZero();
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM transfers WHERE id=?", String.class, request.transferId())).isEqualTo("SYNTHETIC_LIMIT");
        var event = jdbc.queryForObject("SELECT payload::text FROM canonical_events WHERE aggregate_id=? AND aggregate_version=2", String.class, request.transferId());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "artifacts", "rejected-event.json"), event);
    }

    @Test void inactiveAccountAfterAdmissionRejectsDeterministically() throws Exception {
        var f = fixture(1000); post(f, "inactive-later", 100, token(f.tenant(), "alice", "transfer:write"));
        jdbc.update("UPDATE accounts SET active=false WHERE id=?", f.destination());
        assertThat(process.execute(processing(f)).status()).isEqualTo("REJECTED");
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
    }

    @Test void failedTerminalEventRollsBackJournalBalancesHoldAndDedup() throws Exception {
        var f = fixture(1000); post(f, "terminal-rollback", 100, token(f.tenant(), "alice", "transfer:write"));
        var request = processing(f);
        doThrow(new IllegalStateException("injected terminal persistence failure")).when(posting).appendTerminal(any(), any(), any());
        assertThatThrownBy(() -> process.execute(request)).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(1000);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM holds WHERE transfer_id=?", request.transferId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", request.transferId())).isZero();
        assertThat(count("SELECT count(*) FROM processed_events WHERE transfer_id=?", request.transferId())).isZero();
        org.mockito.Mockito.doCallRealMethod().when(posting).appendTerminal(any(), any(), any());
        assertThat(process.execute(request).status()).isEqualTo("POSTED");
    }

    @Test void concurrentRedeliveriesHaveOneFinancialEffect() throws Exception {
        var f = fixture(1000); post(f, "concurrent-posting", 100, token(f.tenant(), "alice", "transfer:write"));
        var request = processing(f); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<dev.dtp.application.ProcessTransfer.Result>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> { start.await(); return process.execute(request); }));
            start.countDown(); var results = new ArrayList<dev.dtp.application.ProcessTransfer.Result>();
            for (var future : futures) results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> !r.alreadyTerminal()).count()).isEqualTo(1);
            assertThat(results).allMatch(r -> r.status().equals("POSTED"));
        }
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(900);
        assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", request.transferId())).isEqualTo(1);
    }

    @Test void unknownEventAndCrossTenantCannotProcessCanonicalTransfer() throws Exception {
        var f = fixture(1000); post(f, "canonical-only", 100, token(f.tenant(), "alice", "transfer:write"));
        var request = processing(f);
        assertThatThrownBy(() -> process.execute(new dev.dtp.application.PostingRepository.Request(UUID.randomUUID(), f.tenant(), request.transferId())))
                .isInstanceOf(dev.dtp.application.InvalidEvent.class);
        assertThatThrownBy(() -> process.execute(new dev.dtp.application.PostingRepository.Request(request.eventId(), UUID.randomUUID(), request.transferId())))
                .isInstanceOf(dev.dtp.application.InvalidEvent.class);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
    }

    @Test void databaseRejectsTerminalizationWithoutItsAtomicEvidence() throws Exception {
        var f = fixture(1000); post(f, "illegal-terminal", 100, token(f.tenant(), "alice", "transfer:write"));
        var request = processing(f);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("DELETE FROM holds WHERE transfer_id=?", request.transferId());
            jdbc.update("UPDATE accounts SET reserved_cents=0 WHERE id=?", f.source());
            jdbc.update("UPDATE transfers SET status='POSTED',aggregate_version=2,terminal_at=now() WHERE id=?", request.transferId());
        })).isInstanceOf(RuntimeException.class);
        process.execute(request);
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status='REJECTED',rejection_reason='SYNTHETIC_LIMIT' WHERE id=?", request.transferId()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM processed_events WHERE transfer_id=?", request.transferId())).isInstanceOf(RuntimeException.class);
    }

    private dev.dtp.application.PostingRepository.Request processing(Fixture f) {
        return jdbc.queryForObject("SELECT event_id,aggregate_id FROM canonical_events WHERE tenant_id=? AND aggregate_version=1",
                (rs, index) -> new dev.dtp.application.PostingRepository.Request(rs.getObject(1, UUID.class), f.tenant(), rs.getObject(2, UUID.class)), f.tenant());
    }

    @Test void migrationUpgradesExistingV1AdmissionWithoutRewritingHistory() throws Exception {
        var schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var flywayV1 = org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).target("1").load();
        flywayV1.migrate();
        var sql = new JdbcTemplate(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(java.nio.file.Files.readString(java.nio.file.Path.of("..", "docs", "local-seed.sql")));
        }
        var tenant = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var from = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var to = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var transfer = new dev.dtp.domain.Transfer(UUID.randomUUID(), tenant, "alice", from, to, new Money(100, "BRL"),
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), dev.dtp.domain.Transfer.RULE_VERSION);
        var event = new dev.dtp.application.AcceptedEvent(UUID.randomUUID(), "TransferAccepted", 1, tenant, transfer.id(), 1,
                transfer.acceptedAt(), transfer, "upgrade-test", null);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            sql.update("UPDATE accounts SET reserved_cents=100 WHERE id=?", from);
            sql.update("INSERT INTO transfers(id,tenant_id,initiated_by,source_account_id,destination_account_id,amount_cents,currency,status,accepted_at,rule_version,aggregate_version) VALUES (?,?,'alice',?,?,100,'BRL','ACCEPTED',?,'synthetic-v1',1)",
                    transfer.id(), tenant, from, to, java.time.OffsetDateTime.ofInstant(transfer.acceptedAt(), java.time.ZoneOffset.UTC));
            sql.update("INSERT INTO holds(transfer_id,tenant_id,account_id,amount_cents) VALUES (?,?,?,100)", transfer.id(), tenant, from);
            sql.update("INSERT INTO canonical_events(event_id,tenant_id,aggregate_id,aggregate_version,event_type,schema_version,occurred_at,payload,correlation_id) VALUES (?,?,?,1,'TransferAccepted',1,now(),CAST(? AS jsonb),'upgrade-test')",
                    event.eventId(), tenant, transfer.id(), json.writeValueAsString(event));
            sql.update("INSERT INTO outbox_deliveries(event_id,created_at,next_attempt_at) VALUES (?,now(),now())", event.eventId());
        });
        var checksum = sql.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).load().migrate();
        assertThat(sql.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class)).isEqualTo(checksum);
        assertThat(sql.queryForObject("SELECT status FROM transfers WHERE id=?", String.class, transfer.id())).isEqualTo("ACCEPTED");
        var upgraded = new dev.dtp.application.ProcessTransfer(new dev.dtp.adapters.persistence.SpringTransactions(manager),
                new dev.dtp.adapters.persistence.PostgresPosting(sql, json), new dev.dtp.domain.PostingPolicy(), java.time.Clock.systemUTC());
        assertThat(upgraded.execute(new dev.dtp.application.PostingRepository.Request(event.eventId(), tenant, transfer.id())).status()).isEqualTo("POSTED");
        assertThat(sql.queryForObject("SELECT balance_cents FROM accounts WHERE id=?", Long.class, from)).isEqualTo(99900);
        assertThat(sql.queryForObject("SELECT count(*) FROM canonical_events", Long.class)).isEqualTo(2);
    }

    @Test void postingAndNewAdmissionSerializeOnSharedAccountsWithoutLosingReservations() throws Exception {
        var f = fixture(1000); post(f, "first-hold", 250, token(f.tenant(), "alice", "transfer:write"));
        var first = processing(f); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var postingFuture = executor.submit(() -> { start.await(); return process.execute(first); });
            var admissionFuture = executor.submit(() -> { start.await(); return admit.execute(new Actor(f.tenant(), "alice", "test-client"), "last-available",
                    new TransferCommand(f.source(), f.destination(), new Money(750, "BRL")), "posting-admission-race", null); });
            start.countDown(); assertThat(postingFuture.get(20, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("POSTED");
            var second = admissionFuture.get(20, java.util.concurrent.TimeUnit.SECONDS).admission().transferId();
            var secondEvent = jdbc.queryForObject("SELECT event_id FROM canonical_events WHERE aggregate_id=? AND aggregate_version=1", UUID.class, second);
            assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(750);
            assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(750);
            process.execute(new dev.dtp.application.PostingRepository.Request(secondEvent, f.tenant(), second));
        }
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isZero();
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.destination())).isEqualTo(1000);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
    }

    @Test void postedJournalCannotBeExtendedOrAcceptedDedupInsertedEarly() throws Exception {
        var f = fixture(1000); post(f, "journal-seal", 100, token(f.tenant(), "alice", "transfer:write")); var request = processing(f);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO processed_events(event_id,tenant_id,transfer_id,processed_at) VALUES (?,?,?,now())",
                request.eventId(), f.tenant(), request.transferId())).isInstanceOf(RuntimeException.class);
        process.execute(request);
        var journal = jdbc.queryForObject("SELECT id FROM journals WHERE transfer_id=?", UUID.class, request.transferId());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,1),(?,?,?,?,-1)",
                    UUID.randomUUID(), f.tenant(), journal, f.source(), UUID.randomUUID(), f.tenant(), journal, f.destination());
            jdbc.update("UPDATE accounts SET balance_cents=balance_cents+1 WHERE id=?", f.source());
            jdbc.update("UPDATE accounts SET balance_cents=balance_cents-1 WHERE id=?", f.destination());
        })).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM journal_entries WHERE journal_id=?", journal)).isEqualTo(2);
    }

    @Test void rejectedStateMustHaveNonNullReasonEvenWithDeferredConstraints() throws Exception {
        var f = fixture(1000); post(f, "reason-required", 100, token(f.tenant(), "alice", "transfer:write")); var request = processing(f);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s ->
                jdbc.update("UPDATE transfers SET status='REJECTED',aggregate_version=2,terminal_at=now(),rejection_reason=NULL WHERE id=?", request.transferId())))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("transfer_state");
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id=?", String.class, request.transferId())).isEqualTo("ACCEPTED");
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
    }

    @Test void auditorReadsOnlyItsTenantCustomersAndCannotAdmit() throws Exception {
        var f = fixture(1000); var other = fixture(1000);
        var accepted = post(f, "audited", 100, token(f.tenant(), "alice", "transfer:write"));
        String id = json.readTree(accepted.body()).path("transferId").stringValue();
        String audit = signed(f.tenant(), "audit-user", "test-client", "dtp-ledger", "transfer:read transfer:write", ISSUER, Instant.now().plusSeconds(300), List.of("auditor"));
        assertThat(get("/v1/accounts/" + f.source(), audit).statusCode()).isEqualTo(200);
        assertThat(get("/v1/accounts/" + f.destination(), audit).statusCode()).isEqualTo(200);
        assertThat(get("/v1/transfers/" + id, audit).statusCode()).isEqualTo(200);
        assertThat(get("/v1/accounts/" + other.source(), audit).statusCode()).isEqualTo(404);
        UUID technical = jdbc.queryForObject("SELECT id FROM accounts WHERE tenant_id=? AND kind='TECHNICAL'", UUID.class, f.tenant());
        assertThat(get("/v1/accounts/" + technical, audit).statusCode()).isEqualTo(404);
        assertThat(post(f, "audit-denied", 100, audit).statusCode()).isEqualTo(403);
        String noScope = signed(f.tenant(), "audit-user", "test-client", "dtp-ledger", "transfer:write", ISSUER, Instant.now().plusSeconds(300), List.of("auditor"));
        assertThat(get("/v1/accounts/" + f.source(), noScope).statusCode()).isEqualTo(403);
    }

    @Test void operatorRequeueIsTenantScopedAuditedAndPreservesCanonicalMoney() throws Exception {
        var f = fixture(1000); post(f, "requeue-http", 100, token(f.tenant(), "alice", "transfer:write")); var request = processing(f);
        String payload = jdbc.queryForObject("SELECT payload::text FROM canonical_events WHERE event_id=?", String.class, request.eventId());
        jdbc.update("UPDATE outbox_deliveries SET status='PUBLISHED',published_at=now() WHERE event_id=?", request.eventId());
        String op = operator(f.tenant());
        var response = requeue(request.transferId(), op, "recover laboratory delivery");
        assertThat(response.statusCode()).isEqualTo(202);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "artifacts", "requeue-queued.json"), response.body());
        assertThat(json.readTree(response.body()).path("eventId").stringValue()).isEqualTo(request.eventId().toString());
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE event_id=?", String.class, request.eventId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM canonical_events WHERE event_id=?", String.class, request.eventId())).isEqualTo(payload);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(1000);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
        assertThat(count("SELECT aggregate_version FROM transfers WHERE id=?", request.transferId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=? AND action='REQUEUE_REQUESTED' AND actor='operator-user'", request.transferId())).isEqualTo(1);
        assertThat(requeue(request.transferId(), operator(UUID.randomUUID()), "wrong tenant").statusCode()).isEqualTo(404);
        assertThat(requeue(request.transferId(), token(f.tenant(), "alice", "transfer:operate"), "wrong role").statusCode()).isEqualTo(403);
        String admin = signed(f.tenant(), "admin-user", "test-client", "dtp-ledger", "transfer:operate", ISSUER, Instant.now().plusSeconds(300), List.of("admin"));
        assertThat(requeue(request.transferId(), admin, "no implicit permission").statusCode()).isEqualTo(403);
        assertThat(requeue(request.transferId(), op, " ").statusCode()).isEqualTo(400);
        assertThat(post(f, "operator-no-admission", 100, op).statusCode()).isEqualTo(403);
    }

    @Test void liveLeaseBlocksRequeueAndExpiredLeaseIsFenced() throws Exception {
        var f = fixture(1000); post(f, "requeue-lease", 100, token(f.tenant(), "alice", "transfer:write")); var request = processing(f);
        UUID staleToken = UUID.randomUUID();
        jdbc.update("UPDATE outbox_deliveries SET status='CLAIMED',claim_token=?,lease_until=clock_timestamp()+interval '60 seconds' WHERE event_id=?", staleToken, request.eventId());
        assertThat(requeue(request.transferId(), operator(f.tenant()), "busy").statusCode()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=?", request.transferId())).isZero();
        jdbc.update("UPDATE outbox_deliveries SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?", request.eventId());
        assertThat(requeue(request.transferId(), operator(f.tenant()), "expired claim").statusCode()).isEqualTo(202);
        var stale = new dev.dtp.adapters.persistence.PostgresOutbox.Delivery(request.eventId(), f.tenant(), request.transferId(), 1, "{}", staleToken, 1);
        assertThat(new dev.dtp.adapters.persistence.PostgresOutbox(jdbc, transactions).published(stale)).isFalse();
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
    }

    @Test void requeueAuditFailureRollsBackDeliveryReset() throws Exception {
        var f = fixture(1000); post(f, "requeue-audit-fault", 100, token(f.tenant(), "alice", "transfer:write")); var request = processing(f);
        jdbc.update("UPDATE outbox_deliveries SET status='PUBLISHED',published_at=now() WHERE event_id=?", request.eventId());
        jdbc.execute("CREATE FUNCTION test_requeue_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic audit fault' USING ERRCODE='40001'; END $$");
        jdbc.execute("CREATE TRIGGER test_requeue_audit_fault BEFORE INSERT ON operational_audit FOR EACH ROW WHEN (NEW.reason='force-rollback') EXECUTE FUNCTION test_requeue_audit_fault()");
        try {
            assertThat(requeue(request.transferId(), operator(f.tenant()), "force-rollback").statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE event_id=?", String.class, request.eventId())).isEqualTo("PUBLISHED");
            assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=?", request.transferId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_requeue_audit_fault ON operational_audit"); jdbc.execute("DROP FUNCTION test_requeue_audit_fault()");
        }
    }

    @Test void terminalRequeueIsAuditedNoOpForPostedAndRejectedTransfers() throws Exception {
        for (long amount : List.of(100L, 600000L)) {
            var f = fixture(1000000); post(f, "terminal-requeue", amount, token(f.tenant(), "alice", "transfer:write")); var request = processing(f);
            var result = process.execute(request); long balance = count("SELECT balance_cents FROM accounts WHERE id=?", f.source());
            String before = jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE event_id=?", String.class, request.eventId());
            var response = requeue(request.transferId(), operator(f.tenant()), "terminal should not replay");
            assertThat(response.statusCode()).isEqualTo(200); assertThat(json.readTree(response.body()).path("outcome").stringValue()).isEqualTo("ALREADY_TERMINAL");
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target", "artifacts", "requeue-terminal.json"), response.body());
            assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id=?", String.class, request.transferId())).isEqualTo(result.status());
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE event_id=?", String.class, request.eventId())).isEqualTo(before);
            assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(balance);
            assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
            assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=? AND action='REQUEUE_ALREADY_TERMINAL'", request.transferId())).isEqualTo(1);
        }
    }
    private HttpResponse<String> requeue(UUID transfer, String bearer, String reason) throws Exception {
        return send("POST", "/v1/transfers/" + transfer + "/requeue", json.writeValueAsString(java.util.Map.of("reason", reason)), null, bearer);
    }
    private String operator(UUID tenant) throws Exception {
        return signed(tenant, "operator-user", "test-client", "dtp-ledger", "transfer:operate", ISSUER, Instant.now().plusSeconds(300), List.of("operator"));
    }
    private Fixture fixture(long balance) {
        var tenant = UUID.randomUUID(); var source = UUID.randomUUID(); var destination = UUID.randomUUID(); var technical = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'alice','CUSTOMER','BRL',?)", source, tenant, balance);
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'bob','CUSTOMER','BRL',0)", destination, tenant);
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'system','TECHNICAL','BRL',?)", technical, tenant, -balance);
            if (balance > 0) {
                var journal = UUID.randomUUID();
                jdbc.update("INSERT INTO journals(id,tenant_id,purpose,currency,created_at) VALUES (?,?,'SYNTHETIC_FUNDING','BRL',now())", journal, tenant);
                jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,?)", UUID.randomUUID(), tenant, journal, source, balance);
                jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,?)", UUID.randomUUID(), tenant, journal, technical, -balance);
            }
        });
        return new Fixture(tenant, source, destination);
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private HttpResponse<String> post(Fixture f, String key, long amount, String bearer) throws Exception {
        return send("POST", "/v1/transfers", "{\"sourceAccountId\":\"" + f.source() + "\",\"destinationAccountId\":\"" + f.destination()
                + "\",\"amountCents\":" + amount + ",\"currency\":\"BRL\"}", key, bearer);
    }
    private HttpResponse<String> get(String path, String bearer) throws Exception { return send("GET", path, null, null, bearer); }
    private HttpResponse<String> send(String method, String path, String body, String key, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(20))
                .header("X-Correlation-Id", "foundation-test");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token(UUID tenant, String subject, String scope) throws Exception {
        return signed(tenant, subject, "test-client", "dtp-ledger", scope, ISSUER, Instant.now().plusSeconds(300));
    }
    private static String signed(UUID tenant, String subject, String client, String audience, String scope, String issuer, Instant expiration) throws Exception {
        return signed(tenant, subject, client, audience, scope, issuer, expiration, java.util.List.of("transfer-client"));
    }
    private static String signed(UUID tenant, String subject, String client, String audience, String scope, String issuer, Instant expiration, java.util.List<String> roles) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiration))
                .claim("azp", client).claim("scope", scope)
                .claim("realm_access", java.util.Map.of("roles", roles));
        if (tenant != null) claims.claim("tenant_id", tenant.toString());
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
    @FunctionalInterface interface RequestTask { HttpResponse<String> run(int index) throws Exception; }
    private static List<HttpResponse<String>> concurrent(int count, RequestTask task) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> { start.await(); return task.run(index); }));
            }
            start.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var future : futures) results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
    }
}
