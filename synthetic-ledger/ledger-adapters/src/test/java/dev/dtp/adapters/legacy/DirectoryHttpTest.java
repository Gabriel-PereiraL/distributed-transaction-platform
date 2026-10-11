package dev.dtp.adapters.legacy;

import com.sun.net.httpserver.HttpServer;
import dev.dtp.application.LegacyUnavailable;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryHttpTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), delay = new AtomicInteger();
    private volatile String body = "{\"externalReference\":\"SYNTHETIC_1\",\"state\":\"ACTIVE\"}";
    private volatile CountDownLatch entered, release;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/accounts/", exchange -> {
            calls.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            try {
                if (entered != null) { entered.countDown(); release.await(5, TimeUnit.SECONDS); }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), 0); // Chunked: never trust Content-Length as the bound.
                if (delay.get() > 0) Thread.sleep(delay.get());
                try (var output = exchange.getResponseBody()) { output.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelled) { /* Expected client cancellation in deadline/body-bound tests. */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    @Test void readsMetadataWithoutFinancialFields() {
        try (var directory = new DirectoryHttp(origin(), Duration.ofSeconds(1), 2)) {
            var metadata = directory.lookup(UUID.randomUUID());
            assertEquals("SYNTHETIC_1", metadata.externalReference()); assertEquals("ACTIVE", metadata.state());
            assertEquals(1, calls.get());
        }
    }
    @Test void opensThenRecoversCircuitAndBoundsReadonlyRetries() throws Exception {
        status.set(503);
        var config = CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(75)).permittedNumberOfCallsInHalfOpenState(1).build();
        try (var directory = new DirectoryHttp(origin(), Duration.ofSeconds(1), 2, config)) {
            for (int i = 0; i < 2; i++) assertThrows(LegacyUnavailable.class, () -> directory.lookup(UUID.randomUUID()));
            assertEquals(4, calls.get()); assertEquals(CircuitBreaker.State.OPEN, directory.state());
            assertThrows(LegacyUnavailable.class, () -> directory.lookup(UUID.randomUUID())); assertEquals(4, calls.get());
            status.set(200); Thread.sleep(100);
            assertEquals("ACTIVE", directory.lookup(UUID.randomUUID()).state());
            assertEquals(CircuitBreaker.State.CLOSED, directory.state());
        }
    }
    @Test void bulkheadRejectsWithoutWaitingAndReleasesPermit() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var directory = new DirectoryHttp(origin(), Duration.ofSeconds(2), 1); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> directory.lookup(UUID.randomUUID()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertThrows(LegacyUnavailable.class, () -> directory.lookup(UUID.randomUUID()));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500);
            assertEquals(1, calls.get()); release.countDown(); assertEquals("ACTIVE", first.get(2, TimeUnit.SECONDS).state());
            entered = null; assertEquals("ACTIVE", directory.lookup(UUID.randomUUID()).state());
        } finally { release.countDown(); }
    }
    @Test void deadlineIncludesStalledChunkedBodyAndNeverReturnsFallback() {
        delay.set(800);
        try (var directory = new DirectoryHttp(origin(), Duration.ofMillis(200), 1)) {
            long start = System.nanoTime(); assertThrows(LegacyUnavailable.class, () -> directory.lookup(UUID.randomUUID()));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 600);
        }
    }
    @Test void oversizedAndFinancialPayloadsRejected() {
        try (var directory = new DirectoryHttp(origin(), Duration.ofSeconds(1), 1)) {
            body = "x".repeat(5000); assertThrows(LegacyUnavailable.class, () -> directory.lookup(UUID.randomUUID()));
            body = "{\"externalReference\":\"SYNTHETIC_1\",\"state\":\"ACTIVE\",\"balanceCents\":900}";
            assertThrows(LegacyUnavailable.class, () -> directory.lookup(UUID.randomUUID()));
        }
    }
    @Test void nonlocalEndpointsAndInvalidLimitsRejected() {
        for (String endpoint : java.util.List.of("https://127.0.0.1:8080", "http://localhost:8080", "http://169.254.169.254:80", "http://127.0.0.1:8080/path", "http://user@127.0.0.1:8080")) {
            assertThrows(IllegalArgumentException.class, () -> new DirectoryHttp(URI.create(endpoint), Duration.ofSeconds(1), 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new DirectoryHttp(origin(), Duration.ofSeconds(6), 1));
        assertThrows(IllegalArgumentException.class, () -> new DirectoryHttp(origin(), Duration.ofSeconds(1), 0));
    }
}
