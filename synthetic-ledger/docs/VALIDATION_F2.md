# F2 validation — 2026-10-11

Executed locally on Windows/Docker Desktop. Synthetic laboratory evidence; no real money, cloud deployment or Fulfillment Hub changes.

## Final verification

Command: `./mvnw.cmd -B -ntp -Pintegration clean verify`

**BUILD SUCCESS — 71 tests, zero failures/errors/skips.** Reactor elapsed: 04:09 min. This is verification time on this machine, not an application performance benchmark.

| Suite | Executed | Scope |
|---|---:|---|
| DomainTest | 13 | Money/reservation/identity invariants |
| PostingPolicyTest | 3 | Exact synthetic limit, inactive accounts and unsupported persisted rule |
| AdmissionTest | 4 | Admission ports and idempotency/error branches |
| ArchitectureTest | 2 | Framework independence/dependency direction |
| FoundationIT | 31 | Real HTTP/RS256/JWKS/PostgreSQL; admission, posting/rejection, concurrency, rollback and migrations |
| KafkaPostingIT | 18 | Real PostgreSQL/Kafka records/offsets; outbox, dedup, retries, DLT, recovery, requeue and process death |

Machine summary: [f2-validation.json](evidence/f2-validation.json). JDK Temurin 21.0.12.1, Maven 3.9.16, Spring Boot/Spring Kafka 4.1.1, Kafka client 4.2.1, broker 4.2.0, PostgreSQL 17.6, Testcontainers 2.0.5. Container digests are fixed in the integration fixtures. Tests use isolated disposable databases, random ports and unique Kafka topics/groups, no external datasource or reuse.

## Financial evidence

- Admission still persists key, reservation, hold, canonical event and outbox in one commit. Twenty same-key requests produce one admission; competing requests cannot reserve the same last funds.
- Posting persists exact balanced double entry, materialized balances, hold release, terminal state, canonical dedup and terminal event/outbox together. Twenty concurrent deliveries produce one journal. Admission and posting on shared accounts serialize without losing reservations.
- Synthetic policy rejection releases the reservation with unchanged balances; inactive account after admission also rejects. GET returns current status, while POST replay remains the original ACCEPTED receipt.
- Injected terminal-event failure rolls back journal/balance/hold/state/dedup; retry can subsequently post. Unknown canonical identity/tenant cannot move funds.
- Direct SQL guards reject missing terminal evidence, extending a posted journal, premature dedup, mutating terminal identity/audit and NULL rejection reason. The last assertion specifically guards PostgreSQL CHECK's three-valued NULL behavior.
- V1-to-V2 migration was applied to an existing V1 admission: V1 checksum stays unchanged and the instruction can post after migration.

## Delivery and failure evidence

- Real broker publication produces accepted version 1 then terminal version 2 under one tenant/transfer key. Actual posting offsets advance after the SQL effect.
- Broker acceptance without saving publication state produces duplicate bytes/eventId; one financial effect survives. A committed SQL effect delivered later through Kafka remains a no-op. These selected commit windows are modeled deliberately, not described as whole-process kills.
- Sixteen concurrent publishers claim sixteen different events. Expired tokens cannot finish/retry a replacement claim. Version 2 cannot be claimed while version 1 awaits confirmation.
- A separate test-only Java process commits a claim, is forcibly killed, and publication resumes after the unmodified database-clock 30-second lease expires. The result is one journal. This is an actual process death in the claim window; no production crash hooks are installed.
- Unavailable bootstrap metadata leaves delivery durable/retryable. While publication waits, another SQL transaction acquires the outbox row with NOWAIT, showing the broker call does not hold the SQL lock.
- Poison records are quarantined before the original offset can advance. A recoverer fault preserves that offset; restoring DLT publication resumes consumption. Quarantine failures pause the affected partition through a managed timer. Two injected transient SQL failures roll back and retry before a successful third attempt.
- Quarantine bounds original bytes, records digest/origin and omits untrusted headers and exception text. Canonical equality rejects tampered amounts, unknown schema and incorrect partition key.
- Opt-in overdue recovery uses the same posting path; repeated recovery is a no-op. Operator requeue preserves canonical identity, audits request/outcome/uncertainty and terminal no-op, validates tenant/reason and refuses an active SQL transaction.

## Contracts and gates

OpenAPI 3.1 and JSON Schema Draft 2020-12 validate actual SQL accepted/posted/rejected samples and a broker-produced quarantine sample. Identity/tenant/terminal-time consistency and quarantine byte bounds/hash are checked. Command: `python scripts/validate-contracts.py` with isolated requirements.

The packaged worker `requeue` command without its explicit environment gate was invoked and rejected with exit 1 before database credentials/connections. F1's explicit migration gate remains unchanged. Compiler uses release 21 and `-Xlint:all -Werror`; Mockito test agent messages remain distinct from compiler warnings. No aggregate coverage percentage is claimed.

## Limits

One local broker with replication factor one does not demonstrate cluster durability/failover. No production throughput, rebalance-under-load benchmark, multi-host E2E of all future components, OS process kills at every commit boundary, TLS/SASL/ACLs, full Keycloak/operator RBAC, runtime DB least privilege, reversal, Mongo/Redis, collector/export/dashboard/alert drill, Kubernetes/Terraform or cloud operation was validated. Local operator identity is a declared label plus dedicated DB access/explicit flag.

No exactly-once claim: late publication/ack uncertainty can duplicate events; SQL invariants and idempotency preserve financial effects. Kafka retention is finite; SQL retains canonical facts. Later phases require approval. [ADR-002](ADR-002-posting-and-recovery.md) and [runbook](RUNBOOK_F2.md) explain operating decisions and recovery limits.
