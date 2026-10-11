package dev.dtp.application;

import dev.dtp.domain.PostingPolicy;
import java.time.Instant;
import java.util.UUID;

public interface PostingRepository {
    record Request(UUID eventId, UUID tenantId, UUID transferId) { }
    record Locked(TransferState state, String correlationId, String traceparent) { }
    record Accounts(boolean sourceActive, boolean destinationActive) { }
    /** Lock the transfer before canonically ordered account locks; validate canonical identity. */
    Locked lockCanonical(Request request);
    Accounts lockAccounts(Locked transfer);
    void complete(Request request, Locked transfer, PostingPolicy.Decision decision, Instant at);
}
