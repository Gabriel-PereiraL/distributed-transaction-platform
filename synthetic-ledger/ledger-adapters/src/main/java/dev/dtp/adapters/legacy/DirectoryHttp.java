package dev.dtp.adapters.legacy;

import dev.dtp.application.LegacyDirectory;
import dev.dtp.application.LegacyUnavailable;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.json.JsonMapper;

/** Laboratory-only metadata HTTP adapter. No credentials, redirects, writes or financial fallback. */
public final class DirectoryHttp implements LegacyDirectory, AutoCloseable {
    private final URI base;
    private final Duration deadline;
    private final HttpClient http;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final JsonMapper json = JsonMapper.builder().build();

    public DirectoryHttp(URI base, Duration deadline, int concurrency) {
        this(base, deadline, concurrency, CircuitBreakerConfig.custom().slidingWindowSize(4).minimumNumberOfCalls(4)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(2)).permittedNumberOfCallsInHalfOpenState(2).build());
    }
    DirectoryHttp(URI base, Duration deadline, int concurrency, CircuitBreakerConfig breakerConfig) {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath())) || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(Duration.ofSeconds(5)) > 0 || concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("Legacy endpoint must be an explicit local HTTP origin with bounded limits.");
        }
        this.base = base; this.deadline = deadline;
        http = HttpClient.newBuilder().connectTimeout(deadline).followRedirects(HttpClient.Redirect.NEVER).build();
        breaker = CircuitBreaker.of("legacy-directory", breakerConfig);
        bulkhead = Bulkhead.of("legacy-directory", BulkheadConfig.custom().maxConcurrentCalls(concurrency).maxWaitDuration(Duration.ZERO).build());
        retry = Retry.of("legacy-directory", RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(25))
                .retryExceptions(LegacyUnavailable.class).build());
    }
    @Override public Metadata lookup(UUID accountId) {
        java.util.Objects.requireNonNull(accountId);
        long end = System.nanoTime() + deadline.toNanos();
        // Bulkhead outside breaker: saturation does not count as a downstream failure.
        var operation = Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker,
                Retry.decorateSupplier(retry, () -> request(accountId, end))));
        try { return operation.get(); }
        catch (RuntimeException unavailable) { throw new LegacyUnavailable(); }
    }
    private Metadata request(UUID accountId, long end) {
        long remaining = end - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new LegacyUnavailable();
        var request = HttpRequest.newBuilder(base.resolve("/accounts/" + accountId)).timeout(Duration.ofNanos(remaining))
                .header("Accept", "application/json").GET().build();
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new BoundedBody(4096));
        try {
            var response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new LegacyUnavailable();
            var node = json.readTree(response.body());
            if (node.size() != 2 || !node.path("externalReference").isString() || !node.path("state").isString()) throw new LegacyUnavailable();
            return new Metadata(node.path("externalReference").stringValue(), node.path("state").stringValue());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new LegacyUnavailable();
        } catch (Exception unavailable) {
            throw new LegacyUnavailable();
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    CircuitBreaker.State state() { return breaker.getState(); }
    @Override public void close() { http.close(); }
}
