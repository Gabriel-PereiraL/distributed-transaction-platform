package dev.dtp.application;

import java.util.Objects;
import java.util.UUID;

public record Actor(UUID tenantId, String subject, String clientId, java.util.Set<String> roles) {
    public Actor(UUID tenantId, String subject, String clientId) { this(tenantId, subject, clientId, java.util.Set.of()); }
    public Actor {
        Objects.requireNonNull(tenantId);
        roles = java.util.Set.copyOf(roles);
        if (subject == null || subject.isBlank() || subject.length() > 128
                || clientId == null || clientId.isBlank() || clientId.length() > 128) {
            throw new IllegalArgumentException("Invalid authenticated identity.");
        }
    }
    public boolean canAudit() { return roles.contains("auditor"); }
}
