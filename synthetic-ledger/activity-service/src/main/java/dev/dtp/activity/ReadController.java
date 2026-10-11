package dev.dtp.activity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ReadController {
    record Receipt(UUID id, String status, long projectionVersion, Instant projectionUpdatedAt, UUID sourceAccountId, UUID destinationAccountId,
                   long amountCents, String currency, Instant acceptedAt, Instant terminalAt, String rejectionReason) { }
    record Page(List<Receipt> items, String nextCursor) { }
    record HistoryEvent(UUID eventId, String eventType, long aggregateVersion, Instant occurredAt, String status, String rejectionReason) { }
    private final MongoSnapshots snapshots;
    private final MongoHistory history;
    ReadController(MongoSnapshots snapshots, MongoHistory history) { this.snapshots = snapshots; this.history = history; }
    @GetMapping("/v1/activity/transfers/{id}") Receipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var actor = ActivityIdentity.from(jwt);
        return view(snapshots.visible(actor.tenantId(), actor.subject(), id, actor.canAudit()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
    @GetMapping("/v1/activity/transfers") Page page(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit,
                                                 @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit outside bound.");
        Instant beforeAt = null; UUID beforeId = null;
        if (cursor != null) {
            if (cursor.length() > 128) throw new IllegalArgumentException("Cursor outside bound.");
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor.");
            beforeAt = Instant.ofEpochMilli(Long.parseLong(parts[0])); beforeId = UUID.fromString(parts[1]);
        }
        var actor = ActivityIdentity.from(jwt); var rows = snapshots.list(actor.tenantId(), actor.subject(), limit + 1, beforeAt, beforeId, actor.canAudit());
        boolean more = rows.size() > limit; var items = rows.stream().limit(limit).map(ReadController::view).toList();
        String next = null;
        if (more) { var last = items.getLast(); next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.acceptedAt().toEpochMilli() + ":" + last.id()).getBytes(StandardCharsets.UTF_8)); }
        return new Page(items, next);
    }
    @GetMapping("/v1/activity/transfers/{id}/events") List<HistoryEvent> events(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var actor = ActivityIdentity.from(jwt);
        snapshots.visible(actor.tenantId(), actor.subject(), id, actor.canAudit()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return history.visibleEvents(actor.tenantId(), actor.subject(), id, actor.canAudit()).stream().map(d -> new HistoryEvent(UUID.fromString(d.getString("eventId")),
                d.getString("eventType"), ((Number) d.get("aggregateVersion")).longValue(), ((Date) d.get("occurredAt")).toInstant(),
                d.getString("status"), d.getString("rejectionReason"))).toList();
    }
    private static Receipt view(Document d) {
        var terminal = (Date) d.get("terminalAt");
        return new Receipt(UUID.fromString(d.getString("transferId")), d.getString("status"), ((Number) d.get("aggregateVersion")).longValue(),
                ((Date) d.get("projectionUpdatedAt")).toInstant(), UUID.fromString(d.getString("sourceAccountId")), UUID.fromString(d.getString("destinationAccountId")),
                ((Number) d.get("amountCents")).longValue(), d.getString("currency"), ((Date) d.get("acceptedAt")).toInstant(), terminal == null ? null : terminal.toInstant(), d.getString("rejectionReason"));
    }
}
