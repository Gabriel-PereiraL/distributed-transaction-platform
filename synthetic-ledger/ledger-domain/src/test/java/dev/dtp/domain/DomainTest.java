package dev.dtp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainTest {
    @ParameterizedTest @ValueSource(longs = {0, -1, 1_000_000_001L, Long.MAX_VALUE})
    void rejectsInvalidAmount(long cents) {
        assertThatThrownBy(() -> new Money(cents, "BRL")).isInstanceOf(RuleViolation.class);
    }
    @ParameterizedTest @ValueSource(strings = {"USD", "brl", "", "BRLL"})
    void rejectsUnsupportedCurrency(String currency) {
        assertThatThrownBy(() -> new Money(1, currency)).isInstanceOf(RuleViolation.class);
    }
    @Test void minimumAndMaximumAmountsAreExact() {
        assertThat(new Money(1, "BRL").cents()).isEqualTo(1);
        assertThat(new Money(Money.MAX_TRANSFER_CENTS, "BRL").cents()).isEqualTo(Money.MAX_TRANSFER_CENTS);
    }
    @Test void reservationUsesAvailableFundsWithoutChangingBalance() {
        var original = account(100, 40, true);
        var reserved = original.reserve(new Money(60, "BRL"));
        assertThat(reserved.balanceCents()).isEqualTo(100);
        assertThat(reserved.availableCents()).isZero();
        assertThat(original.reservedCents()).isEqualTo(40);
        assertThatThrownBy(() -> reserved.reserve(new Money(1, "BRL"))).isInstanceOf(RuleViolation.class);
    }
    @Test void inactiveAccountCannotReserve() {
        assertThatThrownBy(() -> account(100, 0, false).reserve(new Money(1, "BRL"))).isInstanceOf(RuleViolation.class);
    }
    @Test void corruptedCustomerSnapshotIsRejected() {
        assertThatThrownBy(() -> account(10, 11, true)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> account(-1, 0, true)).isInstanceOf(RuleViolation.class);
    }
    @Test void sameAccountCannotReceiveItsOwnTransfer() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> new Transfer(UUID.randomUUID(), UUID.randomUUID(), "alice", id, id,
                new Money(1, "BRL"), Instant.now(), Transfer.RULE_VERSION)).isInstanceOf(RuleViolation.class);
    }
    private static Account account(long balance, long reserved, boolean active) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "alice", "BRL", balance, reserved, active);
    }
}
