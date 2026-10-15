package dev.dtp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("container")
@Testcontainers
class ContainerReadinessIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499").withExposedPorts(6379);
    static final TestIssuer ISSUER = new TestIssuer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.enabled", () -> true); r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::url); r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER.url() + "/jwks");
    }
    @AfterAll static void closeIssuer() { ISSUER.close(); }
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void redisLossRemovesReadinessWithoutRestartingLiveApi() throws Exception {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200));
        REDIS.stop();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503));
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").body()).doesNotContain("jdbc:", "password", "localhost", "redis");
    }
}
