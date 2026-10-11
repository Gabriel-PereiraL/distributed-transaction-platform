package dev.dtp.domain;

/** Positive BRL amount, in integer cents. No rounding or floating-point conversion. */
public record Money(long cents, String currency) {
    public static final long MAX_TRANSFER_CENTS = 1_000_000_000L;

    public Money {
        if (cents <= 0 || cents > MAX_TRANSFER_CENTS) {
            throw new RuleViolation("amount.invalid", "Amount must be between 1 and 1000000000 cents.");
        }
        if (!"BRL".equals(currency)) {
            throw new RuleViolation("currency.unsupported", "Only BRL is supported.");
        }
    }
}
