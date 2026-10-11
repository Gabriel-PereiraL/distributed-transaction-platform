package dev.dtp.worker;

import dev.dtp.adapters.persistence.PostgresOutbox;
import dev.dtp.application.InvalidEvent;
import dev.dtp.application.PostingRepository;
import dev.dtp.application.ProcessTransfer;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Transport bytes never determine balances; the canonical SQL admission does. */
@Component
public class KafkaRecordProcessor {
    public static final int MAX_EVENT_BYTES = 65_536;
    private final PostgresOutbox outbox;
    private final ProcessTransfer process;
    private final ObjectMapper json;
    private final KafkaTracing tracing;
    public KafkaRecordProcessor(PostgresOutbox outbox, ProcessTransfer process, ObjectMapper json, KafkaTracing tracing) {
        this.outbox = outbox; this.process = process; this.json = json;
        this.tracing = tracing;
    }
    public void handle(ConsumerRecord<String, byte[]> record) {
        var bytes = record.value();
        if (bytes == null || bytes.length > MAX_EVENT_BYTES || record.key() == null) throw new InvalidEvent();
        final UUID eventId; final UUID tenantId; final UUID transferId; final String type;
        final tools.jackson.databind.JsonNode node;
        try {
            node = json.readTree(bytes);
            eventId = UUID.fromString(node.get("eventId").stringValue());
            tenantId = UUID.fromString(node.get("tenantId").stringValue());
            transferId = UUID.fromString(node.get("aggregateId").stringValue());
            type = node.get("eventType").stringValue();
            if (node.get("schemaVersion").intValue() != 1 || !Set.of("TransferAccepted", "TransferPosted", "TransferRejected").contains(type)
                    || !record.key().equals(tenantId + ":" + transferId)) throw new InvalidEvent();
        } catch (RuntimeException invalid) { throw new InvalidEvent(); }
        // DB failures propagate as retryable infrastructure failures, not poison messages.
        var canonical = outbox.canonical(eventId, tenantId, transferId).orElseThrow(InvalidEvent::new);
        if (!node.equals(json.readTree(canonical))) throw new InvalidEvent();
        try (var span = tracing.start("dtp.posting.consume", io.opentelemetry.api.trace.SpanKind.CONSUMER, KafkaTracing.parent(record.headers(), node.path("traceparent").stringValue()))) {
            try { if ("TransferAccepted".equals(type)) process.execute(new PostingRepository.Request(eventId, tenantId, transferId)); }
            catch (RuntimeException failure) { span.failed(failure); throw failure; }
        }
    }
}
