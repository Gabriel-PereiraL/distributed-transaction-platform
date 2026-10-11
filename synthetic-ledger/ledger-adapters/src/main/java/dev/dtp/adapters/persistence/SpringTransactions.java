package dev.dtp.adapters.persistence;

import dev.dtp.application.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringTransactions implements TransactionRunner {
    private final TransactionTemplate template;

    public SpringTransactions(PlatformTransactionManager manager) {
        template = new TransactionTemplate(manager);
        template.setTimeout(10);
    }

    @Override public <T> T required(Supplier<T> operation) { return template.execute(status -> operation.get()); }
}
