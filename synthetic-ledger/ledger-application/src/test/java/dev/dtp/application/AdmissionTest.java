package dev.dtp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import dev.dtp.domain.Account;
import dev.dtp.domain.Money;
import dev.dtp.domain.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdmissionTest {
    @Mock AccountRepository accounts;
    @Mock TransferRepository transfers;
    @Mock EventRepository events;
    final Actor actor = new Actor(UUID.randomUUID(), "alice", "client");
    final UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
    AdmitTransfer useCase;

    @BeforeEach void setup() {
        TransactionRunner direct = new TransactionRunner() {
            @Override public <T> T required(Supplier<T> operation) { return operation.get(); }
        };
        useCase = new AdmitTransfer(direct, accounts, transfers, events,
                Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC));
    }
    @Test void replayDoesNotReserveOrEmitAgain() {
        var prior = Admission.accepted(UUID.randomUUID(), Instant.parse("2026-10-10T12:00:00Z"));
        when(transfers.claim(any(), anyString(), anyString(), any())).thenReturn(Optional.of(prior));
        var result = useCase.execute(actor, "same-key", command(), "correlation", null);
        assertThat(result.replayed()).isTrue();
        assertThat(result.admission()).isEqualTo(prior);
        verify(accounts, never()).lockPair(any(), any(), any());
        verify(events, never()).append(any());
    }
    @Test void foreignOwnerCannotReserve() {
        when(transfers.claim(any(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(accounts.lockPair(any(), any(), any())).thenReturn(pair("mallory", 100));
        assertThatThrownBy(() -> useCase.execute(actor, "key", command(), "correlation", null)).isInstanceOf(AccessDenied.class);
        verify(events, never()).append(any());
        verify(accounts, never()).saveReservation(any());
    }
    @Test void insufficientFundsDoNotEmitAcceptedEvent() {
        when(transfers.claim(any(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(accounts.lockPair(any(), any(), any())).thenReturn(pair("alice", 1));
        assertThatThrownBy(() -> useCase.execute(actor, "key", command(), "correlation", null)).isInstanceOf(RuleViolation.class);
        verify(transfers, never()).insertWithHold(any());
    }
    @Test void invalidKeyNeverReachesPersistence() {
        assertThatThrownBy(() -> useCase.execute(actor, "bad key", command(), "correlation", null)).isInstanceOf(RuleViolation.class);
        verify(transfers, never()).claim(any(), anyString(), anyString(), any());
    }
    private TransferCommand command() { return new TransferCommand(source, destination, new Money(10, "BRL")); }
    private List<Account> pair(String owner, long balance) {
        return List.of(new Account(source, actor.tenantId(), owner, "BRL", balance, 0, true),
                new Account(destination, actor.tenantId(), "bob", "BRL", 0, 0, true));
    }
}
