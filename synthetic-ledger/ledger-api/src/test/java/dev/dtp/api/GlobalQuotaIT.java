package dev.dtp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dtp.adapters.quota.RedisMutationQuota;
import dev.dtp.application.Actor;
import dev.dtp.application.QuotaExceeded;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GlobalQuotaIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499").withExposedPorts(6379);
    static final TestIssuer ISSUER = new TestIssuer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.enabled", () -> true); r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::url); r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER.url() + "/jwks");
        r.add("dtp.quota.enabled", () -> true); r.add("dtp.quota.limit", () -> 6); r.add("dtp.quota.window-ms", () -> 60000);
    }
    @AfterAll static void closeIssuer() { ISSUER.close(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    record Fixture(UUID tenant, UUID source, UUID destination) { }

    @Test void twoApiInstancesShareOneAtomicQuotaAndNoExtraFinancialReservations() throws Exception {
        var f = fixture(); var token = token(f, "client-global");
        try (var second = SpringApplication.run(LedgerApplication.class,
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--spring.flyway.enabled=false", "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER.url(),
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + ISSUER.url() + "/jwks", "--dtp.quota.enabled=true", "--dtp.quota.limit=6", "--dtp.quota.window-ms=60000")) {
            int otherPort = ((WebServerApplicationContext) second).getWebServer().getPort();
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<Future<HttpResponse<String>>>();
                for (int i = 0; i < 20; i++) { int index = i; tasks.add(executor.submit(() -> { start.await(); return post(index % 2 == 0 ? port : otherPort, f, "shared-" + index, token); })); }
                start.countDown(); var responses = new ArrayList<HttpResponse<String>>();
                for (var future : tasks) responses.add(future.get(30, TimeUnit.SECONDS));
                assertThat(responses.stream().filter(r -> r.statusCode() == 202).count()).isEqualTo(6);
                assertThat(responses.stream().filter(r -> r.statusCode() == 429).count()).isEqualTo(14);
                assertThat(responses.stream().filter(r -> r.statusCode() == 429)).allMatch(r -> r.headers().firstValue("Retry-After").isPresent());
            }
        }
        assertThat(count("SELECT count(*) FROM transfers WHERE tenant_id=?", f.tenant())).isEqualTo(6);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(600);
        var key = RedisMutationQuota.key(new Actor(f.tenant(), "alice", "client-global"));
        assertThat(redis.opsForValue().get(key)).isEqualTo("20");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
    }

    @Test void clientsAndTenantsHaveSeparateBucketsButSubjectsWithinClientShareBudget() throws Exception {
        var f = fixture();
        for (int i = 0; i < 6; i++) assertThat(post(port, f, "first-" + i, token(f, "client-a")).statusCode()).isEqualTo(202);
        assertThat(post(port, f, "blocked", token(f, "client-a")).statusCode()).isEqualTo(429);
        assertThat(post(port, f, "other-client", token(f, "client-b")).statusCode()).isEqualTo(202);
        var other = fixture(); assertThat(post(port, other, "other-tenant", token(other, "client-a")).statusCode()).isEqualTo(202);
        assertThat(RedisMutationQuota.key(new Actor(f.tenant(), "alice", "client-a"))).isEqualTo(RedisMutationQuota.key(new Actor(f.tenant(), "bob", "client-a")));
    }

    @Test void expiryIsAtomicAndDoesNotRefreshOnDeniedRequests() {
        var actor = new Actor(UUID.randomUUID(), "alice", "expiry-client"); var limiter = new RedisMutationQuota(redis, 1, 300);
        limiter.check(actor); long originalTtl = redis.getExpire(RedisMutationQuota.key(actor), TimeUnit.MILLISECONDS);
        assertThatThrownBy(() -> limiter.check(actor)).isInstanceOf(QuotaExceeded.class);
        assertThat(redis.getExpire(RedisMutationQuota.key(actor), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(originalTtl);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(redis.hasKey(RedisMutationQuota.key(actor))).isFalse());
        limiter.check(actor); assertThat(redis.opsForValue().get(RedisMutationQuota.key(actor))).isEqualTo("1");
    }

    @Test void redisOutageFailsClosedBeforeSqlAndReadAuthorizationStillWorks() throws Exception {
        var f = fixture(); var bearer = token(f, "outage-client");
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            var response = post(port, f, "outage-key", bearer);
            assertThat(response.statusCode()).isEqualTo(503); assertThat(response.body()).contains("quota.unavailable").doesNotContain("redis", "6379");
            assertThat(count("SELECT count(*) FROM transfers WHERE tenant_id=?", f.tenant())).isZero();
            assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isZero();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/accounts/" + f.source())).header("Authorization", "Bearer " + bearer).GET().build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        } finally { DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(redis.opsForValue().get("nonexistent-health-key")).isNull());
        assertThat(post(port, f, "outage-key", bearer).statusCode()).isEqualTo(202);
    }

    @Test void unauthenticatedCallsCannotAllocateQuotaKeysOrReservations() throws Exception {
        var f = fixture(); var actor = new Actor(f.tenant(), "alice", "test-client");
        assertThat(post(port, f, "unauthenticated", null).statusCode()).isEqualTo(401);
        assertThat(redis.hasKey(RedisMutationQuota.key(actor))).isFalse();
        assertThat(count("SELECT count(*) FROM transfers WHERE tenant_id=?", f.tenant())).isZero();
    }

    @Test void requeueQuotaHasSeparateBoundedBucketAndNeverReservesAgain() throws Exception {
        var f = fixture(); String client = "operation-buckets";
        var admitted = post(port, f, "bucket-admission", token(f, client)); assertThat(admitted.statusCode()).isEqualTo(202);
        String transfer = tools.jackson.databind.json.JsonMapper.builder().build().readTree(admitted.body()).path("transferId").stringValue();
        String operator = ISSUER.token(f.tenant(), "operator-user", client, "dtp-ledger", "transfer:operate", java.util.List.of("operator"));
        for (int i = 0; i < 7; i++) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/transfers/" + transfer + "/requeue"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("Authorization", "Bearer " + operator)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"quota laboratory\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(i < 6 ? 202 : 429);
        }
        assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=?", UUID.fromString(transfer))).isEqualTo(6);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(100);
        assertThat(post(port, f, "bucket-second-admission", token(f, client)).statusCode()).isEqualTo(202);
        var actor = new Actor(f.tenant(), "operator-user", client);
        assertThat(redis.opsForValue().get(RedisMutationQuota.key(actor))).isEqualTo("2");
        assertThat(redis.opsForValue().get(RedisMutationQuota.key(actor, "POST /v1/transfers/{id}/requeue"))).isEqualTo("7");
    }
    private String token(Fixture f, String client) throws Exception { return ISSUER.token(f.tenant(), "alice", client, "dtp-ledger", "transfer:write transfer:read"); }
    private HttpResponse<String> post(int serverPort, Fixture f, String key, String bearer) throws Exception {
        var body = "{\"sourceAccountId\":\"" + f.source() + "\",\"destinationAccountId\":\"" + f.destination() + "\",\"amountCents\":100,\"currency\":\"BRL\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/v1/transfers")).timeout(Duration.ofSeconds(10))
                .header("Idempotency-Key", key).header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private Fixture fixture() {
        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); var technical = UUID.randomUUID(); var journal = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'alice','CUSTOMER','BRL',5000)", f.source(), f.tenant());
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'bob','CUSTOMER','BRL',0)", f.destination(), f.tenant());
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'system','TECHNICAL','BRL',-5000)", technical, f.tenant());
            jdbc.update("INSERT INTO journals(id,tenant_id,purpose,currency,created_at) VALUES (?,?,'SYNTHETIC_FUNDING','BRL',now())", journal, f.tenant());
            jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,5000),(?,?,?,?,-5000)",
                    UUID.randomUUID(), f.tenant(), journal, f.source(), UUID.randomUUID(), f.tenant(), journal, technical);
        });
        return f;
    }
}
