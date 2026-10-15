package dev.dtp.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = LedgerWorker.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=true", "dtp.publisher.enabled=false"})
@ActiveProfiles("container")
@Testcontainers
class ContainerProbeIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @LocalServerPort int port;
    @Autowired KafkaListenerEndpointRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private int status(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test void stoppedConsumerIsUnreadyButLiveAndHasNoFinancialHttpSurface() throws Exception {
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(status("/actuator/health/readiness")).isEqualTo(200));
        assertThat(status("/v1/transfers")).isEqualTo(404);
        var listener = registry.getListenerContainer("posting");
        assertThat(listener).isNotNull();
        listener.stop();
        assertThat(status("/actuator/health/readiness")).isEqualTo(503);
        assertThat(status("/actuator/health/liveness")).isEqualTo(200);
        listener.start();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(status("/actuator/health/readiness")).isEqualTo(200));
    }
}
