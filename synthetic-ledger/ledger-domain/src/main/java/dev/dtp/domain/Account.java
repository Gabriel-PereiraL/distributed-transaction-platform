package dev.dtp.domain;

import java.util.Objects;
import java.util.UUID;

/** Immutable customer account snapshot. Technical funding accounts never enter admission. */
public record Account(UUID id, UUID tenantId, String owner, String currency,
                      long balanceCents, long reservedCents, boolean active) {
    public Account {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(currency);
        if (balanceCents < 0 || reservedCents < 0 || reservedCents > balanceCents) {
            throw new RuleViolation("account.corrupt", "Customer balance and reservations are inconsistent.");
        }
    }

    public long availableCents() { return balanceCents - reservedCents; }

    public Account reserve(Money amount) {
        if (!active) throw new RuleViolation("account.inactive", "Account is inactive.");
        if (!currency.equals(amount.currency())) throw new RuleViolation("currency.mismatch", "Currency mismatch.");
        if (availableCents() < amount.cents()) throw new RuleViolation("funds.insufficient", "Insufficient available funds.");
        return new Account(id, tenantId, owner, currency, balanceCents,
                Math.addExact(reservedCents, amount.cents()), active);
    }
}
