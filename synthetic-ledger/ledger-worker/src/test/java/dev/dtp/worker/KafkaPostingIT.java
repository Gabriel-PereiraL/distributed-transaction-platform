package dev.dtp.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dtp.adapters.persistence.PostgresOutbox;
import dev.dtp.application.Actor;
import dev.dtp.application.AdmitTransfer;
import dev.dtp.application.InvalidEvent;
import dev.dtp.application.PostingRepository;
import dev.dtp.application.ProcessTransfer;
import dev.dtp.application.TransferCommand;
import dev.dtp.domain.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LedgerWorker.class, properties = {"dtp.publisher.enabled=false", "dtp.consumer.enabled=false", "dtp.recovery.enabled=false"})
@Testcontainers
class KafkaPostingIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9");
    static final String TOPIC = "dtp-it-" + UUID.randomUUID();
    static final String DLT = TOPIC + ".dlt";
    static final String GROUP = "posting-it-" + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.flyway.enabled", () -> true);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("dtp.kafka.events-topic", () -> TOPIC); r.add("dtp.kafka.dlt-topic", () -> DLT); r.add("dtp.kafka.posting-group", () -> GROUP);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AdmitTransfer admit;
    @Autowired ProcessTransfer process;
    @Autowired PostgresOutbox outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired KafkaRecordProcessor processor;
    @MockitoSpyBean Quarantine quarantine;
    @MockitoSpyBean dev.dtp.adapters.persistence.PostgresPosting posting;
    @Autowired PendingRecovery recovery;
    @Autowired CanonicalRequeue requeue;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired MeterRegistry metrics;
    @Autowired ObjectMapper json;
    record Fixture(UUID tenant, UUID source, UUID destination) { }

    @BeforeEach void clearOnlyDisposableTestDatabase() {
        listeners.getListenerContainer("posting").stop();
        // This datasource is supplied exclusively by our fresh Testcontainer above.
        jdbc.execute("TRUNCATE accounts,transfers,admission_keys,holds,canonical_events,outbox_deliveries,journals,journal_entries,processed_events,operational_audit CASCADE");
    }

    @Test void realBrokerPublishesAndConsumerCommitsAfterAtomicPosting() throws Exception {
        var f = fixture(1000); var id = admission(f, 250, "pipeline");
        listeners.getListenerContainer("posting").start();
        assertThat(publisher.publishOne()).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id)).isEqualTo("POSTED"));
        assertThat(publisher.publishOne()).isTrue();
        var records = read(TOPIC, id, 2);
        assertThat(records).hasSize(2);
        assertThat(records.stream().map(r -> json.readTree(r.value()).get("aggregateVersion").longValue()).toList()).containsExactly(1L, 2L);
        assertThat(records).allMatch(r -> r.key().equals(f.tenant() + ":" + id));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committedBeyond(records.getLast())).isTrue());
        assertBalances(f, 750, 250, 0);
        assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_deliveries WHERE status='PUBLISHED'")).isEqualTo(2);
    }

    @Test void lostPublisherConfirmationCanDuplicateButCannotDoublePost() throws Exception {
        var f = fixture(1000); var id = admission(f, 100, "lost-confirmation"); var original = outbox.claim().orElseThrow();
        kafka.send(TOPIC, original.key(), original.payload().getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
        // Simulates death after broker accepted bytes, before delivery state is saved.
        jdbc.update("UPDATE outbox_deliveries SET lease_until=now()-interval '1 second' WHERE event_id=?", original.eventId());
        listeners.getListenerContainer("posting").start();
        assertThat(publisher.publishOne()).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id)).isEqualTo("POSTED"));
        var records = read(TOPIC, id, 2);
        assertThat(records).hasSize(2);
        assertThat(records.stream().map(r -> json.readTree(r.value()).get("eventId").stringValue()).distinct().count()).isEqualTo(1);
        assertBalances(f, 900, 100, 0);
        assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", id)).isEqualTo(1);
        assertThat(outbox.published(original)).isFalse();
    }

    @Test void commitBeforeOffsetSurvivesConsumerRedelivery() throws Exception {
        var f = fixture(1000); var id = admission(f, 100, "lost-offset"); var accepted = outbox.claim().orElseThrow();
        var record = new ConsumerRecord<String, byte[]>(TOPIC, 0, 0, accepted.key(), accepted.payload().getBytes(StandardCharsets.UTF_8));
        processor.handle(record); // committed, but no Kafka offset has been acknowledged
        processor.handle(record);
        listeners.getListenerContainer("posting").start();
        kafka.send(TOPIC, record.key(), record.value()).get(10, TimeUnit.SECONDS);
        var delivered = read(TOPIC, id, 1).getFirst();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(committedBeyond(delivered)).isTrue());
        assertBalances(f, 900, 100, 0);
        assertThat(count("SELECT count(*) FROM processed_events WHERE transfer_id=?", id)).isEqualTo(1);
    }

    @Test void expiredOwnerCannotFinishOrReleaseReplacementClaim() {
        var f = fixture(1000); admission(f, 100, "fencing"); var old = outbox.claim().orElseThrow();
        assertThat(outbox.claim()).isEmpty();
        jdbc.update("UPDATE outbox_deliveries SET lease_until=now()-interval '1 second' WHERE event_id=?", old.eventId());
        var replacement = outbox.claim().orElseThrow();
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(replacement.attempts()).isEqualTo(2);
        assertThat(outbox.stillOwned(old)).isFalse(); assertThat(outbox.published(old)).isFalse(); assertThat(outbox.retry(old, 1)).isFalse();
        assertThat(outbox.stillOwned(replacement)).isTrue(); assertThat(outbox.published(replacement)).isTrue();
    }

    @Test void laterAggregateVersionWaitsForEarlierDeliveryConfirmation() {
        var f = fixture(1000); var id = admission(f, 100, "ordering"); var accepted = outbox.claim().orElseThrow();
        process.execute(new PostingRepository.Request(accepted.eventId(), f.tenant(), id));
        assertThat(outbox.claim()).isEmpty();
        assertThat(outbox.published(accepted)).isTrue();
        assertThat(outbox.claim().orElseThrow().version()).isEqualTo(2);
    }

    @Test void concurrentPublishersClaimEachEventOnlyOnce() throws Exception {
        var f = fixture(10000);
        for (int i = 0; i < 16; i++) admission(f, 100, "claim-" + i);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<PostgresOutbox.Delivery>>();
            for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> { start.await(); return outbox.claim().orElseThrow(); }));
            start.countDown(); var deliveries = new ArrayList<PostgresOutbox.Delivery>();
            for (var future : futures) deliveries.add(future.get(20, TimeUnit.SECONDS));
            assertThat(deliveries.stream().map(PostgresOutbox.Delivery::eventId).distinct().count()).isEqualTo(16);
        }
    }

    @Test void malformedRecordIsQuarantinedBeforeOffsetCanAdvance() throws Exception {
        listeners.getListenerContainer("posting").start();
        byte[] invalid = "{invalid-json-secret-text".getBytes(StandardCharsets.UTF_8);
        var sent = kafka.send(TOPIC, "invalid", invalid).get(10, TimeUnit.SECONDS).getRecordMetadata();
        var records = readByKey(DLT, TOPIC + ":" + sent.partition() + ":" + sent.offset(), 1); assertThat(records).hasSize(1);
        var dlt = json.readTree(records.getFirst().value());
        assertThat(dlt.get("sourceOffset").longValue()).isEqualTo(sent.offset());
        assertThat(dlt.get("sourcePartition").intValue()).isEqualTo(sent.partition());
        assertThat(dlt.get("reason").stringValue()).isEqualTo("INVALID_EVENT");
        assertThat(dlt.get("originalBytes").intValue()).isEqualTo(invalid.length);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        java.nio.file.Files.write(java.nio.file.Path.of("target", "artifacts", "quarantine-event.json"), records.getFirst().value());
        var source = new ConsumerRecord<String, byte[]>(TOPIC, sent.partition(), sent.offset(), "invalid", invalid);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committedBeyond(source)).isTrue());
        assertThat(count("SELECT count(*) FROM journals")).isZero();
    }

    @Test void unknownSchemaTamperedPayloadAndWrongKeyCannotChangeBalances() {
        var f = fixture(1000); var id = admission(f, 100, "tamper"); var accepted = outbox.claim().orElseThrow();
        var altered = accepted.payload().replace("\"schemaVersion\": 1", "\"schemaVersion\": 9");
        assertThat(altered).isNotEqualTo(accepted.payload());
        assertThatThrownBy(() -> processor.handle(new ConsumerRecord<>(TOPIC, 0, 0, accepted.key(), altered.getBytes(StandardCharsets.UTF_8)))).isInstanceOf(InvalidEvent.class);
        var node = json.readTree(accepted.payload()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node.get("payload").get("amount")).put("cents", 999);
        assertThatThrownBy(() -> processor.handle(new ConsumerRecord<>(TOPIC, 0, 0, accepted.key(), json.writeValueAsBytes(node)))).isInstanceOf(InvalidEvent.class);
        assertThatThrownBy(() -> processor.handle(new ConsumerRecord<>(TOPIC, 0, 0, "wrong-key", accepted.payload().getBytes(StandardCharsets.UTF_8)))).isInstanceOf(InvalidEvent.class);
        assertThat(status(id)).isEqualTo("ACCEPTED"); assertBalances(f, 1000, 0, 100);
    }

    @Test void quarantineBoundsBytesAndDoesNotCopyUntrustedHeaders() throws Exception {
        byte[] huge = new byte[100000]; java.util.Arrays.fill(huge, (byte) 'x');
        var record = new ConsumerRecord<String, byte[]>(TOPIC, 2, 999, "untrusted", huge);
        record.headers().add("Authorization", "secret-token".getBytes(StandardCharsets.UTF_8));
        quarantine.recover(record, new InvalidEvent());
        var dlt = json.readTree(readByKey(DLT, TOPIC + ":2:999", 1).getFirst().value());
        assertThat(dlt.get("truncated").booleanValue()).isTrue();
        assertThat(java.util.Base64.getDecoder().decode(dlt.get("bytesBase64").stringValue())).hasSize(KafkaRecordProcessor.MAX_EVENT_BYTES);
        assertThat(dlt.toString()).doesNotContain("secret-token", "Authorization", "stackTrace");
    }

    @SuppressWarnings("try") // The root scope supplies context without being referenced.
    @Test void realBrokerCarriesTraceFromAdmissionThroughPostingAndTerminalOutbox() throws Exception {
        var exporter = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try (var provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn())
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry = io.opentelemetry.sdk.OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var tracing = new KafkaTracing(telemetry);
            var tracedPublisher = new OutboxPublisher(outbox, kafka, metrics, TOPIC, false, tracing);
            var tracedProcessor = new KafkaRecordProcessor(outbox, process, json, tracing);
            var f = fixture(1000);
            var root = telemetry.getTracer("test").spanBuilder("admission").startSpan();
            String parent;
            try (var scope = root.makeCurrent()) { parent = KafkaTracing.currentTraceparent(); }
            root.end();
            var id = admit.execute(new Actor(f.tenant(), "alice", "test-client"), "traced-pipeline",
                    new TransferCommand(f.source(), f.destination(), new Money(100, "BRL")), "trace-test", parent)
                    .admission().transferId();
            assertThat(tracedPublisher.publishOne()).isTrue();
            var accepted = read(TOPIC, id, 1).getFirst();
            var acceptedHeader = KafkaTracing.parent(accepted.headers(), null);
            assertThat(acceptedHeader).isNotNull();
            tracedProcessor.handle(accepted);
            assertThat(status(id)).isEqualTo("POSTED");
            var terminalParent = jdbc.queryForObject("SELECT traceparent FROM canonical_events WHERE aggregate_id=? AND aggregate_version=2", String.class, id);
            assertThat(terminalParent).isNotNull();
            assertThat(terminalParent.substring(3, 35)).isEqualTo(root.getSpanContext().getTraceId());
            assertThat(tracedPublisher.publishOne()).isTrue();
            var terminal = read(TOPIC, id, 2).stream().filter(r -> json.readTree(r.value()).get("aggregateVersion").longValue() == 2).findFirst().orElseThrow();
            var spans = exporter.getFinishedSpanItems();
            var producer = spans.stream().filter(s -> s.getName().equals("dtp.outbox.publish") && s.getParentSpanId().equals(root.getSpanContext().getSpanId())).findFirst().orElseThrow();
            var consumer = spans.stream().filter(s -> s.getName().equals("dtp.posting.consume")).findFirst().orElseThrow();
            assertThat(consumer.getParentSpanId()).isEqualTo(producer.getSpanId());
            assertThat(terminalParent.substring(36, 52)).isEqualTo(consumer.getSpanId());
            var terminalProducer = spans.stream().filter(s -> s.getName().equals("dtp.outbox.publish") && s.getParentSpanId().equals(consumer.getSpanId())).findFirst().orElseThrow();
            assertThat(KafkaTracing.parent(terminal.headers(), null).substring(36, 52)).isEqualTo(terminalProducer.getSpanId());
            assertThat(spans).allMatch(s -> s.getTraceId().equals(root.getSpanContext().getTraceId()));
            assertThat(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid()).isFalse();
            assertBalances(f, 900, 100, 0);
        }
    }

    @Test void unavailableBrokerLeavesDurableOutboxRetryableAndNoSqlLockDuringSend() throws Exception {
        var f = fixture(1000); var id = admission(f, 100, "broker-down");
        var props = Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        var factory = new DefaultKafkaProducerFactory<String, byte[]>(props);
        try {
            var sending = new CountDownLatch(1);
            var releaseSend = new CountDownLatch(1);
            var disconnectedKafka = new KafkaTemplate<String, byte[]>(factory) {
                @Override public java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, byte[]>> send(
                        org.apache.kafka.clients.producer.ProducerRecord<String, byte[]> record) {
                    sending.countDown();
                    try {
                        if (!releaseSend.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test send barrier timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test send barrier interrupted", interrupted);
                    }
                    return super.send(record);
                }
            };
            var disconnected = new OutboxPublisher(outbox, disconnectedKafka, metrics, TOPIC, false);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = executor.submit(disconnected::publishOne);
                try {
                    assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(jdbc.queryForObject("SELECT status FROM outbox_deliveries", String.class)).isEqualTo("CLAIMED");
                    new TransactionTemplate(transactions).executeWithoutResult(s ->
                            assertThat(jdbc.queryForList("SELECT event_id FROM outbox_deliveries FOR UPDATE NOWAIT")).hasSize(1));
                } finally { releaseSend.countDown(); }
                assertThat(pending.get(10, TimeUnit.SECONDS)).isFalse();
            }
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_deliveries", String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_deliveries", String.class)).isEqualTo("BROKER_UNCONFIRMED");
            assertThat(status(id)).isEqualTo("ACCEPTED"); assertBalances(f, 1000, 0, 100);
        } finally { factory.destroy(); }
        jdbc.update("UPDATE outbox_deliveries SET next_attempt_at=now()");
        assertThat(publisher.publishOne()).isTrue();
    }

    @Test void unavailableDltThrowsInsteadOfSilentlyDiscardingOriginal() {
        var factory = new DefaultKafkaProducerFactory<String, byte[]>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500));
        try {
            var unavailable = new Quarantine(new KafkaTemplate<>(factory), json, metrics, DLT);
            assertThatThrownBy(() -> unavailable.recover(new ConsumerRecord<>(TOPIC, 0, 9999, "bad", new byte[0]), new InvalidEvent()))
                    .isInstanceOf(RuntimeException.class);
        } finally { factory.destroy(); }
    }

    @Test void recoveryUsesSameIdempotentPostingAndPreservesCanonicalDeliveryOrder() {
        var f = fixture(1000); var id = admission(f, 100, "recovery");
        assertThat(recovery.recover(0)).isEqualTo(1);
        assertThat(recovery.recover(0)).isZero();
        assertBalances(f, 900, 100, 0); assertThat(status(id)).isEqualTo("POSTED");
        assertThat(outbox.claim().orElseThrow().version()).isEqualTo(1);
    }

    @Test void failedQuarantineDoesNotCommitOriginalOffsetAndResumesAfterRecovery() throws Exception {
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> { failures.incrementAndGet(); throw new IllegalStateException("injected unavailable DLT"); })
                .when(quarantine).recover(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        listeners.getListenerContainer("posting").start();
        var metadata = kafka.send(TOPIC, "poison-dlt-unavailable", "{invalid".getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS).getRecordMetadata();
        var record = new ConsumerRecord<String, byte[]>(TOPIC, metadata.partition(), metadata.offset(), "poison-dlt-unavailable", new byte[0]);
        try {
            await().atMost(Duration.ofSeconds(20)).until(() -> failures.get() >= 2);
            assertThat(committedBeyond(record)).isFalse();
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(quarantine).recover(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
        assertThat(readByKey(DLT, TOPIC + ":" + metadata.partition() + ":" + metadata.offset(), 1)).hasSize(1);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committedBeyond(record)).isTrue());
    }

    @Test void transientSqlFailureRetriesBeforeQuarantineAndRetainsFinancialAtomicity() throws Exception {
        var f = fixture(1000); var id = admission(f, 100, "transient-sql"); var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) throw new org.springframework.dao.TransientDataAccessResourceException("injected transient SQL outage");
            return invocation.callRealMethod();
        }).when(posting).appendTerminal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        listeners.getListenerContainer("posting").start(); assertThat(publisher.publishOne()).isTrue();
        try {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id)).isEqualTo("POSTED"));
            assertThat(calls.get()).isEqualTo(3); assertBalances(f, 900, 100, 0);
            assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", id)).isEqualTo(1);
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(posting).appendTerminal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Test void operatorRequeuePreservesIdentityAndAuditsIntentOutcomeAndTerminalNoop() throws Exception {
        var f = fixture(1000); var id = admission(f, 100, "operator-requeue");
        var original = jdbc.queryForObject("SELECT event_id FROM canonical_events WHERE aggregate_id=?", UUID.class, id);
        assertThat(requeue.execute(f.tenant(), id, "operator.local", "broker recovered")).isEqualTo("CONFIRMED");
        assertThat(requeue.execute(f.tenant(), id, "operator.local", "repeat uncertain attempt")).isEqualTo("CONFIRMED");
        var records = read(TOPIC, id, 2); assertThat(records).hasSize(2);
        assertThat(records).allMatch(r -> json.readTree(r.value()).get("eventId").stringValue().equals(original.toString()));
        assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=?", id)).isEqualTo(4);
        assertBalances(f, 1000, 0, 100);
        listeners.getListenerContainer("posting").start();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id)).isEqualTo("POSTED"));
        assertThat(requeue.execute(f.tenant(), id, "operator.local", "verify terminal result")).isEqualTo("ALREADY_TERMINAL");
        assertThat(count("SELECT count(*) FROM operational_audit WHERE transfer_id=?", id)).isEqualTo(5);
        assertBalances(f, 900, 100, 0);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM operational_audit WHERE transfer_id=?", id)).isInstanceOf(RuntimeException.class);
    }

    @Test void requeueFailureLeavesAuditedUncertaintyAndExistingFinancialHold() {
        var f = fixture(1000); var id = admission(f, 100, "operator-unconfirmed");
        var factory = new DefaultKafkaProducerFactory<String, byte[]>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500));
        try {
            var disconnected = new CanonicalRequeue(jdbc, new KafkaTemplate<>(factory), TOPIC);
            assertThatThrownBy(() -> disconnected.execute(f.tenant(), id, "operator.local", "outage drill")).isInstanceOf(IllegalStateException.class);
        } finally { factory.destroy(); }
        assertThat(jdbc.queryForList("SELECT action FROM operational_audit WHERE transfer_id=? ORDER BY occurred_at", String.class, id))
                .containsExactly("REQUEUE_REQUESTED", "REQUEUE_UNCONFIRMED");
        assertBalances(f, 1000, 0, 100); assertThat(status(id)).isEqualTo("ACCEPTED");
    }

    @Test void requeueValidatesTenantReasonAndTransactionBoundaryBeforeSending() {
        var f = fixture(1000); var id = admission(f, 100, "operator-validation");
        assertThatThrownBy(() -> requeue.execute(UUID.randomUUID(), id, "operator.local", "wrong tenant")).isInstanceOf(InvalidEvent.class);
        assertThatThrownBy(() -> requeue.execute(f.tenant(), id, "operator.local", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> requeue.execute(f.tenant(), id, "operator.local", "inside SQL")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM operational_audit")).isZero();
    }

    @Test void realPublisherProcessDeathRecoversAfterNaturalLeaseExpiry() throws Exception {
        var f = fixture(1000); var id = admission(f, 100, "process-death");
        var javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var builder = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), LeaseCrashProbe.class.getName());
        builder.environment().put("PROBE_JDBC_URL", POSTGRES.getJdbcUrl()); builder.environment().put("PROBE_JDBC_USER", POSTGRES.getUsername());
        builder.environment().put("PROBE_JDBC_PASSWORD", POSTGRES.getPassword());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "artifacts"));
        builder.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("target", "artifacts", "lease-crash-probe.log").toFile());
        var child = builder.start();
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(child.isAlive()).isTrue();
                assertThat(jdbc.queryForObject("SELECT status FROM outbox_deliveries", String.class)).isEqualTo("CLAIMED");
            });
            child.destroyForcibly(); assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(outbox.claim()).isEmpty();
            // No test edit of lease_until: actual database-clock timeout recovers the dead owner.
            await().atMost(Duration.ofSeconds(40)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until<=clock_timestamp() FROM outbox_deliveries", Boolean.class)));
            listeners.getListenerContainer("posting").start(); assertThat(publisher.publishOne()).isTrue();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id)).isEqualTo("POSTED"));
            assertBalances(f, 900, 100, 0); assertThat(count("SELECT count(*) FROM journals WHERE transfer_id=?", id)).isEqualTo(1);
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); } }
    }

    private Fixture fixture(long balance) {
        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); var technical = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'alice','CUSTOMER','BRL',?)", f.source(), f.tenant(), balance);
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'bob','CUSTOMER','BRL',0)", f.destination(), f.tenant());
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'system','TECHNICAL','BRL',?)", technical, f.tenant(), -balance);
            var journal = UUID.randomUUID();
            jdbc.update("INSERT INTO journals(id,tenant_id,purpose,currency,created_at) VALUES (?,?,'SYNTHETIC_FUNDING','BRL',now())", journal, f.tenant());
            jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,?),(?,?,?,?,?)",
                    UUID.randomUUID(), f.tenant(), journal, f.source(), balance, UUID.randomUUID(), f.tenant(), journal, technical, -balance);
        });
        return f;
    }
    private UUID admission(Fixture f, long cents, String key) {
        return admit.execute(new Actor(f.tenant(), "alice", "test-client"), key,
                new TransferCommand(f.source(), f.destination(), new Money(cents, "BRL")), "kafka-test", null).admission().transferId();
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private String status(UUID id) { return jdbc.queryForObject("SELECT status FROM transfers WHERE id=?", String.class, id); }
    private void assertBalances(Fixture f, long source, long destination, long reserved) {
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.source())).isEqualTo(source);
        assertThat(count("SELECT balance_cents FROM accounts WHERE id=?", f.destination())).isEqualTo(destination);
        assertThat(count("SELECT reserved_cents FROM accounts WHERE id=?", f.source())).isEqualTo(reserved);
    }
    private List<ConsumerRecord<String, byte[]>> read(String topic, UUID id, int expected) {
        return readMatching(topic, r -> id == null || r.key().endsWith(":" + id), expected);
    }
    private List<ConsumerRecord<String, byte[]>> readByKey(String topic, String key, int expected) {
        return readMatching(topic, r -> key.equals(r.key()), expected);
    }
    private List<ConsumerRecord<String, byte[]>> readMatching(String topic, java.util.function.Predicate<ConsumerRecord<String, byte[]>> matches, int expected) {
        var props = new Properties(); props.put("bootstrap.servers", KAFKA.getBootstrapServers()); props.put("group.id", "observer-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest"); props.put("enable.auto.commit", "false");
        try (var consumer = new KafkaConsumer<String, byte[]>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic)); var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (matches.test(record)) records.add(record);
                }
            }
            return records;
        }
    }
    private boolean committedBeyond(ConsumerRecord<String, byte[]> record) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()));
            return offset != null && offset.offset() > record.offset();
        }
    }
}
