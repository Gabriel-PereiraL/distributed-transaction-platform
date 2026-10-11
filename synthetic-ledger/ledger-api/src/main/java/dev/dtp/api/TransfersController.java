package dev.dtp.api;

import dev.dtp.application.AccountRepository;
import dev.dtp.application.AdmitTransfer;
import dev.dtp.application.Admission;
import dev.dtp.application.AccessDenied;
import dev.dtp.application.TransactionRunner;
import dev.dtp.application.TransferCommand;
import dev.dtp.application.TransferRepository;
import dev.dtp.domain.Money;
import dev.dtp.domain.Transfer;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransfersController {
    record Request(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
                   @Min(1) @Max(Money.MAX_TRANSFER_CENTS) long amountCents,
                   @NotNull @Pattern(regexp = "BRL") String currency) { }
    record AccountView(UUID id, String currency, long balanceCents, long reservedCents, long availableCents) { }
    record TransferView(UUID id, String status, UUID sourceAccountId, UUID destinationAccountId,
                        long amountCents, String currency, java.time.Instant acceptedAt, String ruleVersion,
                        long aggregateVersion, java.time.Instant terminalAt, String rejectionReason) { }

    private final AdmitTransfer admit;
    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final ObjectProvider<Tracer> tracing;
    private final dev.dtp.application.MutationQuota quota;

    TransfersController(AdmitTransfer admit, TransactionRunner tx, AccountRepository accounts,
                        TransferRepository transfers, ObjectProvider<Tracer> tracing, dev.dtp.application.MutationQuota quota) {
        this.admit = admit; this.tx = tx; this.accounts = accounts; this.transfers = transfers; this.tracing = tracing;
        this.quota = quota;
    }

    @PostMapping("/v1/transfers")
    ResponseEntity<Admission> accept(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody Request request, HttpServletRequest http) {
        var actor = ActorMapping.from(jwt);
        quota.check(actor);
        var result = admit.execute(actor, key,
                new TransferCommand(request.sourceAccountId(), request.destinationAccountId(), new Money(request.amountCents(), request.currency())),
                http.getAttribute(RequestProtection.CORRELATION_ATTRIBUTE).toString(), traceparent());
        return ResponseEntity.accepted().location(URI.create("/v1/transfers/" + result.admission().transferId()))
                .header("Idempotent-Replayed", Boolean.toString(result.replayed())).body(result.admission());
    }

    @GetMapping("/v1/accounts/{id}")
    AccountView account(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var account = tx.required(() -> accounts.findVisible(ActorMapping.from(jwt), id).orElseThrow(AccessDenied::new));
        return new AccountView(account.id(), account.currency(), account.balanceCents(), account.reservedCents(), account.availableCents());
    }

    @GetMapping("/v1/transfers/{id}")
    TransferView transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var state = tx.required(() -> transfers.findState(ActorMapping.from(jwt), id).orElseThrow(AccessDenied::new));
        Transfer t = state.transfer();
        return new TransferView(t.id(), state.status(), t.sourceAccountId(), t.destinationAccountId(), t.amount().cents(),
                t.amount().currency(), t.acceptedAt(), t.ruleVersion(), state.aggregateVersion(), state.terminalAt(), state.reason());
    }

    private String traceparent() {
        var tracer = tracing.getIfAvailable();
        var span = tracer == null ? null : tracer.currentSpan();
        if (span == null) return null;
        var context = span.context();
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }
}
