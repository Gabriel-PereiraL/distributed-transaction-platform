# F6 partial benchmark — gate open

13/10/2026. The benchmark failed; these are partial observations, not a production SLO or a completed F6 gate.

| Run | Expected / unexpected | p95 ms | p99 ms | Dropped |
|---|---:|---:|---:|---:|
| baseline-500ms-0 | 21 / 0 | 166.54 | 209.45 | 0 |
| baseline-500ms-1 | 20 / 0 | 93.68 | 97.18 | 0 |
| baseline-500ms-2 | 20 / 0 | 253.68 | 277.79 | 0 |
| tuned-20ms-0 | 158 / 0 | 1749.89 | 2715.66 | 14 |
| tuned-20ms-1 | 169 / 0 | 625.77 | 764.92 | 3 |
| tuned-20ms-2 | 164 / 0 | 859.73 | 1474.02 | 9 |
| two-api-two-worker-0 | 165 / 0 | 1499.85 | 2179.32 | 8 |
| two-api-two-worker-1 | 129 / 1 | 7096.56 | 8949.70 | 43 |

[Safe raw metrics and limitations](evidence/f6-partial/summary.json). Only selected k6 JSON summaries are included; no tokens, configuration credentials, logs or raw JFR.

- 100 customer accounts, 50 funded source/destination pairs, shared four-logical-CPU 16GB host.
- Baseline uses distributed sources and 500ms publication; tuned scenarios use a hot pair and 20ms. This changes more than one variable.
- Two API contexts share one JVM; two worker processes are separate. No independent API process scaling claim.
- Completed earlier runs drained and passed SQL/Mongo financial checks before successful query runs.
- Last failed run did not reach final financial checks. No full baseline or JFR was saved in that execution.
- SQLSTATE57014 matches query cancellation; transaction timeout is10s. Root cause remains unproven.


Next: preserve diagnostic JFR on failure, verify post-failure financial invariants, make workload and warmup comparisons fair, inspect locks/CPU/query plans and rerun three repetitions within a measured safe envelope. Do not increase financial timeouts to mask the failure. F7/F8 remain pending.
