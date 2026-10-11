package dev.dtp.application;

import dev.dtp.domain.RuleViolation;
import dev.dtp.domain.Transfer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/** Admission is one local transaction. No provider/broker call participates in it. */
public final class AdmitTransfer {
    private final TransactionRunner transactions;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final EventRepository events;
    private final Clock clock;

    public AdmitTransfer(TransactionRunner transactions, AccountRepository accounts,
                         TransferRepository transfers, EventRepository events, Clock clock) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.transfers = transfers;
        this.events = events;
        this.clock = clock;
    }

    public record Result(Admission admission, boolean replayed) { }

    public Result execute(Actor actor, String key, TransferCommand command, String correlationId, String traceparent) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new RuleViolation("idempotency.invalid_key", "Idempotency-Key must contain 1 to 64 letters, digits, '-' or '_'.");
        }
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var transfer = new Transfer(UUID.randomUUID(), actor.tenantId(), actor.subject(),
                command.sourceAccountId(), command.destinationAccountId(), command.amount(), at, Transfer.RULE_VERSION);
        var hash = fingerprint(command);
        return transactions.required(() -> {
            var prior = transfers.claim(actor, key, hash, Admission.accepted(transfer.id(), at));
            if (prior.isPresent()) return new Result(prior.orElseThrow(), true);
            var pair = accounts.lockPair(actor.tenantId(), transfer.sourceAccountId(), transfer.destinationAccountId());
            var source = pair.stream().filter(a -> a.id().equals(transfer.sourceAccountId())).findFirst().orElseThrow(AccessDenied::new);
            var destination = pair.stream().filter(a -> a.id().equals(transfer.destinationAccountId())).findFirst().orElseThrow(AccessDenied::new);
            if (!source.owner().equals(actor.subject())) throw new AccessDenied();
            if (!destination.active()) throw new RuleViolation("account.inactive", "Destination account is inactive.");
            if (!destination.currency().equals(transfer.amount().currency())) throw new RuleViolation("currency.mismatch", "Currency mismatch.");
            accounts.saveReservation(source.reserve(transfer.amount()));
            transfers.insertWithHold(transfer);
            events.append(new AcceptedEvent(UUID.randomUUID(), "TransferAccepted", 1, actor.tenantId(),
                    transfer.id(), 1, at, transfer, correlationId, traceparent));
            return new Result(Admission.accepted(transfer.id(), at), false);
        });
    }

    private static String fingerprint(TransferCommand command) {
        var canonical = "POST /v1/transfers\n" + command.sourceAccountId() + "\n" + command.destinationAccountId()
                + "\n" + command.amount().cents() + "\n" + command.amount().currency();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable.", failure);
        }
    }
}
