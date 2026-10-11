package dev.dtp.worker;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("try") // Scopes deliberately exist only for their lifetime effect.
class KafkaTracingTest {
    @Test void detachedW3cParentAndScopeClosePreserveTraceAcrossThreads() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build(); var tracing = new KafkaTracing(sdk);
            var admission = sdk.getTracer("test").spanBuilder("admission").startSpan(); String rootId = admission.getSpanContext().getSpanId(); String traceId = admission.getSpanContext().getTraceId();
            String parent;
            try (var scope = admission.makeCurrent()) { parent = KafkaTracing.currentTraceparent(); }
            admission.end();
            var headers = new RecordHeaders();
            try (var publish = tracing.start("publish", SpanKind.PRODUCER, parent)) { publish.inject(headers); }
            assertFalse(Span.current().getSpanContext().isValid());
            executor.submit(() -> {
                try (var consume = tracing.start("consume", SpanKind.CONSUMER, KafkaTracing.parent(headers, null))) {
                    assertEquals(traceId, Span.current().getSpanContext().getTraceId());
                }
                assertFalse(Span.current().getSpanContext().isValid());
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            var spans = exporter.getFinishedSpanItems(); assertEquals(3, spans.size());
            var producer = spans.stream().filter(s -> s.getName().equals("publish")).findFirst().orElseThrow();
            var consumer = spans.stream().filter(s -> s.getName().equals("consume")).findFirst().orElseThrow();
            assertEquals(rootId, producer.getParentSpanId()); assertEquals(producer.getSpanId(), consumer.getParentSpanId());
            assertEquals(traceId, consumer.getTraceId());
        }
    }
    @Test void oversizedCarrierCannotAttachParentAndZeroIdentityStartsFreshTrace() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new KafkaTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var headers = new RecordHeaders(); headers.add("traceparent", new byte[65536]);
            assertNull(KafkaTracing.parent(headers, null));
            try (var scope = tracing.start("fresh", SpanKind.CONSUMER, "00-" + "0".repeat(32) + "-" + "0".repeat(16) + "-01")) {
                assertTrue(Span.current().getSpanContext().isValid());
            }
            assertEquals("0000000000000000", exporter.getFinishedSpanItems().getFirst().getParentSpanId());
        }
    }
    @Test void failuresExportOnlyBoundedTypeAndNeverExceptionMessage() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new KafkaTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            try (var span = tracing.start("failure", SpanKind.CONSUMER, null)) { span.failed(new IllegalStateException("SECRET-NOT-EXPORTED")); }
            var finished = exporter.getFinishedSpanItems().getFirst(); assertEquals(StatusCode.ERROR, finished.getStatus().getStatusCode());
            assertTrue(finished.getEvents().isEmpty()); assertFalse(finished.toString().contains("SECRET-NOT-EXPORTED"));
        }
    }
}
