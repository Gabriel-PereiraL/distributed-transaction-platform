# F6 â€” local ramp recovery

The2/5/10 arrivals/second ramp left **133 accepted transfers** pending with the500ms publisher. The fixture then gracefully stopped that worker and started one with20ms publishing. Kafka group readiness and partition assignment were verified. All **176 synthetic transfers** were POSTED, all outbox deliveries PUBLISHED and all Mongo projections POSTED after **52.85 seconds**, including restart/startup time.

Financial invariants passed after recovery: no holds/reservations, no negative customer balances, one posting journal per transfer, balanced entries and balances equal to journal entries. Activity HTTP reads also passed. The90-second drain gate and10-second financial transaction timeout were unchanged. No production configuration changed;20ms is a fixture override.

[Safe recovery evidence](evidence/f6-recovery-20261014/recovery.json), [first API JFR summary](evidence/f6-recovery-20261014/jfr-summary.json), [matched comparison](BENCHMARK_F6_MATCHED.md), [preserved failed default drain](BENCHMARK_F6_SATURATION.md).

This validates recovery of synthetic backlog after a graceful worker restart with faster publishing. It does not reproduce an abrupt crash, demonstrate sustained capacity, establish isolation or prove the cause of the separate13/10 SQL cancellation. That failure remains unresolved and explicitly retained. F6 local gate passed with clean136/plain48/contracts; [final gate record](VALIDATION_F6.md).
