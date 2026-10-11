package dev.dtp.application;

import java.util.UUID;
import dev.dtp.domain.RuleViolation;

/** SQL-only canonical requeue. Never changes money, event identity or aggregate version. */
public final class RequeueAccepted {
    private final TransactionRunner transactions;
    private final RequeueRepository repository;
    public RequeueAccepted(TransactionRunner transactions, RequeueRepository repository) { this.transactions = transactions; this.repository = repository; }
    public RequeueRepository.Result execute(Actor actor, UUID transferId, String reason) {
        if (!actor.roles().contains("operator")) throw new AccessDenied();
        if (reason == null || reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl)) {
            throw new RuleViolation("operation.reason.invalid", "A bounded reason is required.");
        }
        return transactions.required(() -> repository.request(actor, transferId, reason.strip()));
    }
}
