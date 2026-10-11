package dev.dtp.activity;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import dev.dtp.contracts.InvalidContract;
import dev.dtp.contracts.TransferEventDecoder;
import dev.dtp.contracts.TransferFact;
import java.util.Date;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class MongoHistory {
    private final MongoCollection<Document> collection;
    private final TransferEventDecoder decoder;
    public MongoHistory(MongoTemplate mongo, TransferEventDecoder decoder, @Value("${dtp.activity.prefix}") String prefix) {
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,31}")) throw new IllegalArgumentException("Bounded activity prefix required.");
        this.decoder = decoder; collection = mongo.getCollection(prefix + "_events");
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId", "eventId")), new IndexOptions().unique(true));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId", "transferId", "aggregateVersion")), new IndexOptions().unique(true));
    }
    public void put(TransferFact fact) {
        String id = fact.tenantId() + ":" + fact.eventId(); String hash = decoder.canonicalHash(fact);
        var document = new Document("_id", id).append("tenantId", fact.tenantId().toString()).append("eventId", fact.eventId().toString())
                .append("transferId", fact.transferId().toString()).append("aggregateVersion", fact.aggregateVersion()).append("hash", hash)
                .append("eventType", fact.eventType()).append("occurredAt", Date.from(fact.occurredAt()))
                .append("initiatedBy", fact.initiatedBy()).append("status", fact.status()).append("rejectionReason", fact.rejectionReason());
        try { collection.insertOne(document); }
        catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw duplicate;
            var prior = collection.find(new Document("_id", id)).first();
            if (prior == null || !hash.equals(prior.getString("hash"))) throw new InvalidContract();
            // A duplicate event DOES NOT skip the subsequent snapshot update.
        }
    }
    public java.util.List<Document> ownedEvents(java.util.UUID tenant, String subject, java.util.UUID transfer) {
        return visibleEvents(tenant, subject, transfer, false);
    }
    public java.util.List<Document> visibleEvents(java.util.UUID tenant, String subject, java.util.UUID transfer, boolean audit) {
        var filter = new Document("tenantId", tenant.toString()).append("transferId", transfer.toString());
        if (!audit) filter.append("initiatedBy", subject);
        return collection.find(filter)
                .sort(new Document("aggregateVersion", 1)).limit(20).into(new java.util.ArrayList<>());
    }
}
