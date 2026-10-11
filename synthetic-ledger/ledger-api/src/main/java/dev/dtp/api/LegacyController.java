package dev.dtp.api;

import dev.dtp.application.AccessDenied;
import dev.dtp.application.AccountRepository;
import dev.dtp.application.LegacyDirectory;
import dev.dtp.application.TransactionRunner;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "dtp.legacy.enabled", havingValue = "true")
class LegacyController {
    private final TransactionRunner transactions;
    private final AccountRepository accounts;
    private final LegacyDirectory directory;
    LegacyController(TransactionRunner transactions, AccountRepository accounts, LegacyDirectory directory) {
        this.transactions = transactions; this.accounts = accounts; this.directory = directory;
    }
    @GetMapping("/v1/accounts/{id}/legacy")
    LegacyDirectory.Metadata lookup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        transactions.required(() -> accounts.findOwned(ActorMapping.from(jwt), id).orElseThrow(AccessDenied::new));
        return directory.lookup(id); // Read transaction has completed before remote I/O.
    }
}
