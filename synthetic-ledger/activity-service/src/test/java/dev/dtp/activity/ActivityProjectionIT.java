package dev.dtp.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;

import dev.dtp.contracts.InvalidContract;
import dev.dtp.contracts.TransferEventDecoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = ActivityApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dtp.activity.listener-enabled=false")
@Import(ActivityProjectionIT.Topics.class)
@Testcontainers
class ActivityProjectionIT {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo@sha256:d0d926f94df099bff534b7ee5b5986458131a22489dfff8664509af0c1e2ca9c");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9");
    static final TestIssuer ISSUER = new TestIssuer();
    static final String TOPIC = "activity-it-" + UUID.randomUUID();
    static final String GROUP = "activity-it-" + UUID.randomUUID();
    @TestConfiguration static class Topics { @Bean NewTopic events() { return new NewTopic(TOPIC, 3, (short) 1); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::url);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER.url() + "/jwks");
        r.add("dtp.activity.prefix", () -> "it_v1"); r.add("dtp.activity.group", () -> GROUP);
        r.add("dtp.kafka.events-topic", () -> TOPIC); r.add("dtp.kafka.dlt-topic", () -> TOPIC + ".dlt");
    }
    @AfterAll static void closeIssuer() { ISSUER.close(); }
    @LocalServerPort int port;
    @Autowired MongoTemplate mongo;
    @Autowired ProjectActivity projector;
    @Autowired TransferEventDecoder decoder;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper json;
    @MockitoSpyBean MongoSnapshots snapshots;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    record Fixture(UUID tenant, UUID id) { String key() { return tenant + ":" + id; } }
    @BeforeEach void clearOnlyOwnedDisposableCollections() {
        listeners.getListenerContainer("activity").stop();
        mongo.getCollection("it_v1_events").deleteMany(new Document()); mongo.getCollection("it_v1_snapshots").deleteMany(new Document());
    }

    @Test void terminalBeforeAdmissionNeverRegressesAndCompletesHistory() {
        var f = fixture(); var terminal = event(f, "TransferPosted"); var accepted = event(f, "TransferAccepted");
        projector.execute(terminal, f.key()); projector.execute(accepted, f.key()); projector.execute(terminal, f.key());
        assertThat(snapshot(f).getString("status")).isEqualTo("POSTED");
        assertThat(snapshot(f).getLong("aggregateVersion")).isEqualTo(2);
        assertThat(count("it_v1_events", f)).isEqualTo(2); assertThat(count("it_v1_snapshots", f)).isEqualTo(1);
    }

    @Test void failureBetweenHistoryAndSnapshotHealsOnDuplicateRedelivery() {
        var f = fixture(); byte[] accepted = event(f, "TransferAccepted");
        org.mockito.Mockito.doThrow(new IllegalStateException("injected between writes")).when(snapshots).advance(any());
        try { assertThatThrownBy(() -> projector.execute(accepted, f.key())).isInstanceOf(IllegalStateException.class); }
        finally { org.mockito.Mockito.doCallRealMethod().when(snapshots).advance(any()); }
        assertThat(count("it_v1_events", f)).isEqualTo(1); assertThat(count("it_v1_snapshots", f)).isZero();
        projector.execute(accepted, f.key());
        assertThat(count("it_v1_events", f)).isEqualTo(1); assertThat(snapshot(f).getString("status")).isEqualTo("ACCEPTED");
    }

    @Test void concurrentMixedVersionsConvergeToOneTerminalSnapshot() throws Exception {
        var f = fixture(); byte[] accepted = event(f, "TransferAccepted"); byte[] terminal = event(f, "TransferPosted");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) { byte[] bytes = i % 2 == 0 ? accepted : terminal; futures.add(executor.submit(() -> { start.await(); projector.execute(bytes, f.key()); return null; })); }
            start.countDown(); for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("it_v1_events", f)).isEqualTo(2); assertThat(count("it_v1_snapshots", f)).isEqualTo(1);
        assertThat(snapshot(f).getString("status")).isEqualTo("POSTED");
    }

    @Test void reusedEventIdentityWithChangedAmountCannotOverwriteProjection() {
        var f = fixture(); var accepted = event(f, "TransferAccepted"); projector.execute(accepted, f.key());
        var node = json.readTree(accepted);
        ((tools.jackson.databind.node.ObjectNode) node.get("payload").get("amount")).put("cents", 999);
        assertThatThrownBy(() -> projector.execute(json.writeValueAsBytes(node), f.key())).isInstanceOf(InvalidContract.class);
        assertThat(snapshot(f).getLong("amountCents")).isEqualTo(100); assertThat(count("it_v1_events", f)).isEqualTo(1);
    }

    @Test void httpReadsRequireAudienceScopeTenantAndOwnerAndNeverWrite() throws Exception {
        var f = fixture(); projector.execute(event(f, "TransferPosted"), f.key());
        var valid = token(f.tenant(), "alice", "dtp-activity", "activity:read");
        var response = get("/v1/activity/transfers/" + f.id(), valid);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(response.body()).get("status").stringValue()).isEqualTo("POSTED");
        saveSample("activity-receipt.json", response.body());
        assertThat(get("/v1/activity/transfers/" + f.id(), token(f.tenant(), "bob", "dtp-activity", "activity:read")).statusCode()).isEqualTo(404);
        assertThat(get("/v1/activity/transfers/" + f.id(), token(UUID.randomUUID(), "alice", "dtp-activity", "activity:read")).statusCode()).isEqualTo(404);
        assertThat(get("/v1/activity/transfers/" + f.id(), token(f.tenant(), "alice", "dtp-ledger", "activity:read")).statusCode()).isEqualTo(401);
        assertThat(get("/v1/activity/transfers/" + f.id(), token(f.tenant(), "alice", "dtp-activity", "transfer:write")).statusCode()).isEqualTo(403);
        assertThat(get("/v1/activity/transfers/" + f.id(), null).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/activity/transfers"))
                .header("Authorization", "Bearer " + valid).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test void cursorPaginationIsStableBoundedAndTenantScoped() throws Exception {
        UUID tenant = UUID.randomUUID(); var token = token(tenant, "alice", "dtp-activity", "activity:read");
        for (int i = 0; i < 5; i++) { var f = new Fixture(tenant, UUID.randomUUID()); projector.execute(event(f, "TransferAccepted"), f.key()); }
        var foreign = fixture(); projector.execute(event(foreign, "TransferAccepted"), foreign.key());
        var ids = new java.util.HashSet<String>(); String cursor = null;
        do {
            var response = get("/v1/activity/transfers?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), token);
            assertThat(response.statusCode()).isEqualTo(200); var page = json.readTree(response.body());
            saveSample("activity-page.json", response.body());
            for (var item : page.get("items")) assertThat(ids.add(item.get("id").stringValue())).isTrue();
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").stringValue();
        } while (cursor != null);
        assertThat(ids).hasSize(5); assertThat(ids).doesNotContain(foreign.id().toString());
        assertThat(get("/v1/activity/transfers?limit=101", token).statusCode()).isEqualTo(400);
        assertThat(get("/v1/activity/transfers?cursor=bad", token).statusCode()).isEqualTo(400);
    }

    @Test void realKafkaProjectorAndIndependentGroupBothReceiveEveryFact() throws Exception {
        var f = fixture(); listeners.getListenerContainer("activity").start();
        kafka.send(TOPIC, f.key(), event(f, "TransferAccepted")).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, f.key(), event(f, "TransferPosted")).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var projection = snapshot(f); assertThat(projection).isNotNull(); assertThat(projection.getString("status")).isEqualTo("POSTED");
        });
        var props = consumerProperties("independent-" + UUID.randomUUID());
        try (var observer = new KafkaConsumer<String, byte[]>(props)) {
            observer.subscribe(List.of(TOPIC)); int matching = 0; long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (matching < 2 && System.nanoTime() < deadline) for (var record : observer.poll(Duration.ofMillis(250))) if (f.key().equals(record.key())) matching++;
            assertThat(matching).isEqualTo(2); observer.commitSync();
        }
        assertThat(count("it_v1_events", f)).isEqualTo(2);
    }

    @Test void replayIntoNewCollectionsAndNewGroupRebuildsSameBusinessReceipt() throws Exception {
        var f = fixture(); listeners.getListenerContainer("activity").start();
        kafka.send(TOPIC, f.key(), event(f, "TransferPosted")).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, f.key(), event(f, "TransferAccepted")).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("it_v1_events", f)).isEqualTo(2));
        String prefix = "rebuild_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var rebuilt = new ProjectActivity(decoder, new MongoHistory(mongo, decoder, prefix), new MongoSnapshots(mongo, prefix));
        var properties = new ContainerProperties(TOPIC); properties.setGroupId("activity-rebuild-" + UUID.randomUUID()); properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener((MessageListener<String, byte[]>) record -> rebuilt.execute(record.value(), record.key()));
        var container = new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<String, byte[]>(consumerProperties(properties.getGroupId())), properties);
        try {
            container.start();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var rebuiltDocument = mongo.getCollection(prefix + "_snapshots").find(new Document("_id", f.key())).first();
                assertThat(rebuiltDocument).isNotNull(); assertThat(rebuiltDocument.getString("status")).isEqualTo("POSTED");
                assertThat(mongo.getCollection(prefix + "_events").countDocuments(new Document("transferId", f.id().toString()))).isEqualTo(2);
            });
            var live = snapshot(f); var copy = mongo.getCollection(prefix + "_snapshots").find(new Document("_id", f.key())).first();
            live.remove("projectionUpdatedAt"); copy.remove("projectionUpdatedAt"); assertThat(copy).isEqualTo(live);
        } finally { container.stop(); }
    }

    @Test void healthIsPublicButBroaderActuatorIsProtected() throws Exception {
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(401);
    }

    @Test void historyUsesSeparateCollectionAndObjectAuthorization() throws Exception {
        var f = fixture(); projector.execute(event(f, "TransferAccepted"), f.key()); projector.execute(event(f, "TransferPosted"), f.key());
        var response = get("/v1/activity/transfers/" + f.id() + "/events", token(f.tenant(), "alice", "dtp-activity", "activity:read"));
        assertThat(response.statusCode()).isEqualTo(200); var history = json.readTree(response.body());
        assertThat(history.size()).isEqualTo(2); assertThat(history.get(0).get("aggregateVersion").longValue()).isEqualTo(1);
        assertThat(history.get(1).get("aggregateVersion").longValue()).isEqualTo(2);
        saveSample("activity-history.json", response.body());
        assertThat(get("/v1/activity/transfers/" + f.id() + "/events", token(f.tenant(), "bob", "dtp-activity", "activity:read")).statusCode()).isEqualTo(404);
        assertThat(get("/v1/activity/transfers/" + f.id() + "/events", token(UUID.randomUUID(), "alice", "dtp-activity", "activity:read")).statusCode()).isEqualTo(404);
    }

    @Test void unavailableMongoReturnsControlledReadErrorWithoutFinancialFallback() throws Exception {
        var f = fixture(); var bearer = token(f.tenant(), "alice", "dtp-activity", "activity:read");
        projector.execute(event(f, "TransferAccepted"), f.key());
        org.testcontainers.DockerClientFactory.instance().client().pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            var response = get("/v1/activity/transfers/" + f.id(), bearer);
            assertThat(response.statusCode()).isEqualTo(503); assertThat(response.body()).contains("Projection storage unavailable");
        } finally { org.testcontainers.DockerClientFactory.instance().client().unpauseContainerCmd(MONGO.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(snapshot(f)).isNotNull());
    }

    @Test void auditorReadsTenantReceiptPageAndHistoryWithoutCrossTenantAccess() throws Exception {
        var f = fixture(); projector.execute(event(f, "TransferAccepted"), f.key()); projector.execute(event(f, "TransferPosted"), f.key());
        String audit = ISSUER.token(f.tenant(), "audit-user", "test-client", "dtp-activity", "activity:read", List.of("auditor"));
        assertThat(get("/v1/activity/transfers/" + f.id(), audit).statusCode()).isEqualTo(200);
        var events = get("/v1/activity/transfers/" + f.id() + "/events", audit);
        assertThat(events.statusCode()).isEqualTo(200); assertThat(json.readTree(events.body()).size()).isEqualTo(2);
        var page = get("/v1/activity/transfers", audit); assertThat(page.statusCode()).isEqualTo(200);
        assertThat(json.readTree(page.body()).path("items").size()).isEqualTo(1);
        String other = ISSUER.token(UUID.randomUUID(), "audit-user", "test-client", "dtp-activity", "activity:read", List.of("auditor"));
        assertThat(get("/v1/activity/transfers/" + f.id(), other).statusCode()).isEqualTo(404);
        assertThat(get("/v1/activity/transfers/" + f.id() + "/events", other).statusCode()).isEqualTo(404);
        assertThat(json.readTree(get("/v1/activity/transfers", other).body()).path("items").size()).isZero();
        String admin = ISSUER.token(f.tenant(), "admin-user", "test-client", "dtp-activity", "activity:read", List.of("admin"));
        assertThat(get("/v1/activity/transfers/" + f.id(), admin).statusCode()).isEqualTo(403);
    }
    private static Fixture fixture() { return new Fixture(UUID.randomUUID(), UUID.randomUUID()); }
    private static void saveSample(String name, String body) throws java.io.IOException {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "artifacts", name), body);
    }
    private Document snapshot(Fixture f) { return mongo.getCollection("it_v1_snapshots").find(new Document("_id", f.key())).first(); }
    private long count(String collection, Fixture f) { return mongo.getCollection(collection).countDocuments(new Document("tenantId", f.tenant().toString()).append("transferId", f.id().toString())); }
    private String token(UUID tenant, String subject, String audience, String scope) throws Exception { return ISSUER.token(tenant, subject, "test-client", audience, scope); }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Map<String, Object> consumerProperties(String group) {
        return Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", group, "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", ByteArrayDeserializer.class);
    }
    private byte[] event(Fixture f, String type) {
        boolean accepted = type.equals("TransferAccepted");
        var payload = new LinkedHashMap<String, Object>(); payload.put("id", f.id()); payload.put("tenantId", f.tenant()); payload.put("initiatedBy", "alice");
        payload.put("sourceAccountId", "20000000-0000-0000-0000-000000000001"); payload.put("destinationAccountId", "20000000-0000-0000-0000-000000000002");
        payload.put("amount", Map.of("cents", 100, "currency", "BRL")); payload.put("acceptedAt", "2026-10-11T10:00:00Z"); payload.put("ruleVersion", "synthetic-v1");
        if (!accepted) { payload.put("status", "POSTED"); payload.put("terminalAt", "2026-10-11T10:00:01Z"); payload.put("rejectionReason", null); }
        var event = new LinkedHashMap<String, Object>(); event.put("eventId", UUID.nameUUIDFromBytes((f.id() + type).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        event.put("tenantId", f.tenant()); event.put("aggregateId", f.id()); event.put("schemaVersion", 1); event.put("aggregateVersion", accepted ? 1 : 2);
        event.put("eventType", type); event.put("occurredAt", accepted ? "2026-10-11T10:00:00Z" : "2026-10-11T10:00:01Z"); event.put("payload", payload); event.put("correlationId", "projection-test");
        return json.writeValueAsBytes(event);
    }
}
