package dev.dtp.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable admission identity; the current state is stored separately from the accepted event. */
public record Transfer(UUID id, UUID tenantId, String initiatedBy, UUID sourceAccountId,
                       UUID destinationAccountId, Money amount, Instant acceptedAt, String ruleVersion) {
    public static final String RULE_VERSION = "synthetic-v1";

    public Transfer {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(initiatedBy);
        Objects.requireNonNull(sourceAccountId);
        Objects.requireNonNull(destinationAccountId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(acceptedAt);
        Objects.requireNonNull(ruleVersion);
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new RuleViolation("transfer.same_account", "Source and destination must differ.");
        }
    }
}
