package dev.dtp.domain;

/** Versioned, deterministic laboratory rule. Never retrospectively change this version. */
public final class PostingPolicy {
    public static final long V1_LIMIT_CENTS = 500_000;
    public record Decision(String status, String reason) { }

    public Decision decide(Transfer transfer, boolean sourceActive, boolean destinationActive) {
        if (!Transfer.RULE_VERSION.equals(transfer.ruleVersion())) {
            throw new IllegalArgumentException("Unsupported persisted rule version.");
        }
        if (!sourceActive || !destinationActive) return new Decision("REJECTED", "ACCOUNT_INACTIVE");
        if (transfer.amount().cents() > V1_LIMIT_CENTS) return new Decision("REJECTED", "SYNTHETIC_LIMIT");
        return new Decision("POSTED", null);
    }
}
