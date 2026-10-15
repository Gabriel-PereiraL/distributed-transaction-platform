# Local benchmark runbook

Run only against the disposable Testcontainers fixture, with synthetic accounts. Docker must be available and a verified k6 executable supplied explicitly. Do not run other builds/load generators concurrently. There is no production capacity or SLO claim.

From the project root in PowerShell, with JAVA_HOME pointing to Java21:

```powershell
./mvnw.cmd -B -ntp -DskipTests package
./mvnw.cmd -B -ntp -pl ledger-api -am -Pbenchmark verify `
  '-Dtest=BenchmarkNoUnit' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Ddtp.benchmark.run=UNIQUE-RUN-ID' '-Ddtp.k6=C:/absolute/path/to/k6.exe'
```

The unique run ID must match `[A-Za-z0-9-]{1,32}`. Do not reuse an evidence directory. Package first because the fixture launches worker, Activity and second API executable JARs. Benchmark tests are excluded from the normal integration profile. Archive full logs privately and inspect bounded test/build results.

The comparison uses the same hot source, 2 arrivals/second for10 seconds and three repetitions per configuration: one API/one worker with publisher500ms, one API/one worker with20ms, and two independent API JVMs/two worker JVMs with20ms. Each worker must have a partition assignment in the stable posting group before load starts. Warmup, workload, read queries and financial checks are explicit; cases remain ordered and share warmed data, so do not claim a causal speedup or statistical capacity estimate.

After each run the fixture waits at most90 seconds for all transfers to POST, outbox deliveries to publish and Mongo projections to reach POSTED, then checks zero holds/reservations, nonnegative customer balances, one posting journal per transfer, balanced entries and materialized balances equal to entries. An HTTP202 proves admission only. Failed drain is a failed financial completion gate, even when all HTTP responses are expected. The financial transaction timeout is unchanged.

Artifacts under `ledger-api/target/f6-benchmark-<run>` include k6 metrics, SQL wait categories/counts, sampled generator CPU cores and host CPU fraction, partial results, readiness evidence, SQL plans and a bounded JFR of the first API JVM. JFR does not profile child API/workers. Negative host CPU values indicate unavailable samples. Process CPU samples alone do not prove generator headroom; dropped iterations indicate undelivered scheduled load. Transaction age is not lock wait duration.

The secret-bearing `private-config.json` is deleted in cleanup. Never export it, raw logs, environment, certificate keys or raw JFR to Git. JFR disables initial environment/system properties and system-process events. Publish only the explicitly whitelisted summaries. Preserve unsuccessful runs alongside successes and distinguish which final invariants did not complete.

The default publisher sends one event per500ms tick plus processing time, and each transfer needs Accepted and Posted events. A short burst at2 admissions/second can drain afterward; this does not demonstrate sustained2-transfers/second capacity. The higher2/5/10 ramp exceeded the baseline drain budget; see [the preserved failed run](BENCHMARK_F6_SATURATION.md).

To reproduce the recovery experiment, use another unique run ID and add `'-Ddtp.benchmark.mode=recovery'` to the benchmark command. It runs the higher ramp with500ms publishing, captures backlog, gracefully restarts the worker with20ms publishing, verifies partition assignment, drains within90 seconds and checks final financial invariants. It does not change production configuration or claim abrupt-crash recovery; those scenarios belong to the F2 integration tests.

After benchmark processes exit, run the required regression gates separately:

```powershell
./mvnw.cmd -B -ntp -Pintegration verify
./mvnw.cmd -B -ntp verify
```

Record the actual results in PROGRESSO.md before marking F6 complete. Current results and limitations belong to the evidence report, not to this procedure.
