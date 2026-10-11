package dev.dtp.activity;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

record ActivityIdentity(UUID tenantId, String subject, String clientId, java.util.Set<String> roles) {
    boolean canAudit() { return roles.contains("auditor"); }
    static ActivityIdentity from(Jwt jwt) {
        var tenant = jwt.getClaimAsString("tenant_id"); var subject = jwt.getSubject(); var client = jwt.getClaimAsString("azp");
        if (tenant == null || subject == null || subject.isBlank() || subject.length() > 128 || client == null || client.isBlank() || client.length() > 128) {
            throw new IllegalArgumentException("Required identity claims missing.");
        }
        var roles = RealmAuthorities.from(jwt).stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ActivityIdentity(UUID.fromString(tenant), subject, client, roles);
    }
}
