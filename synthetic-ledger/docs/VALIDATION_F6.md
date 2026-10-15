# F6 — local performance gate

F6 is validated locally within the limits below. The matched comparison, controlled backlog recovery, current clean regression and contracts passed. Failures remain preserved; this gate does not imply production capacity or resolution of the separate13/10 SQL cancellation.

- Matched hot-source burst: nine repetitions,2 arrivals/second for10 seconds,203 transfers, no dropped iterations or unexpected HTTP responses. One API/worker500ms, one API/worker20ms, then two independent API JVMs/two worker JVMs20ms. All final financial/projection invariants passed.
- Default500ms publisher under the2/5/10 ramp:172 admissions returned202, but121 transfers were not POSTED after90 seconds. Final invariants did not complete for that unsuccessful run.
- Repeated ramp with explicit recovery:133-transfer backlog; graceful worker restart with20ms publishing;176 transfers ultimately POSTED/published/projected and final financial invariants passed. Recovery52.85s includes startup. No abrupt-crash or sustained-capacity claim.
- Clean integration reactor:136 tests (48 unit/slice and88 integration), no failures/errors/skips. Separate plainverify48 passed; persisted HTTP/event artifacts passed contract validation.
- Query plans, sampled database waits, per-process generatorCPU/hostCPU and safe JFR summaries preserved. First API only; matched heap is unavailable because no heap summary event occurred. Recovery heap samples are not an RSS/peak-memory measurement.

[Validation counts and source fingerprint](evidence/f6-validation.json), [matched measurements](BENCHMARK_F6_MATCHED.md), [failed drain](BENCHMARK_F6_SATURATION.md), [recovery](BENCHMARK_F6_RECOVERY.md), [reproduction procedure](RUNBOOK_F6.md).

The demonstrated envelope is a short synthetic burst on this shared host, followed by drain, with the concrete rates/times recorded above. It is not a production SLO. The one-event-per500ms publisher has an ideal upper bound near one fully published transfer/second before I/O; its configuration was not changed. Faster polling may raise idle SQL cost, which this experiment does not quantify. Cache was not added: PostgreSQL remains authoritative and Redis remains mutation quota.

Cases share warmed data and execute in fixed order. Independent API JVMs are demonstrated, but the2-arrivals/second experiment does not establish horizontal-scaling gain. Default publisher overload is documented; the separate13/10 SQL cancellation remains an unresolved diagnostic observation, not a claimed fix. Real Keycloak/TLS/SASL/grants are covered by regression tests, while this performance fixture uses a test RSA issuer and plaintext local Kafka. Secure joint demonstration belongs to F8. No AWS/cloud/deploy/Terraformapply occurred.
