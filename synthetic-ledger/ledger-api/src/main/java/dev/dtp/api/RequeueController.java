package dev.dtp.api;

import dev.dtp.application.MutationQuota;
import dev.dtp.application.RequeueAccepted;
import dev.dtp.application.RequeueRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RequeueController {
    record Request(@NotBlank @Size(max = 256) @Pattern(regexp = "[^\\p{Cntrl}]*") String reason) { }
    private final RequeueAccepted service;
    private final MutationQuota quota;
    RequeueController(RequeueAccepted service, MutationQuota quota) { this.service = service; this.quota = quota; }
    @PostMapping("/v1/transfers/{id}/requeue")
    ResponseEntity<RequeueRepository.Result> request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Request request) {
        var actor = ActorMapping.from(jwt);
        quota.check(actor, "POST /v1/transfers/{id}/requeue");
        var result = service.execute(actor, id, request.reason());
        return ResponseEntity.status("QUEUED".equals(result.outcome()) ? 202 : 200).body(result);
    }
}
