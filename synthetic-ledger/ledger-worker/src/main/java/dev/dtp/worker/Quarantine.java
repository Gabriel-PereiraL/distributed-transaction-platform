package dev.dtp.worker;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Fresh bounded envelope; no untrusted headers or exception text is copied. */
@Component
public class Quarantine {
    private final KafkaTemplate<String, byte[]> kafka;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final String topic;
    public Quarantine(KafkaTemplate<String, byte[]> kafka, ObjectMapper json, MeterRegistry metrics,
                      @Value("${dtp.kafka.dlt-topic}") String topic) {
        this.kafka = kafka; this.json = json; this.metrics = metrics; this.topic = topic;
    }
    public void recover(ConsumerRecord<?, ?> record, Exception error) {
        byte[] bytes = record.value() instanceof byte[] value ? value : new byte[0];
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("schemaVersion", 1); envelope.put("sourceTopic", record.topic());
        envelope.put("sourcePartition", record.partition()); envelope.put("sourceOffset", record.offset());
        envelope.put("reason", errorHasInvalidEvent(error) ? "INVALID_EVENT" : "RETRIES_EXHAUSTED");
        envelope.put("sha256", sha256(bytes)); envelope.put("originalBytes", bytes.length);
        envelope.put("bytesBase64", Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, Math.min(bytes.length, KafkaRecordProcessor.MAX_EVENT_BYTES))));
        envelope.put("truncated", bytes.length > KafkaRecordProcessor.MAX_EVENT_BYTES);
        var key = record.topic() + ":" + record.partition() + ":" + record.offset();
        try {
            // Returning is permitted only after broker confirmation; failure leaves original offset uncommitted.
            kafka.send(topic, key, json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
            metrics.counter("dtp.quarantine.confirmed").increment();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Quarantine confirmation interrupted.", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) {
            metrics.counter("dtp.quarantine.unconfirmed").increment();
            throw new IllegalStateException("Quarantine was not confirmed.");
        }
    }
    private static boolean errorHasInvalidEvent(Throwable error) {
        for (var next = error; next != null; next = next.getCause()) if (next instanceof dev.dtp.application.InvalidEvent) return true;
        return false;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable.", impossible); }
    }
}
