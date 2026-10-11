package dev.dtp.application;

import dev.dtp.domain.Transfer;
import java.time.Instant;
import java.util.UUID;

public record AcceptedEvent(UUID eventId, String eventType, int schemaVersion, UUID tenantId,
                            UUID aggregateId, long aggregateVersion, Instant occurredAt,
                            Transfer payload, String correlationId, String traceparent) { }
