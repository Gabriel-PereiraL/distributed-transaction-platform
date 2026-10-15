# F5 local observability

The F5 gate passed13/10/2026; see [validation](VALIDATION_F5.md). Configuration alone is not evidence. No cloud account or remote notification is used.

Start from `synthetic-ledger` with Docker running:

```powershell
$env:DTP_GRAFANA_PASSWORD = '<choose a local password>'
docker compose -f infra/observability/compose.yaml up -d
```

Start each application with its usual database/broker/identity configuration and add `SPRING_PROFILES_ACTIVE=observe` (or `secure,observe` for its secure broker profile). The default exporter targets are `http://127.0.0.1:14318/v1/traces`, `/v1/logs` and `/v1/metrics`. Override them with `DTP_OTLP_ENDPOINT`, `DTP_OTLP_LOGS_ENDPOINT` and `DTP_OTLP_METRICS_ENDPOINT`. From another container, point them at the reachable Collector service; host loopback is not container loopback.

Grafana: http://127.0.0.1:13000 (admin and the chosen password); Prometheus: http://127.0.0.1:19090. Provisioned dashboard `dtp-local` shows outbox confirmed/unconfirmed attempts, projection records and Collector scrape. Tempo and Loki query ports are localhost13200 and13100. Missing business series before first activity are expected; absence is not zero.

Metrics use Micrometer OTLP, traces use the Boot-managed OpenTelemetry SDK, and selected logs use the SDK API with constant bodies. No logback bridge forwards all framework logs. Logs exported by the worker contain a fixed publication code with trace/span context, never tokens, payloads, account/tenant identifiers or exception messages. Console ECS logging remains separate. Sampling is100% for this bounded laboratory profile; all network exporters are disabled outside `observe`.

## Outbox

`DtpOutboxDeliveryUnconfirmed` means a publication attempt lacks confirmation. It does not prove financial failure: the broker may have accepted a timed-out send. Inspect the durable delivery status with the approved operator/read-only paths. Preserve canonical event IDs; never reset financial balances or consumer offsets. Repair broker/TLS/ACL connectivity, then allow bounded retry or use the audited same-event requeue. Verify terminal state and journal invariants before closing the incident.

## Collector

`DtpCollectorUnavailable` means Prometheus cannot scrape Collector. Inspect only this compose project's status/logs; repair the telemetry pipeline without restarting unrelated projects. Stop Collector for more than10s, observe the alert firing, start it again and observe resolution. This drill loses telemetry while stopped and does not validate financial-service availability.

Stop only the observation project:

```powershell
docker compose -f infra/observability/compose.yaml down
```

Ephemeral lab storage and short retention are deliberate. No HA, production retention, tenant isolation or external incident delivery is claimed. Images are versioned; exact digests and results must be recorded when validation completes.

Primary references: [Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html), [Boot observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html), [Collector configuration](https://opentelemetry.io/docs/collector/configuration/), [Loki native OTLP](https://grafana.com/docs/loki/latest/send-data/otel/native_otlp_vs_loki_exporter/).
