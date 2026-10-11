package dev.dtp.contracts;

import java.time.Instant;
import java.util.UUID;

/** Public transport snapshot. It has no ledger domain, JPA, Spring or database dependency. */
public record TransferFact(UUID eventId, UUID tenantId, UUID transferId, long aggregateVersion, String eventType,
                           Instant occurredAt, String initiatedBy, UUID sourceAccountId, UUID destinationAccountId,
                           long amountCents, String currency, Instant acceptedAt, String ruleVersion, String status,
                           Instant terminalAt, String rejectionReason, String correlationId, String traceparent) {
    public String key() { return tenantId + ":" + transferId; }
}
