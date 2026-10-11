package dev.dtp.adapters.persistence;

import dev.dtp.application.PostingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short claim/finish transactions. Fencing prevents stale owners from changing delivery state. */
@Repository
public class PostgresOutbox {
    public record Delivery(UUID eventId, UUID tenantId, UUID transferId, long version, String payload, UUID token, int attempts) {
        public String key() { return tenantId + ":" + transferId; }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PostgresOutbox(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }
    public Optional<Delivery> claim() {
        return tx.execute(s -> {
            var token = UUID.randomUUID();
            var rows = jdbc.query("""
                    WITH candidate AS (
                     SELECT o.event_id FROM outbox_deliveries o JOIN canonical_events e USING(event_id)
                     WHERE o.status<>'PUBLISHED' AND o.next_attempt_at<=clock_timestamp()
                      AND (o.status='PENDING' OR o.lease_until<=clock_timestamp())
                      AND NOT EXISTS(SELECT 1 FROM canonical_events prior JOIN outbox_deliveries po USING(event_id)
                        WHERE prior.tenant_id=e.tenant_id AND prior.aggregate_id=e.aggregate_id
                         AND prior.aggregate_version<e.aggregate_version AND po.status<>'PUBLISHED')
                     ORDER BY o.next_attempt_at,o.event_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                    ), claimed AS (
                     UPDATE outbox_deliveries o SET status='CLAIMED',claim_token=?,lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1
                     FROM candidate c WHERE o.event_id=c.event_id RETURNING o.*
                    ) SELECT c.event_id,e.tenant_id,e.aggregate_id,e.aggregate_version,e.payload::text,c.claim_token,c.attempts
                     FROM claimed c JOIN canonical_events e USING(event_id)
                    """, (rs, i) -> new Delivery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                    rs.getLong(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getInt(7)), token);
            return rows.stream().findFirst();
        });
    }
    public boolean stillOwned(Delivery d) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM outbox_deliveries WHERE event_id=? AND claim_token=? AND status='CLAIMED' AND lease_until>clock_timestamp())",
                Boolean.class, d.eventId(), d.token()));
    }
    public boolean published(Delivery d) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("""
                UPDATE outbox_deliveries SET status='PUBLISHED',published_at=clock_timestamp(),claim_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND claim_token=? AND status='CLAIMED' AND lease_until>clock_timestamp()
                """, d.eventId(), d.token()) == 1));
    }
    public boolean retry(Delivery d, int delaySeconds) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("""
                UPDATE outbox_deliveries SET status='PENDING',claim_token=NULL,lease_until=NULL,last_error='BROKER_UNCONFIRMED',
                 next_attempt_at=clock_timestamp()+(? * interval '1 second')
                WHERE event_id=? AND claim_token=? AND status='CLAIMED' AND lease_until>clock_timestamp()
                """, delaySeconds, d.eventId(), d.token()) == 1));
    }
    public Optional<String> canonical(UUID eventId, UUID tenantId, UUID transferId) {
        return jdbc.query("SELECT payload::text FROM canonical_events WHERE event_id=? AND tenant_id=? AND aggregate_id=?",
                (rs, i) -> rs.getString(1), eventId, tenantId, transferId).stream().findFirst();
    }
    public List<PostingRepository.Request> overdue(int ageSeconds, int limit) {
        return jdbc.query("""
                SELECT e.event_id,t.tenant_id,t.id FROM transfers t JOIN canonical_events e ON e.aggregate_id=t.id AND e.tenant_id=t.tenant_id AND e.aggregate_version=1
                WHERE t.status='ACCEPTED' AND t.accepted_at<clock_timestamp()-(? * interval '1 second') ORDER BY t.accepted_at,t.id LIMIT ?
                """, (rs, i) -> new PostingRepository.Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), ageSeconds, limit);
    }
}
