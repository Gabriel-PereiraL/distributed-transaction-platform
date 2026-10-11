package dev.dtp.application;

import java.time.Instant;
import java.util.UUID;

public record Admission(UUID transferId, String status, Instant acceptedAt) {
    public static Admission accepted(UUID id, Instant at) { return new Admission(id, "ACCEPTED", at); }
}
