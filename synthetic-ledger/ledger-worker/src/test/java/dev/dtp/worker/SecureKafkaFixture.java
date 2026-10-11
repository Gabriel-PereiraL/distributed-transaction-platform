package dev.dtp.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

/** Ephemeral local TLS material, never committed or included in release archives. */
final class SecureKafkaFixture {
    final String password = UUID.randomUUID().toString();
    final java.util.Map<String, String> identities = java.util.Map.of("broker", UUID.randomUUID().toString(), "admin", UUID.randomUUID().toString(),
            "worker", UUID.randomUUID().toString(), "activity", UUID.randomUUID().toString());
    final Path truststore;
    final KafkaContainer container;
    SecureKafkaFixture() {
        try {
            Path directory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "kafka-tls-");
            Path keystore = directory.resolve("server.p12"); truststore = directory.resolve("trust.p12");
            Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
            var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "broker", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-dname", "CN=dtp-kafka", "-ext", "SAN=dns:localhost,dns:dtp-kafka,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", keystore.toString(), "-storepass:env", "DTP_TEST_TLS_PASSWORD", "-keypass:env", "DTP_TEST_TLS_PASSWORD", "-noprompt");
            process.environment().put("DTP_TEST_TLS_PASSWORD", password);
            process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            var child = process.start();
            if (!child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("keytool timeout"); }
            if (child.exitValue() != 0) throw new IllegalStateException("keytool failed");
            var server = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keystore)) { server.load(input, password.toCharArray()); }
            var trust = KeyStore.getInstance("PKCS12"); trust.load(null, password.toCharArray()); trust.setCertificateEntry("broker", server.getCertificate("broker"));
            try (var output = Files.newOutputStream(truststore)) { trust.store(output, password.toCharArray()); }
            String jaas = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"broker\" password=\"" + identities.get("broker") + "\""
                    + " user_broker=\"" + identities.get("broker") + "\" user_admin=\"" + identities.get("admin")
                    + "\" user_worker=\"" + identities.get("worker") + "\" user_activity=\"" + identities.get("activity") + "\";";
            container = new KafkaContainer("apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9")
                    .withCreateContainerCmdModifier(command -> command.withHostName("dtp-kafka"))
                    .withCopyFileToContainer(MountableFile.forHostPath(keystore), "/tmp/server.p12")
                    .withCopyFileToContainer(MountableFile.forHostPath(truststore), "/tmp/trust.p12")
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:SASL_SSL,BROKER:SASL_SSL,CONTROLLER:SSL")
                    .withEnv("KAFKA_SSL_KEYSTORE_LOCATION", "/tmp/server.p12").withEnv("KAFKA_SSL_KEYSTORE_TYPE", "PKCS12")
                    .withEnv("KAFKA_SSL_KEYSTORE_PASSWORD", password).withEnv("KAFKA_SSL_KEY_PASSWORD", password)
                    .withEnv("KAFKA_SSL_TRUSTSTORE_LOCATION", "/tmp/trust.p12").withEnv("KAFKA_SSL_TRUSTSTORE_TYPE", "PKCS12")
                    .withEnv("KAFKA_SSL_TRUSTSTORE_PASSWORD", password).withEnv("KAFKA_LISTENER_NAME_CONTROLLER_SSL_CLIENT_AUTH", "required")
                    .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN").withEnv("KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL", "PLAIN")
                    .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_PLAIN_SASL_JAAS_CONFIG", jaas).withEnv("KAFKA_LISTENER_NAME_BROKER_PLAIN_SASL_JAAS_CONFIG", jaas)
                    .withEnv("KAFKA_AUTHORIZER_CLASS_NAME", "org.apache.kafka.metadata.authorizer.StandardAuthorizer")
                    .withEnv("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND", "false").withEnv("KAFKA_SUPER_USERS", "User:admin;User:broker;User:CN=dtp-kafka")
                    .withStartupTimeout(Duration.ofMinutes(2));
        } catch (Exception failure) { throw new IllegalStateException("Local Kafka TLS fixture failed.", failure); }
    }
    java.util.Map<String, Object> properties(String user) {
        var result = new java.util.HashMap<String, Object>();
        result.put("bootstrap.servers", container.getBootstrapServers()); result.put("security.protocol", "SASL_SSL"); result.put("sasl.mechanism", "PLAIN");
        result.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + user + "\" password=\"" + identities.get(user) + "\";");
        result.put("ssl.truststore.location", truststore.toString()); result.put("ssl.truststore.password", password); result.put("ssl.truststore.type", "PKCS12");
        result.put("ssl.endpoint.identification.algorithm", "https"); result.put("request.timeout.ms", 3000); result.put("default.api.timeout.ms", 6000);
        return result;
    }
}
