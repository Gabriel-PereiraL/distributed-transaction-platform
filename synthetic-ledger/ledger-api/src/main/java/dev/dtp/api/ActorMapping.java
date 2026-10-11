package dev.dtp.api;

import dev.dtp.application.Actor;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

final class ActorMapping {
    private ActorMapping() { }
    static Actor from(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant_id");
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("Missing tenant identity.");
        var roles = RealmAuthorities.from(jwt).stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Actor(UUID.fromString(tenant), jwt.getSubject(), jwt.getClaimAsString("azp"), roles);
    }
}
