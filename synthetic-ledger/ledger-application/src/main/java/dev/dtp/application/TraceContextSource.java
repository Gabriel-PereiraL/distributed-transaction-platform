package dev.dtp.application;

/** Optional transport metadata only; never participates in financial decisions. */
@FunctionalInterface
public interface TraceContextSource { String currentTraceparent(); }
