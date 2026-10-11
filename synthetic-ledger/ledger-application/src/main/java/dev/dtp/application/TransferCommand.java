package dev.dtp.application;

import dev.dtp.domain.Money;
import java.util.Objects;
import java.util.UUID;

public record TransferCommand(UUID sourceAccountId, UUID destinationAccountId, Money amount) {
    public TransferCommand {
        Objects.requireNonNull(sourceAccountId);
        Objects.requireNonNull(destinationAccountId);
        Objects.requireNonNull(amount);
    }
}
