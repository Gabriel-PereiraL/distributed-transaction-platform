package dev.dtp.adapters.persistence;

import dev.dtp.application.Actor;
import dev.dtp.application.AccessDenied;
import dev.dtp.application.DeliveryBusy;
import dev.dtp.application.RequeueRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class PostgresRequeue implements RequeueRepository {
    private final JdbcTemplate jdbc;
    public PostgresRequeue(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Result request(Actor actor, UUID transfer, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Requeue requires a SQL transaction.");
        var states = jdbc.query("""
                SELECT t.status,e.event_id FROM transfers t JOIN canonical_events e
                  ON e.tenant_id=t.tenant_id AND e.aggregate_id=t.id AND e.aggregate_version=1 AND e.event_type='TransferAccepted'
                WHERE t.tenant_id=? AND t.id=? FOR UPDATE OF t
                """, (rs, index) -> new Result(transfer, rs.getObject("event_id", UUID.class), rs.getString("status")), actor.tenantId(), transfer);
        if (states.size() != 1) throw new AccessDenied();
        var state = states.getFirst();
        if (!"ACCEPTED".equals(state.outcome())) {
            audit(actor, transfer, reason, "REQUEUE_ALREADY_TERMINAL");
            return new Result(transfer, state.eventId(), "ALREADY_TERMINAL");
        }
        Boolean busy = jdbc.queryForObject("SELECT status='CLAIMED' AND lease_until>clock_timestamp() FROM outbox_deliveries WHERE event_id=? FOR UPDATE", Boolean.class, state.eventId());
        if (Boolean.TRUE.equals(busy)) throw new DeliveryBusy();
        int updated = jdbc.update("""
                UPDATE outbox_deliveries SET status='PENDING',next_attempt_at=clock_timestamp(),claim_token=NULL,lease_until=NULL,published_at=NULL,last_error=NULL WHERE event_id=?
                """, state.eventId());
        if (updated != 1) throw new IllegalStateException("Missing canonical delivery.");
        audit(actor, transfer, reason, "REQUEUE_REQUESTED");
        return new Result(transfer, state.eventId(), "QUEUED");
    }
    private void audit(Actor actor, UUID transfer, String reason, String action) {
        jdbc.update("INSERT INTO operational_audit(id,attempt_id,tenant_id,transfer_id,actor,reason,action) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), UUID.randomUUID(), actor.tenantId(), transfer, actor.subject(), reason, action);
    }
}
