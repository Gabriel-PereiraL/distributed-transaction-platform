package dev.dtp.activity;

import static org.junit.jupiter.api.Assertions.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class ActivityTracingTest {
    @Test void consumerAttachesTransportParentAndAlwaysClosesScope() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new ActivityTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var headers = new RecordHeaders();
            headers.add("traceparent", "00-12345678901234567890123456789012-1234567890123456-01".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            tracing.consume(headers, () -> assertEquals("12345678901234567890123456789012", Span.current().getSpanContext().getTraceId()));
            assertFalse(Span.current().getSpanContext().isValid());
            var span = exporter.getFinishedSpanItems().getFirst();
            assertEquals("1234567890123456", span.getParentSpanId());
            assertTrue(span.getAttributes().isEmpty());
        }
    }
    @Test void oversizedHeaderStartsRootAndFailureExportsNoExceptionMessage() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new ActivityTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var headers = new RecordHeaders(); headers.add("traceparent", new byte[65536]);
            assertThrows(IllegalStateException.class, () -> tracing.consume(headers, () -> { throw new IllegalStateException("SECRET-CREDENTIAL"); }));
            assertFalse(Span.current().getSpanContext().isValid());
            var span = exporter.getFinishedSpanItems().getFirst();
            assertEquals("0000000000000000", span.getParentSpanId());
            assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
            assertTrue(span.getEvents().isEmpty());
            assertFalse(span.toString().contains("SECRET-CREDENTIAL"));
        }
    }
}
