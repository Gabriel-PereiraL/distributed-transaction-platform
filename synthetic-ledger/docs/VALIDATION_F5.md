# F5 validation — local observability

13/10/2026: full clean integration reactor passed **136 tests**, zero failures/errors/skips. Plain verify and contract validation also passed. Machine-readable suite summaries and code fingerprint: [f5-validation.json](evidence/f5-validation.json). XML properties and raw logs are intentionally excluded.

Validated separately: bounded W3C parent and scope lifecycle; real PostgreSQL/Kafka admission-to-posting-to-terminal producer trace continuity with balance assertions; Activity parent/scope/privacy; fresh Collector ingestion using SDK/Micrometer and Boot `observe` profile exports; compose OTLP smoke queried via Prometheus, Tempo and Loki; Grafana provisioned four panels; both Prometheus rules tested for firing/resolution; real Collector stop/start alert firing and resolution.

The transient CLAIMED test now synchronizes at send entry before checking SQL NOWAIT, then performs the genuine unavailable-broker send. No financial logic or production timeout was changed. Initial Keycloak startup failed during heavy image downloads; only this clean completed run is gate evidence.

Commands: `./mvnw.cmd -B -ntp -Pintegration clean verify`, `./mvnw.cmd -B -ntp verify`, `python scripts/validate-contracts.py`, `python scripts/verify-observability.py`, and digest-pinned `promtool test rules alerts-test.yaml`. Local detailed logs are retained under workspace `work/f5-*`.

These tests are not a performance benchmark or a combined secure three-executable demo. No production SLO, HA, real money, exactly-once delivery or cloud execution is claimed. See [ADR-005](ADR-005-local-observability.md) and [runbook](RUNBOOK_F5.md). F6 remains the next gate.
