# F6 â€” matched local benchmark

Nine repetitions using the same hot account, arrival rate 2 per second for ten seconds. Two API JVMs and two worker JVMs in the final case. Financial invariants and Activity reads passed.

| Case | Rep | Transfers | p95 ms | p99 ms | Dropped | Drain s | k6 CPU cores max | Host CPU mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline-500ms | 0 | 21 | 100.95 | 104.46 | 0 | 12.86 | 0.05 | 75.5% |
| baseline-500ms | 1 | 21 | 88.01 | 94.40 | 0 | 12.87 | 0.06 | 61.6% |
| baseline-500ms | 2 | 21 | 54.98 | 67.30 | 0 | 12.35 | 0.08 | 61.0% |
| tuned-20ms | 0 | 21 | 64.25 | 64.37 | 0 | 0.53 | 0.06 | 66.4% |
| tuned-20ms | 1 | 21 | 73.81 | 75.90 | 0 | 0.53 | 0.08 | 64.4% |
| tuned-20ms | 2 | 21 | 87.05 | 91.06 | 0 | 0.52 | 0.05 | 70.7% |
| two-api-two-worker | 0 | 21 | 69.22 | 106.45 | 0 | 0.54 | 0.05 | 69.4% |
| two-api-two-worker | 1 | 20 | 88.66 | 89.25 | 0 | 0.53 | 0.08 | 80.5% |
| two-api-two-worker | 2 | 21 | 74.33 | 76.52 | 0 | 0.52 | 0.06 | 71.7% |

[Safe evidence](evidence/f6-20261014matched2/summary.json).

Matched hot source, rate 2 per second, ten seconds, three repetitions; second API is a separate JVM. Ordered cases share warmed disposable data, not randomized independent trials. Prior 2/5/10 run exceeded baseline drain budget and is preserved separately.

Shared host; 100-account lab dataset, test RSA issuer, plaintext local broker; no production capacity/SLO claim. JFR covers first API JVM, not child API/workers. Generator CPU sampled separately; no CPU affinity/isolation.

CPU cores is sampled k6 process CPU time divided by elapsed wall time; host CPU is sampled separately. Low generator CPU alone does not prove scheduling/network headroom. Dropped iterations mean the scheduled workload was not fully delivered. Completion rate includes load, observer overhead and subsequent drain; it is not an isolated worker throughput measurement.

Previous failure remains preserved and its root cause unproved. No financial timeout or production behavior changed. F6 local gate passed; [counts and limits](VALIDATION_F6.md).
