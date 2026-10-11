package dev.dtp.api;

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

@SpringBootApplication(scanBasePackages = "dev.dtp")
@EntityScan("dev.dtp.adapters.persistence")
public class LedgerApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("migrate")) {
            if (!"true".equals(System.getenv("DTP_ALLOW_MIGRATION"))) {
                throw new IllegalStateException("Explicit DTP_ALLOW_MIGRATION=true is required.");
            }
            org.flywaydb.core.Flyway.configure()
                    .dataSource(required("DTP_DATABASE_URL"), required("DTP_DATABASE_USER"), required("DTP_DATABASE_PASSWORD"))
                    .locations("classpath:db/migration").load().migrate();
            return;
        }
        SpringApplication.run(LedgerApplication.class, args);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration: " + name);
        return value;
    }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean dev.dtp.application.RequeueAccepted requeueAccepted(TransactionRunner tx, dev.dtp.application.RequeueRepository repository) {
        return new dev.dtp.application.RequeueAccepted(tx, repository);
    }
    @Bean AdmitTransfer admitTransfer(TransactionRunner tx, AccountRepository accounts, TransferRepository transfers,
                                     EventRepository events, Clock clock) {
        return new AdmitTransfer(tx, accounts, transfers, events, clock);
    }
}
