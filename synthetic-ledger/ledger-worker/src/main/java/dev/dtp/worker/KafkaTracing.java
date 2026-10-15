package dev.dtp.worker;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded W3C carrier, detached parent and explicit scope lifecycle. No baggage or payload attributes. */
@Component
public final class KafkaTracing {
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final io.opentelemetry.api.logs.Logger logger;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };
    @Autowired KafkaTracing(ObjectProvider<OpenTelemetry> telemetry) { this(telemetry.getIfAvailable(OpenTelemetry::noop)); }
    public KafkaTracing(OpenTelemetry telemetry) {
        tracer = telemetry.getTracer("dtp.transport");
        logger = telemetry.getLogsBridge().get("dtp.transport");
    }
    public void published() {
        try {
            logger.logRecordBuilder().setContext(Context.current()).setSeverity(io.opentelemetry.api.logs.Severity.INFO)
                    .setBody("OUTBOX_PUBLICATION_CONFIRMED").emit();
        } catch (RuntimeException unavailable) { /* Telemetry must not alter delivery bookkeeping. */ }
    }
    public TraceRun start(String name, SpanKind kind, String parent) {
        Map<String, String> carrier = valid(parent) ? Map.of("traceparent", parent) : Map.of();
        Context context = W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, GETTER);
        Span span = tracer.spanBuilder(name).setSpanKind(kind).setParent(context).startSpan();
        return new TraceRun(span, span.makeCurrent());
    }
    public static String parent(Headers headers, String fallback) {
        var header = headers.lastHeader("traceparent");
        if (header != null && header.value() != null && header.value().length == 55) {
            String value = new String(header.value(), StandardCharsets.US_ASCII);
            if (valid(value)) return value;
        }
        return valid(fallback) ? fallback : null;
    }
    public static String currentTraceparent() {
        var context = Span.current().getSpanContext();
        if (!context.isValid()) return null;
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + (context.isSampled() ? "01" : "00");
    }
    private static boolean valid(String value) { return value != null && value.length() == 55 && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"); }
    public static final class TraceRun implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private TraceRun(Span span, Scope scope) { this.span = span; this.scope = scope; }
        public void inject(Headers headers) {
            String parent = currentTraceparent();
            headers.remove("traceparent");
            if (parent != null) headers.add("traceparent", parent.getBytes(StandardCharsets.US_ASCII));
        }
        public void failed(Throwable error) {
            span.setStatus(StatusCode.ERROR, "Transport processing failed.");
            span.setAttribute("error.type", error.getClass().getSimpleName()); // Never export exception message/payload/credentials.
        }
        @Override public void close() { try { scope.close(); } finally { span.end(); } }
    }
}
