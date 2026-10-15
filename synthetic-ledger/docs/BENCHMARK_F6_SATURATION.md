# F6 — baseline backlog under the matched ramp

The 14/10/2026 hot-account ramp of 2/5/10 admissions per second failed the 90-second drain gate in the first 500ms publisher case. All 172 HTTP requests returned202; admission p95 was99.69ms, but121 transfers were still not POSTED at timeout. Final financial invariants were not completed. This run is a failure, not a capacity proof.

The publisher sends one event per scheduled tick with a500ms fixed delay, plus processing time. Accepted and Posted each require publishing. This gives an ideal upper bound of about one fully published transfer per second for one publisher before I/O costs, consistent with backlog under this offered load. It does not prove the cause of the separate SQL cancellation on13/10. No production behavior or financial timeout changed.

[Safe failed-run evidence](evidence/f6-saturation-20261014/summary.json). The JFR remains local and the private token fixture was removed. A new comparison at2 admissions/second for10 seconds per repetition retains the90-second drain gate, identical workload and three repetitions per configuration. It measures burst recovery, not sustained2-transfers/second capacity for the default publisher.
