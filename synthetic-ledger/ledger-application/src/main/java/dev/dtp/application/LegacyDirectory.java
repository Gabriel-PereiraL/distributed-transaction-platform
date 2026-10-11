package dev.dtp.application;

import java.util.UUID;

/** Optional read-only external metadata, never a financial authority. */
public interface LegacyDirectory {
    record Metadata(String externalReference, String state) {
        public Metadata {
            if (externalReference == null || !externalReference.matches("[A-Za-z0-9_-]{1,64}")
                    || !("ACTIVE".equals(state) || "INACTIVE".equals(state))) {
                throw new IllegalArgumentException("Invalid legacy metadata.");
            }
        }
    }
    Metadata lookup(UUID accountId);
}
