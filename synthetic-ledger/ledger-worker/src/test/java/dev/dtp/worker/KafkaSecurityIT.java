package dev.dtp.worker;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KafkaSecurityIT {
    private static final SecureKafkaFixture FIXTURE = new SecureKafkaFixture();
    @Container static final KafkaContainer KAFKA = FIXTURE.container;
    private static final String EVENTS = "dtp-sec-events", LEDGER_DLT = "dtp-sec-ledger-dlt", ACTIVITY_DLT = "dtp-sec-activity-dlt";
    @BeforeAll static void provision() throws Exception {
        try (var admin = Admin.create(FIXTURE.properties("admin"))) {
            admin.createTopics(List.of(new NewTopic(EVENTS, 1, (short) 1), new NewTopic(LEDGER_DLT, 1, (short) 1), new NewTopic(ACTIVITY_DLT, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            admin.createAcls(List.of(
                    acl("worker", ResourceType.TOPIC, EVENTS, AclOperation.WRITE, PatternType.LITERAL),
                    acl("worker", ResourceType.TOPIC, EVENTS, AclOperation.READ, PatternType.LITERAL),
                    acl("worker", ResourceType.TOPIC, LEDGER_DLT, AclOperation.WRITE, PatternType.LITERAL),
                    acl("worker", ResourceType.GROUP, "posting-sec", AclOperation.READ, PatternType.LITERAL),
                    acl("worker", ResourceType.CLUSTER, "kafka-cluster", AclOperation.IDEMPOTENT_WRITE, PatternType.LITERAL),
                    acl("activity", ResourceType.TOPIC, EVENTS, AclOperation.READ, PatternType.LITERAL),
                    acl("activity", ResourceType.TOPIC, ACTIVITY_DLT, AclOperation.WRITE, PatternType.LITERAL),
                    acl("activity", ResourceType.GROUP, "activity-", AclOperation.READ, PatternType.PREFIXED),
                    acl("activity", ResourceType.CLUSTER, "kafka-cluster", AclOperation.IDEMPOTENT_WRITE, PatternType.LITERAL))).all().get(10, TimeUnit.SECONDS);
        }
    }
    @Test void tlsHostnameVerifiedWorkerPublishesAndActivityConsumesOwnGroup() throws Exception {
        var trust = java.security.KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(FIXTURE.truststore)) { trust.load(input, FIXTURE.password.toCharArray()); }
        var managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var ssl = javax.net.ssl.SSLContext.getInstance("TLSv1.3"); ssl.init(null, managers.getTrustManagers(), null);
        try (var socket = (javax.net.ssl.SSLSocket) ssl.getSocketFactory().createSocket(KAFKA.getHost(), KAFKA.getMappedPort(9092))) {
            socket.setSoTimeout(5000); var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
            socket.startHandshake(); assertEquals("CN=dtp-kafka", socket.getSession().getPeerPrincipal().getName());
        }
        try (var producer = producer("worker"); var consumer = consumer("activity", "activity-sec-" + UUID.randomUUID())) {
            producer.send(new ProducerRecord<>(EVENTS, "synthetic", "event")).get(10, TimeUnit.SECONDS);
            consumer.subscribe(List.of(EVENTS));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos(); boolean found = false;
            while (System.nanoTime() < deadline && !found) {
                for (var record : consumer.poll(Duration.ofMillis(200))) if ("event".equals(record.value())) found = true;
            }
            assertTrue(found); consumer.commitSync();
        }
    }
    @Test void activityCannotPublishFinancialFactsOrConsumePostingGroup() throws Exception {
        try (var producer = producer("activity")) {
            var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> producer.send(new ProducerRecord<>(EVENTS, "synthetic", "forbidden")).get(10, TimeUnit.SECONDS));
            assertInstanceOf(org.apache.kafka.common.errors.TopicAuthorizationException.class, denied.getCause());
        }
        try (var consumer = consumer("activity", "posting-sec")) {
            consumer.subscribe(List.of(EVENTS));
            assertThrows(org.apache.kafka.common.errors.GroupAuthorizationException.class, () -> consumer.poll(Duration.ofSeconds(6)));
        }
    }
    @Test void distinctSecretsCannotImpersonateWorkerAndActivityDltIsIsolated() throws Exception {
        var wrong = FIXTURE.properties("worker");
        wrong.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"worker\" password=\"" + FIXTURE.identities.get("activity") + "\";");
        try (var admin = Admin.create(wrong)) {
            var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> admin.listTopics().names().get(10, TimeUnit.SECONDS));
            assertInstanceOf(org.apache.kafka.common.errors.SaslAuthenticationException.class, denied.getCause());
        }
        try (var producer = producer("activity")) {
            producer.send(new ProducerRecord<>(ACTIVITY_DLT, "synthetic", "quarantine")).get(10, TimeUnit.SECONDS);
            var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> producer.send(new ProducerRecord<>(LEDGER_DLT, "synthetic", "forbidden")).get(10, TimeUnit.SECONDS));
            assertInstanceOf(org.apache.kafka.common.errors.TopicAuthorizationException.class, denied.getCause());
        }
    }
    @Test void springSecureProfileBindsProducerAndConsumerTlsWithoutTopicCreation() throws Exception {
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(KafkaOnly.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("secure").run(
                    "--DTP_KAFKA_BOOTSTRAP_SERVERS=" + KAFKA.getBootstrapServers(), "--DTP_KAFKA_WORKER_PASSWORD=" + FIXTURE.identities.get("worker"),
                    "--DTP_KAFKA_TRUSTSTORE=" + FIXTURE.truststore, "--DTP_KAFKA_TRUSTSTORE_PASSWORD=" + FIXTURE.password)) {
            var properties = context.getBean(org.springframework.boot.kafka.autoconfigure.KafkaProperties.class);
            assertEquals("SASL_SSL", properties.buildProducerProperties().get("security.protocol"));
            assertEquals("SASL_SSL", properties.buildConsumerProperties().get("security.protocol"));
            assertEquals("https", properties.buildProducerProperties().get("ssl.endpoint.identification.algorithm"));
            assertEquals("false", context.getEnvironment().getProperty("dtp.kafka.create-topics"));
            var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, byte[]>(properties.buildProducerProperties());
            try {
                var template = new org.springframework.kafka.core.KafkaTemplate<String, byte[]>(factory);
                template.send(EVENTS, "spring-profile", new byte[] {1}).get(10, TimeUnit.SECONDS);
            } finally { factory.destroy(); }
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class)
    static class KafkaOnly { }
    private static AclBinding acl(String user, ResourceType resource, String name, AclOperation operation, PatternType pattern) {
        return new AclBinding(new ResourcePattern(resource, name, pattern), new AccessControlEntry("User:" + user, "*", operation, AclPermissionType.ALLOW));
    }
    private static KafkaProducer<String, String> producer(String user) {
        var properties = FIXTURE.properties(user); properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer"); properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        properties.put("acks", "all"); properties.put("enable.idempotence", true); properties.put("max.block.ms", 6000); properties.put("delivery.timeout.ms", 6000);
        return new KafkaProducer<>(properties);
    }
    private static KafkaConsumer<String, String> consumer(String user, String group) {
        var properties = FIXTURE.properties(user); properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"); properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("group.id", group); properties.put("auto.offset.reset", "earliest"); properties.put("enable.auto.commit", false);
        return new KafkaConsumer<>(properties);
    }
}
