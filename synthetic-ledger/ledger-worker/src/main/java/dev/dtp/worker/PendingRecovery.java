package dev.dtp.worker;

import dev.dtp.adapters.persistence.PostgresOutbox;
import dev.dtp.application.ProcessTransfer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Explicitly opt-in recovery; emits separate counters so delivery failures are visible. */
@Component
public class PendingRecovery {
    private final PostgresOutbox outbox;
    private final ProcessTransfer process;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final int ageSeconds;
    public PendingRecovery(PostgresOutbox outbox, ProcessTransfer process, MeterRegistry metrics,
                           @Value("${dtp.recovery.enabled:false}") boolean enabled, @Value("${dtp.recovery.age-seconds:300}") int ageSeconds) {
        if (ageSeconds < 1) throw new IllegalArgumentException("Recovery age must be positive.");
        this.outbox = outbox; this.process = process; this.metrics = metrics; this.enabled = enabled; this.ageSeconds = ageSeconds;
    }
    @Scheduled(fixedDelayString = "${dtp.recovery.delay-ms:30000}") public void tick() { if (enabled) recover(ageSeconds); }
    public int recover(int age) {
        int completed = 0;
        for (var request : outbox.overdue(age, 5)) {
            try {
                var result = process.execute(request);
                if (!result.alreadyTerminal()) { completed++; metrics.counter("dtp.recovery.terminalized").increment(); }
            } catch (RuntimeException failure) { metrics.counter("dtp.recovery.failed").increment(); }
        }
        return completed;
    }
}
