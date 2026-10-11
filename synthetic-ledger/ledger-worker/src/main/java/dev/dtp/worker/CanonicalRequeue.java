package dev.dtp.worker;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Operator repair uses the original canonical identity; never resets financial consumer offsets. */
@Component
public class CanonicalRequeue {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;
    public CanonicalRequeue(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, @Value("${dtp.kafka.events-topic}") String topic) {
        this.jdbc = jdbc; this.kafka = kafka; this.topic = topic;
    }
    public String execute(UUID tenant, UUID transfer, String actor, String reason) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Requeue must run outside financial transactions.");
        }
        if (actor == null || !actor.matches("[A-Za-z0-9_.@-]{1,128}") || reason == null || reason.isBlank() || reason.length() > 256
                || reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Bounded operator and reason required.");
        var rows = jdbc.query("""
                SELECT t.status,e.payload::text FROM transfers t JOIN canonical_events e ON e.aggregate_id=t.id AND e.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.id=? AND e.aggregate_version=1 AND e.event_type='TransferAccepted'
                """, (rs, i) -> java.util.Map.entry(rs.getString(1), rs.getString(2)), tenant, transfer);
        if (rows.size() != 1) throw new dev.dtp.application.InvalidEvent();
        var attempt = UUID.randomUUID(); var canonical = rows.getFirst();
        if (!"ACCEPTED".equals(canonical.getKey())) {
            audit(attempt, tenant, transfer, actor, reason, "REQUEUE_ALREADY_TERMINAL"); return "ALREADY_TERMINAL";
        }
        audit(attempt, tenant, transfer, actor, reason, "REQUEUE_REQUESTED");
        // Commit audit before external effect. A crash leaves REQUESTED as an explicit uncertain attempt.
        try {
            kafka.send(topic, tenant + ":" + transfer, canonical.getValue().getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); audit(attempt, tenant, transfer, actor, reason, "REQUEUE_UNCONFIRMED");
            throw new IllegalStateException("Requeue confirmation interrupted.");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) {
            audit(attempt, tenant, transfer, actor, reason, "REQUEUE_UNCONFIRMED");
            throw new IllegalStateException("Requeue was not confirmed.");
        }
        audit(attempt, tenant, transfer, actor, reason, "REQUEUE_CONFIRMED"); return "CONFIRMED";
    }
    private void audit(UUID attempt, UUID tenant, UUID transfer, String actor, String reason, String action) {
        jdbc.update("INSERT INTO operational_audit(id,attempt_id,tenant_id,transfer_id,actor,reason,action) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), attempt, tenant, transfer, actor, reason, action);
    }
}
