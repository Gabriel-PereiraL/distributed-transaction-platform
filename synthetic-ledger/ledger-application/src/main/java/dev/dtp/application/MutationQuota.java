package dev.dtp.application;

/** Admission HTTP edge calls this before opening its financial transaction. */
@FunctionalInterface
public interface MutationQuota {
    void check(Actor actor);
    default void check(Actor actor, String operation) { check(actor); }
}
