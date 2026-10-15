package dev.dtp.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Real SDK and Micrometer OTLP exports into a fresh Collector; no external account. */
@SuppressWarnings("try") // Scope is intentionally used only for its lifetime effect.
class CollectorSignalsIT {
    @Test void collectorIngestsRealTraceMetricAndCorrelatedBoundedLog() {
        try (var collector = new GenericContainer<>("otel/opentelemetry-collector-contrib@sha256:39923a8e431bd1f57be82411999d389fcfe40857492e4365456d97a4c1f74be6")
                .withExposedPorts(4318)
                .withCopyFileToContainer(MountableFile.forClasspathResource("collector-test.yaml"), "/etc/otelcol-contrib/config.yaml")
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(new byte[0], 0666), "/tmp/dtp-traces.json")
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(new byte[0], 0666), "/tmp/dtp-metrics.json")
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(new byte[0], 0666), "/tmp/dtp-logs.json")
                .waitingFor(Wait.forHttp("/v1/traces").forStatusCode(405).forStatusCode(415))) {
            collector.start();
            String endpoint = "http://" + collector.getHost() + ":" + collector.getMappedPort(4318);
            var traceExporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint + "/v1/traces").setTimeout(Duration.ofSeconds(5)).build();
            var logExporter = OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs").setTimeout(Duration.ofSeconds(5)).build();
            OtlpConfig config = new OtlpConfig() {
                @Override public String get(String key) { return null; }
                @Override public String url() { return endpoint + "/v1/metrics"; }
                @Override public Duration step() { return Duration.ofHours(1); }
            };
            try (var traces = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(traceExporter)).build();
                 var logs = SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter)).build();
                 var metrics = new TestRegistry(config)) {
                var span = traces.get("dtp.test").spanBuilder("dtp.collector.trace").startSpan();
                try (var scope = span.makeCurrent()) {
                    logs.get("dtp.test").logRecordBuilder().setContext(io.opentelemetry.context.Context.current())
                            .setBody("OUTBOX_PUBLICATION_CONFIRMED").emit();
                }
                span.end();
                metrics.counter("dtp.collector.accepted").increment(3);
                metrics.flush();
                assertThat(traces.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
                assertThat(logs.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(file(collector, "/tmp/dtp-traces.json")).contains("dtp.collector.trace", span.getSpanContext().getTraceId());
                    assertThat(file(collector, "/tmp/dtp-logs.json")).contains("OUTBOX_PUBLICATION_CONFIRMED", span.getSpanContext().getTraceId());
                    assertThat(file(collector, "/tmp/dtp-metrics.json")).contains("dtp.collector.accepted");
                });
            }
            // Verify production Boot profile binding, not just standalone SDK exporters.
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(Probe.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("observe")
                    .run("--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
                            "--management.opentelemetry.tracing.export.otlp.endpoint=" + endpoint + "/v1/traces",
                            "--management.opentelemetry.logging.export.otlp.endpoint=" + endpoint + "/v1/logs",
                            "--management.otlp.metrics.export.url=" + endpoint + "/v1/metrics")) {
                var telemetry = context.getBean(io.opentelemetry.api.OpenTelemetry.class);
                var span = telemetry.getTracer("dtp.boot.probe").spanBuilder("dtp.boot.profile.trace").startSpan();
                try (var scope = span.makeCurrent()) {
                    telemetry.getLogsBridge().get("dtp.boot.probe").logRecordBuilder()
                            .setContext(io.opentelemetry.context.Context.current()).setBody("BOOT_PROFILE_LOG").emit();
                }
                span.end();
                context.getBean(io.micrometer.core.instrument.MeterRegistry.class).counter("dtp.boot.profile.counter").increment();
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(file(collector, "/tmp/dtp-traces.json")).contains("dtp.boot.profile.trace");
                    assertThat(file(collector, "/tmp/dtp-logs.json")).contains("BOOT_PROFILE_LOG");
                    assertThat(file(collector, "/tmp/dtp-metrics.json")).contains("dtp.boot.profile.counter");
                });
            }
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class Probe { }
    private static String file(GenericContainer<?> collector, String path) {
        return collector.copyFileFromContainer(path, stream -> {
            byte[] bytes = stream.readNBytes(131073);
            assertThat(bytes.length).isLessThanOrEqualTo(131072);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        });
    }
    private static final class TestRegistry extends OtlpMeterRegistry implements AutoCloseable {
        TestRegistry(OtlpConfig config) { super(config, Clock.SYSTEM); }
        void flush() { publish(); }
    }
}
