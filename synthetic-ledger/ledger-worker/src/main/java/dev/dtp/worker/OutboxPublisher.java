package dev.dtp.worker;

import dev.dtp.adapters.persistence.PostgresOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private final PostgresOutbox outbox;
    private final KafkaTemplate<String, byte[]> kafka;
    private final MeterRegistry metrics;
    private final String topic;
    private final boolean enabled;
    private final KafkaTracing tracing;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    public OutboxPublisher(PostgresOutbox outbox, KafkaTemplate<String, byte[]> kafka, MeterRegistry metrics,
                           @Value("${dtp.kafka.events-topic}") String topic, @Value("${dtp.publisher.enabled:true}") boolean enabled) {
        this(outbox, kafka, metrics, topic, enabled, new KafkaTracing(io.opentelemetry.api.OpenTelemetry.noop()));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public OutboxPublisher(PostgresOutbox outbox, KafkaTemplate<String, byte[]> kafka, MeterRegistry metrics,
                           @Value("${dtp.kafka.events-topic}") String topic, @Value("${dtp.publisher.enabled:true}") boolean enabled, KafkaTracing tracing) {
        this.outbox = outbox; this.kafka = kafka; this.metrics = metrics; this.topic = topic; this.enabled = enabled;
        this.tracing = tracing;
    }
    @Scheduled(fixedDelayString = "${dtp.publisher.delay-ms:500}") public void tick() { if (enabled) publishOne(); }
    public boolean publishOne() {
        var claimed = outbox.claim();
        if (claimed.isEmpty()) return false;
        var delivery = claimed.orElseThrow();
        if (!outbox.stillOwned(delivery)) { metrics.counter("dtp.outbox.stale_claim").increment(); return false; }
        try {
            var node = json.readTree(delivery.payload());
            try (var span = tracing.start("dtp.outbox.publish", io.opentelemetry.api.trace.SpanKind.PRODUCER, node.path("traceparent").stringValue())) {
                var record = new org.apache.kafka.clients.producer.ProducerRecord<String, byte[]>(topic, delivery.key(), delivery.payload().getBytes(StandardCharsets.UTF_8));
                span.inject(record.headers());
                try { kafka.send(record).get(10, TimeUnit.SECONDS); }
                catch (InterruptedException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) { span.failed(failure); throw failure; }
                tracing.published();
            }
            if (outbox.published(delivery)) metrics.counter("dtp.outbox.published").increment();
            else metrics.counter("dtp.outbox.stale_claim").increment();
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException unconfirmed) {
            int base = 1 << Math.min(delivery.attempts(), 6);
            outbox.retry(delivery, base + ThreadLocalRandom.current().nextInt(base + 1));
            metrics.counter("dtp.outbox.unconfirmed").increment();
            return false;
        }
    }
}
