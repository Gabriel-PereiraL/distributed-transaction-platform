package dev.dtp.adapters.persistence;

import dev.dtp.application.PostingRepository;
import dev.dtp.application.ProcessTransfer;
import dev.dtp.application.TransactionRunner;
import dev.dtp.domain.PostingPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PostingConfiguration {
    @Bean ProcessTransfer processTransfer(TransactionRunner tx, PostingRepository repository, Clock clock) {
        return new ProcessTransfer(tx, repository, new PostingPolicy(), clock);
    }
}
