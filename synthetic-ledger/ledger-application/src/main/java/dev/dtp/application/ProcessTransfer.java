package dev.dtp.application;

import dev.dtp.domain.PostingPolicy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** SQL commit precedes Kafka offset acknowledgment. No network calls inside this boundary. */
public final class ProcessTransfer {
    private final TransactionRunner transactions;
    private final PostingRepository repository;
    private final PostingPolicy policy;
    private final Clock clock;

    public ProcessTransfer(TransactionRunner transactions, PostingRepository repository, PostingPolicy policy, Clock clock) {
        this.transactions = transactions; this.repository = repository; this.policy = policy; this.clock = clock;
    }
    public record Result(String status, boolean alreadyTerminal) { }
    public Result execute(PostingRepository.Request request) {
        return transactions.required(() -> {
            var locked = repository.lockCanonical(request);
            if (!"ACCEPTED".equals(locked.state().status())) return new Result(locked.state().status(), true);
            var accounts = repository.lockAccounts(locked);
            var decision = policy.decide(locked.state().transfer(), accounts.sourceActive(), accounts.destinationActive());
            repository.complete(request, locked, decision, clock.instant().truncatedTo(ChronoUnit.MILLIS));
            return new Result(decision.status(), false);
        });
    }
}
