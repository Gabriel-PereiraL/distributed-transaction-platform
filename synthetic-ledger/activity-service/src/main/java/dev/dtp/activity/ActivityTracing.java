package dev.dtp.activity;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Transport metadata only: no baggage, tenant, account, token or event body. */
@Component
final class ActivityTracing {
    private final io.opentelemetry.api.trace.Tracer tracer;
    @Autowired ActivityTracing(ObjectProvider<OpenTelemetry> telemetry) { this(telemetry.getIfAvailable(OpenTelemetry::noop)); }
    ActivityTracing(OpenTelemetry telemetry) { tracer = telemetry.getTracer("dtp.activity.transport"); }
    @SuppressWarnings("try") // Scope lifetime restores thread context even on failure.
    void consume(Headers headers, Runnable action) {
        var header = headers.lastHeader("traceparent");
        String parent = header != null && header.value() != null && header.value().length == 55
                ? new String(header.value(), StandardCharsets.US_ASCII) : null;
        Map<String, String> carrier = parent != null && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                ? Map.of("traceparent", parent) : Map.of();
        var context = W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, new TextMapGetter<Map<String, String>>() {
            @Override public Iterable<String> keys(Map<String, String> value) { return value.keySet(); }
            @Override public String get(Map<String, String> value, String key) { return value.get(key); }
        });
        var span = tracer.spanBuilder("dtp.activity.consume").setSpanKind(SpanKind.CONSUMER).setParent(context).startSpan();
        try (var scope = span.makeCurrent()) { action.run(); }
        catch (RuntimeException failure) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Projection processing failed.");
            span.setAttribute("error.type", failure.getClass().getSimpleName());
            throw failure;
        } finally { span.end(); }
    }
}
