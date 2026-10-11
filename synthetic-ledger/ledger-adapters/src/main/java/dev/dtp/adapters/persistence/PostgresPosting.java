package dev.dtp.adapters.persistence;

import dev.dtp.application.InvalidEvent;
import dev.dtp.application.PostingRepository;
import dev.dtp.application.TransferState;
import dev.dtp.domain.Money;
import dev.dtp.domain.PostingPolicy;
import dev.dtp.domain.Transfer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Shares the same datasource/transaction manager as admission; no broker calls. */
@Repository
public class PostgresPosting implements PostingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final dev.dtp.application.TraceContextSource trace;
    public PostgresPosting(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; this.trace = () -> null; }
    @org.springframework.beans.factory.annotation.Autowired
    public PostgresPosting(JdbcTemplate jdbc, ObjectMapper json, org.springframework.beans.factory.ObjectProvider<dev.dtp.application.TraceContextSource> trace) {
        this.jdbc = jdbc; this.json = json; this.trace = trace.getIfAvailable(() -> () -> null);
    }

    @Override public Locked lockCanonical(Request request) {
        var rows = jdbc.query("""
                SELECT t.*,e.correlation_id,e.traceparent FROM transfers t
                JOIN canonical_events e ON e.aggregate_id=t.id AND e.tenant_id=t.tenant_id
                WHERE t.id=? AND t.tenant_id=? AND e.event_id=? AND e.event_type='TransferAccepted'
                 AND e.aggregate_version=1 AND e.schema_version=1 FOR UPDATE OF t
                """, (rs, index) -> new Locked(state(rs), rs.getString("correlation_id"), rs.getString("traceparent")),
                request.transferId(), request.tenantId(), request.eventId());
        if (rows.size() != 1) throw new InvalidEvent();
        return rows.getFirst();
    }

    @Override public Accounts lockAccounts(Locked locked) {
        var t = locked.state().transfer();
        var rows = jdbc.query("""
                SELECT id,active FROM accounts WHERE tenant_id=? AND kind='CUSTOMER' AND id IN (?,?) ORDER BY id FOR UPDATE
                """, (rs, index) -> Map.entry(rs.getObject("id", UUID.class), rs.getBoolean("active")),
                t.tenantId(), t.sourceAccountId(), t.destinationAccountId());
        if (rows.size() != 2) throw new IllegalStateException("Canonical transfer accounts missing.");
        boolean source = rows.stream().filter(r -> r.getKey().equals(t.sourceAccountId())).findFirst().orElseThrow().getValue();
        boolean destination = rows.stream().filter(r -> r.getKey().equals(t.destinationAccountId())).findFirst().orElseThrow().getValue();
        return new Accounts(source, destination);
    }

    @Override public void complete(Request request, Locked locked, PostingPolicy.Decision decision, Instant at) {
        var t = locked.state().transfer(); var timestamp = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        if ("POSTED".equals(decision.status())) {
            var journal = UUID.randomUUID();
            jdbc.update("INSERT INTO journals(id,tenant_id,purpose,currency,created_at,transfer_id) VALUES (?,?,'TRANSFER_POSTING','BRL',?,?)",
                    journal, t.tenantId(), timestamp, t.id());
            jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,?),(?,?,?,?,?)",
                    UUID.randomUUID(), t.tenantId(), journal, t.sourceAccountId(), -t.amount().cents(),
                    UUID.randomUUID(), t.tenantId(), journal, t.destinationAccountId(), t.amount().cents());
            requireOne(jdbc.update("UPDATE accounts SET balance_cents=balance_cents-?,reserved_cents=reserved_cents-?,version=version+1 WHERE id=? AND tenant_id=? AND reserved_cents>=?",
                    t.amount().cents(), t.amount().cents(), t.sourceAccountId(), t.tenantId(), t.amount().cents()));
            requireOne(jdbc.update("UPDATE accounts SET balance_cents=balance_cents+?,version=version+1 WHERE id=? AND tenant_id=?",
                    t.amount().cents(), t.destinationAccountId(), t.tenantId()));
        } else {
            requireOne(jdbc.update("UPDATE accounts SET reserved_cents=reserved_cents-?,version=version+1 WHERE id=? AND tenant_id=? AND reserved_cents>=?",
                    t.amount().cents(), t.sourceAccountId(), t.tenantId(), t.amount().cents()));
        }
        requireOne(jdbc.update("DELETE FROM holds WHERE transfer_id=? AND tenant_id=? AND amount_cents=? AND account_id=?",
                t.id(), t.tenantId(), t.amount().cents(), t.sourceAccountId()));
        requireOne(jdbc.update("UPDATE transfers SET status=?,aggregate_version=2,terminal_at=?,rejection_reason=? WHERE id=? AND tenant_id=? AND status='ACCEPTED'",
                decision.status(), timestamp, decision.reason(), t.id(), t.tenantId()));
        jdbc.update("INSERT INTO processed_events(event_id,tenant_id,transfer_id,processed_at) VALUES (?,?,?,?)",
                request.eventId(), t.tenantId(), t.id(), timestamp);
        appendTerminal(locked, decision, at);
    }

    /** Separate method provides a meaningful rollback fault boundary for integration tests. */
    public void appendTerminal(Locked locked, PostingPolicy.Decision decision, Instant at) {
        var t = locked.state().transfer(); var eventId = UUID.randomUUID();
        var eventType = "POSTED".equals(decision.status()) ? "TransferPosted" : "TransferRejected";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", t.id()); payload.put("tenantId", t.tenantId()); payload.put("initiatedBy", t.initiatedBy());
        payload.put("sourceAccountId", t.sourceAccountId()); payload.put("destinationAccountId", t.destinationAccountId());
        payload.put("amount", t.amount()); payload.put("acceptedAt", t.acceptedAt()); payload.put("ruleVersion", t.ruleVersion());
        payload.put("status", decision.status()); payload.put("terminalAt", at); payload.put("rejectionReason", decision.reason());
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", eventId); envelope.put("eventType", eventType); envelope.put("schemaVersion", 1);
        envelope.put("tenantId", t.tenantId()); envelope.put("aggregateId", t.id()); envelope.put("aggregateVersion", 2);
        envelope.put("occurredAt", at); envelope.put("payload", payload);
        String parent = locked.traceparent();
        try {
            String current = trace.currentTraceparent();
            if (current != null && current.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) parent = current;
        } catch (RuntimeException unavailable) { /* Telemetry never changes the financial outcome. */ }
        envelope.put("correlationId", locked.correlationId()); envelope.put("traceparent", parent);
        var timestamp = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO canonical_events(event_id,tenant_id,aggregate_id,aggregate_version,event_type,schema_version,occurred_at,payload,correlation_id,traceparent)
                VALUES (?,?,?,2,?,1,?,CAST(? AS jsonb),?,?)
                """, eventId, t.tenantId(), t.id(), eventType, timestamp, json.writeValueAsString(envelope), locked.correlationId(), parent);
        jdbc.update("INSERT INTO outbox_deliveries(event_id,created_at,next_attempt_at) VALUES (?,?,?)", eventId, timestamp, timestamp);
    }

    public static TransferState state(ResultSet rs) throws SQLException {
        var transfer = new Transfer(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("initiated_by"),
                rs.getObject("source_account_id", UUID.class), rs.getObject("destination_account_id", UUID.class),
                new Money(rs.getLong("amount_cents"), rs.getString("currency")), rs.getTimestamp("accepted_at").toInstant(), rs.getString("rule_version"));
        var terminal = rs.getTimestamp("terminal_at");
        return new TransferState(transfer, rs.getString("status"), rs.getLong("aggregate_version"), terminal == null ? null : terminal.toInstant(), rs.getString("rejection_reason"));
    }
    private static void requireOne(int affected) { if (affected != 1) throw new IllegalStateException("Canonical posting state inconsistent."); }
}
