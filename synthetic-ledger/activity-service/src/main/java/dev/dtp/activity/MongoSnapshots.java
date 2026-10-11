package dev.dtp.activity;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import dev.dtp.contracts.InvalidContract;
import dev.dtp.contracts.TransferFact;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class MongoSnapshots {
    private final MongoCollection<Document> collection;
    public MongoSnapshots(MongoTemplate mongo, @Value("${dtp.activity.prefix}") String prefix) {
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,31}")) throw new IllegalArgumentException("Bounded activity prefix required.");
        collection = mongo.getCollection(prefix + "_snapshots");
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId", "transferId")), new IndexOptions().unique(true));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId", "initiatedBy"), Indexes.descending("acceptedAt", "transferId")));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId"), Indexes.descending("acceptedAt", "transferId")));
    }
    public void advance(TransferFact fact) {
        var body = body(fact);
        var filter = new Document("_id", fact.key()).append("aggregateVersion", new Document("$lt", fact.aggregateVersion()));
        // Immutable identity predicates prevent terminal facts changing ownership/amount.
        for (var key : List.of("tenantId", "transferId", "initiatedBy", "sourceAccountId", "destinationAccountId", "amountCents", "currency", "acceptedAt", "ruleVersion")) filter.append(key, body.get(key));
        // With only versions 1 and 2, one retry resolves a missing-row upsert race won by v1.
        for (int attempt = 0; attempt < 2; attempt++) {
        try { collection.updateOne(filter, new Document("$set", body), new UpdateOptions().upsert(true)); return; }
        catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw duplicate;
            var prior = collection.find(new Document("_id", fact.key())).first();
            if (prior == null) throw duplicate;
            for (var key : List.of("tenantId", "transferId", "initiatedBy", "sourceAccountId", "destinationAccountId", "amountCents", "currency", "acceptedAt", "ruleVersion")) {
                var previous = prior.get(key); var incoming = body.get(key);
                boolean equal = previous instanceof Number oldNumber && incoming instanceof Number newNumber
                        ? oldNumber.longValue() == newNumber.longValue() : java.util.Objects.equals(previous, incoming);
                if (!equal) throw new InvalidContract();
            }
            if (((Number) prior.get("aggregateVersion")).longValue() < fact.aggregateVersion()) {
                if (attempt == 1) throw duplicate;
                continue;
            }
            // Existing same/newer version is the sole legitimate no-op collision.
            return;
        }
        }
    }
    public Optional<Document> owned(UUID tenant, String subject, UUID transfer) {
        return visible(tenant, subject, transfer, false);
    }
    public Optional<Document> visible(UUID tenant, String subject, UUID transfer, boolean audit) {
        var filter = new Document("tenantId", tenant.toString()).append("transferId", transfer.toString());
        if (!audit) filter.append("initiatedBy", subject);
        return Optional.ofNullable(collection.find(filter).first());
    }
    public List<Document> list(UUID tenant, String subject, int limit, Instant beforeAt, UUID beforeId) {
        return list(tenant, subject, limit, beforeAt, beforeId, false);
    }
    public List<Document> list(UUID tenant, String subject, int limit, Instant beforeAt, UUID beforeId, boolean audit) {
        var filter = new Document("tenantId", tenant.toString());
        if (!audit) filter.append("initiatedBy", subject);
        if (beforeAt != null) filter.append("$or", List.of(new Document("acceptedAt", new Document("$lt", Date.from(beforeAt))),
                new Document("acceptedAt", Date.from(beforeAt)).append("transferId", new Document("$lt", beforeId.toString()))));
        return collection.find(filter).sort(new Document("acceptedAt", -1).append("transferId", -1)).limit(limit).into(new java.util.ArrayList<>());
    }
    private static Document body(TransferFact fact) {
        return new Document("tenantId", fact.tenantId().toString()).append("transferId", fact.transferId().toString()).append("eventId", fact.eventId().toString())
                .append("initiatedBy", fact.initiatedBy()).append("sourceAccountId", fact.sourceAccountId().toString()).append("destinationAccountId", fact.destinationAccountId().toString())
                .append("amountCents", fact.amountCents()).append("currency", fact.currency()).append("acceptedAt", Date.from(fact.acceptedAt())).append("ruleVersion", fact.ruleVersion())
                .append("aggregateVersion", fact.aggregateVersion()).append("status", fact.status()).append("terminalAt", fact.terminalAt() == null ? null : Date.from(fact.terminalAt()))
                .append("rejectionReason", fact.rejectionReason()).append("projectionUpdatedAt", new Date());
    }
}
