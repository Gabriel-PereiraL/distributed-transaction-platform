# F6 diagnostic replay — 14/10/2026

Nine load repetitions and Activity reads passed, including financial invariants. The prior failure did not reproduce; **F6 remains open**.

| Case | Rep | Transfers | p95 ms | Dropped | Drain s | Blocked max | Tx age max s |
|---|---:|---:|---:|---:|---:|---:|---:|
| baseline-500ms | 0 | 21 | 435.20 | 0 | 14.06 | 0 | 0.000 |
| baseline-500ms | 1 | 21 | 212.33 | 0 | 13.04 | 0 | 0.000 |
| baseline-500ms | 2 | 21 | 193.43 | 0 | 13.12 | 0 | 0.000 |
| tuned-20ms | 0 | 169 | 442.27 | 4 | 3.49 | 1 | 0.215 |
| tuned-20ms | 1 | 168 | 529.19 | 4 | 5.21 | 3 | 0.339 |
| tuned-20ms | 2 | 170 | 418.42 | 2 | 6.33 | 2 | 0.357 |
| two-api-two-worker | 0 | 170 | 326.16 | 2 | 3.17 | 2 | 0.191 |
| two-api-two-worker | 1 | 173 | 229.79 | 0 | 3.12 | 1 | 0.174 |
| two-api-two-worker | 2 | 167 | 1026.17 | 5 | 2.58 | 4 | 1.064 |

[Safe metrics, query plans and JFR summary](evidence/f6-diag-20261014/summary.json). Raw JFR, logs, tokens and configuration credentials remain outside Git.

JFR: 363 execution samples, 181.5ms aggregate GC pause, host CPU mean 95.1%. Scope includes API warmups and second context startup; child workers are not profiled.

- Prior failure did not reproduce; no financial timeout was changed.
- Host CPU averaged about95%; this is not an isolated capacity benchmark.
- API instances share one JVM; workload/rates differ between baseline and tuned cases.
- Sampled transaction age is not a direct lock wait duration.
- No independent API process scaling, production SLO or causal speedup claim.

Next: make comparisons equivalent, explicitly wait for both workers, collect generator headroom and repeat on controlled resources. Complete the functional integration/verify gates before closing F6 or advancing F7.
