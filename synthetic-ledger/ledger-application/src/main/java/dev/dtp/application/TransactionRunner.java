package dev.dtp.application;

import java.util.function.Supplier;

public interface TransactionRunner {
    <T> T required(Supplier<T> operation);
}
