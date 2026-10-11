package dev.dtp.worker;

import dev.dtp.application.AccountRepository;
import dev.dtp.application.AdmitTransfer;
import dev.dtp.application.EventRepository;
import dev.dtp.application.TransactionRunner;
import dev.dtp.application.TransferRepository;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"dev.dtp.worker", "dev.dtp.adapters"})
@EntityScan("dev.dtp.adapters.persistence")
@EnableScheduling
public class LedgerWorker {
    public static void main(String[] args) {
        if (args.length > 0 && "requeue".equals(args[0])) {
            if (args.length != 5 || !"true".equals(System.getenv("DTP_ALLOW_REQUEUE"))) {
                throw new IllegalStateException("Explicit DTP_ALLOW_REQUEUE=true and requeue tenant transfer operator reason are required.");
            }
            var tenant = java.util.UUID.fromString(args[1]); var transfer = java.util.UUID.fromString(args[2]);
            try (var context = SpringApplication.run(LedgerWorker.class, "--dtp.publisher.enabled=false", "--dtp.consumer.enabled=false", "--dtp.recovery.enabled=false")) {
                var outcome = context.getBean(CanonicalRequeue.class).execute(tenant, transfer, args[3], args[4]);
                org.slf4j.LoggerFactory.getLogger(LedgerWorker.class).info("Canonical requeue completed: {}", outcome);
            }
            return;
        }
        SpringApplication.run(LedgerWorker.class, args);
    }
    @Bean Clock clock() { return Clock.systemUTC(); }
    /** Also used by integration fixtures; no admission HTTP surface exists in this executable. */
    @Bean AdmitTransfer admitTransfer(TransactionRunner tx, AccountRepository accounts, TransferRepository transfers, EventRepository events, Clock clock) {
        return new AdmitTransfer(tx, accounts, transfers, events, clock);
    }
}
