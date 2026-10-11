package dev.dtp.application;

public interface EventRepository {
    /** Canonical event and delivery outbox belong to the caller's SQL transaction. */
    void append(AcceptedEvent event);
}
