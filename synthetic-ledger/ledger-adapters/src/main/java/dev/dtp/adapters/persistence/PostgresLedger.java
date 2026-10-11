package dev.dtp.adapters.persistence;

import dev.dtp.application.AcceptedEvent;
import dev.dtp.application.AccountRepository;
import dev.dtp.application.Actor;
import dev.dtp.application.Admission;
import dev.dtp.application.EventRepository;
import dev.dtp.application.IdempotencyMismatch;
import dev.dtp.application.TransferRepository;
import dev.dtp.domain.Account;
import dev.dtp.domain.Money;
import dev.dtp.domain.Transfer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresLedger implements AccountRepository, TransferRepository, EventRepository {
    private final EntityManager em;
    private final ObjectMapper json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PostgresLedger(EntityManager em, ObjectMapper json, org.springframework.jdbc.core.JdbcTemplate jdbc) { this.em = em; this.json = json; this.jdbc = jdbc; }

    @Override public Optional<dev.dtp.application.TransferState> findState(Actor actor, UUID id) {
        return jdbc.query("SELECT * FROM transfers WHERE id=? AND tenant_id=? AND (initiated_by=? OR ?)",
                (rs, index) -> PostgresPosting.state(rs), id, actor.tenantId(), actor.subject(), actor.canAudit()).stream().findFirst();
    }

    @Override
    public List<Account> lockPair(UUID tenant, UUID source, UUID destination) {
        return em.createQuery("select a from AccountEntity a where a.tenantId=:tenant and a.kind='CUSTOMER' and a.id in :ids order by a.id", AccountEntity.class)
                .setParameter("tenant", tenant).setParameter("ids", List.of(source, destination))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().map(AccountEntity::snapshot).toList();
    }

    @Override public void saveReservation(Account account) { em.find(AccountEntity.class, account.id()).reserveTo(account.reservedCents()); }

    @Override
    public Optional<Account> findOwned(Actor actor, UUID id) {
        return em.createQuery("select a from AccountEntity a where a.id=:id and a.tenantId=:tenant and a.owner=:owner and a.kind='CUSTOMER'", AccountEntity.class)
                .setParameter("id", id).setParameter("tenant", actor.tenantId()).setParameter("owner", actor.subject())
                .getResultStream().findFirst().map(AccountEntity::snapshot);
    }

    @Override public Optional<Account> findVisible(Actor actor, UUID id) {
        return em.createQuery("select a from AccountEntity a where a.id=:id and a.tenantId=:tenant and (a.owner=:owner or :audit=true) and a.kind='CUSTOMER'", AccountEntity.class)
                .setParameter("id", id).setParameter("tenant", actor.tenantId()).setParameter("owner", actor.subject()).setParameter("audit", actor.canAudit())
                .getResultStream().findFirst().map(AccountEntity::snapshot);
    }

    @Override
    public Optional<Admission> claim(Actor actor, String key, String hash, Admission proposed) {
        int inserted = em.createNativeQuery("""
                INSERT INTO admission_keys(tenant_id,subject,client_id,operation,key,request_hash,transfer_id,accepted_at)
                VALUES (:tenant,:subject,:client,'POST /v1/transfers',:key,:hash,:id,:at)
                ON CONFLICT (tenant_id,subject,client_id,operation,key) DO NOTHING
                """).setParameter("tenant", actor.tenantId()).setParameter("subject", actor.subject())
                .setParameter("client", actor.clientId()).setParameter("key", key).setParameter("hash", hash)
                .setParameter("id", proposed.transferId()).setParameter("at", proposed.acceptedAt()).executeUpdate();
        if (inserted == 1) return Optional.empty();
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT request_hash,transfer_id,accepted_at FROM admission_keys
                WHERE tenant_id=:tenant AND subject=:subject AND client_id=:client AND operation='POST /v1/transfers' AND key=:key
                """).setParameter("tenant", actor.tenantId()).setParameter("subject", actor.subject())
                .setParameter("client", actor.clientId()).setParameter("key", key).getSingleResult();
        if (!hash.equals(row[0].toString())) throw new IdempotencyMismatch();
        return Optional.of(Admission.accepted((UUID) row[1], instant(row[2])));
    }

    @Override
    public void insertWithHold(Transfer t) {
        em.createNativeQuery("""
                INSERT INTO transfers(id,tenant_id,initiated_by,source_account_id,destination_account_id,amount_cents,currency,status,accepted_at,rule_version,aggregate_version)
                VALUES (:id,:tenant,:actor,:source,:destination,:amount,:currency,'ACCEPTED',:at,:rule,1)
                """).setParameter("id", t.id()).setParameter("tenant", t.tenantId()).setParameter("actor", t.initiatedBy())
                .setParameter("source", t.sourceAccountId()).setParameter("destination", t.destinationAccountId())
                .setParameter("amount", t.amount().cents()).setParameter("currency", t.amount().currency())
                .setParameter("at", t.acceptedAt()).setParameter("rule", t.ruleVersion()).executeUpdate();
        em.createNativeQuery("INSERT INTO holds(transfer_id,tenant_id,account_id,amount_cents) VALUES (:id,:tenant,:source,:amount)")
                .setParameter("id", t.id()).setParameter("tenant", t.tenantId()).setParameter("source", t.sourceAccountId())
                .setParameter("amount", t.amount().cents()).executeUpdate();
    }

    @Override
    public Optional<Transfer> findInitiated(Actor actor, UUID id) {
        var rows = em.createNativeQuery("""
                SELECT source_account_id,destination_account_id,amount_cents,currency,accepted_at,rule_version
                FROM transfers WHERE tenant_id=:tenant AND initiated_by=:actor AND id=:id
                """).setParameter("tenant", actor.tenantId()).setParameter("actor", actor.subject()).setParameter("id", id).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Object[] r = (Object[]) rows.getFirst();
        return Optional.of(new Transfer(id, actor.tenantId(), actor.subject(), (UUID) r[0], (UUID) r[1],
                new Money(((Number) r[2]).longValue(), r[3].toString()), instant(r[4]), r[5].toString()));
    }

    @Override
    public void append(AcceptedEvent event) {
        em.createNativeQuery("""
                INSERT INTO canonical_events(event_id,tenant_id,aggregate_id,aggregate_version,event_type,schema_version,occurred_at,payload,correlation_id,traceparent)
                VALUES (:id,:tenant,:aggregate,:version,:type,:schema,:at,CAST(:payload AS jsonb),:correlation,:trace)
                """).setParameter("id", event.eventId()).setParameter("tenant", event.tenantId())
                .setParameter("aggregate", event.aggregateId()).setParameter("version", event.aggregateVersion())
                .setParameter("type", event.eventType()).setParameter("schema", event.schemaVersion())
                .setParameter("at", event.occurredAt()).setParameter("payload", json.writeValueAsString(event))
                .setParameter("correlation", event.correlationId()).setParameter("trace", event.traceparent()).executeUpdate();
        em.createNativeQuery("INSERT INTO outbox_deliveries(event_id,created_at,next_attempt_at) VALUES (:id,:at,:at)")
                .setParameter("id", event.eventId()).setParameter("at", event.occurredAt()).executeUpdate();
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime d) return d.toInstant();
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        throw new IllegalStateException("Unsupported PostgreSQL timestamp representation.");
    }
}
