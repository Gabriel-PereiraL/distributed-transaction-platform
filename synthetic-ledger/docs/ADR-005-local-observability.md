# ADR-005 — explicit local observability and bounded transport context

Status: accepted and validated in F5,13/10/2026;136 clean-reactor tests plus plain verification, contracts and local signal/alert checks.

API, worker and Activity use Boot's managed OpenTelemetry starter. Network export is disabled by default; the `observe` profile opts into traces/logs over OTLP HTTP and Micrometer OTLP metrics. Collector routes these signals to Tempo, Loki and Prometheus; Grafana provisions three datasources and a four-panel dashboard. All published host ports bind to127.0.0.1; storage is ephemeral and short-lived. Images are fixed by digest, not floating tags. This is not a production topology.

Canonical admission stores HTTP trace context. A producer span injects a bounded55-byte W3C traceparent; posting attaches an explicit detached consumer parent and records its context in the new canonical terminal event. Terminal publication therefore continues the same trace after the SQL transaction ends. SQL payload validation and financial policy remain authoritative; trace headers never select tenant, account, amount or event. Activity independently extracts bounded transport context and restores thread state on completion or failure. No baggage is propagated. Missing or invalid context starts a fresh trace.

Exported transport errors carry only a fixed message and exception class. Selected SDK logs contain fixed codes plus trace context; no wholesale framework/logback bridge is installed. Console ECS logging is a separate signal. OTLP log emission failure cannot change publication bookkeeping. Sampling is100% only for the bounded local profile; retry publications create separate spans within the admission trace. Duplicate delivery is still possible, and telemetry does not confer exactly-once behavior.

Validation layers are distinct: pure context/scope/privacy tests; real PostgreSQL/Kafka trace continuation and balance assertions; fresh Collector ingestion with SDK/Micrometer and real Boot `observe` profile binding; synthetic compose pipeline queries; dashboard provisioning; Prometheus rule tests and a real Collector stop/start alert drill. Final regression and contracts are required before declaring the gate complete. The synthetic pipeline smoke does not prove that all three financial executables and secure broker run together; the combined secure demonstration remains F8.

## Limits

No HA, production retention, external notifications, telemetry tenant access policy or performance sizing is claimed. Four panels do not constitute a complete SLO programme. The delivery alert detects an unconfirmed attempt, not a failed financial transfer; the scrape alert detects telemetry outage, not API outage. Collector buffers and drops are bounded; telemetry can be lost during the drill. Application database/broker configuration remains the explicit existing local configuration. No AWS resources or Terraform apply.

Primary references: [Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html), [Boot observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html), [Collector configuration](https://opentelemetry.io/docs/collector/configuration/), [Loki native OTLP](https://grafana.com/docs/loki/latest/send-data/otel/native_otlp_vs_loki_exporter/).
