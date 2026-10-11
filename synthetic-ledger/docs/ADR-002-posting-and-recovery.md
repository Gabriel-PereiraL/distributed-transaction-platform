# ADR-002 — Atomic posting and at-least-once Kafka delivery

Date: 2026-10-11. Scope: F2 local, explicitly approved. Synthetic BRL only.

## Decision

The API and new worker share the PostgreSQL ledger bounded context. Admission remains unchanged: `202 ACCEPTED` persists a hold and an immutable v1 event. POST replay always returns that original admission, even after terminalization; GET returns the current `ACCEPTED`, `POSTED` or `REJECTED` state.

Posting locks a canonical transfer, then its customer accounts in PostgreSQL UUID order. It reads the immutable accepted instruction, applies its persisted `synthetic-v1` policy, and commits journal entries, balances, hold removal, final state, dedup record, terminal event and outbox together. `synthetic-v1` posts at most 500,000 BRL cents; greater values or an account inactive at processing time are rejected with reservation release. This is a fictitious demonstration rule, not compliance or bank risk scoring. Unsupported rule versions are technical failures, never silently decided by a new policy. Terminal states cannot regress. Reversal is not implemented.

The worker accepts only bounded supported JSON records whose identity, tenant, key and complete envelope match the SQL canonical event. Incoming message amounts never determine balances. It also validates terminal records against canonical SQL before acknowledging them. This DB dependency is appropriate for this worker, which owns ledger processing; future Activity must use independently validated contracts and cannot access this database.

## Delivery and concurrency

The publisher claims one due event in a short SQL transaction using `FOR UPDATE SKIP LOCKED`, a unique fencing token and a 30-second database-clock lease. A later aggregate version is ineligible until earlier versions are marked published. Kafka publication and its confirmation happen outside SQL transactions. Finishing/retrying requires the current token and a live lease. No renewal is needed for the configured per-attempt budget (3-second metadata block, 8-second producer delivery, bounded confirmation wait), below the 30-second lease. Arbitrary process pauses can still publish late duplicates. SQL idempotency is mandatory.

`acks=all` and producer idempotence do not provide exactly-once SQL/Kafka behavior. A crash after broker confirmation but before saving publication can duplicate the event. A crash after financial commit but before consumer offset commit produces a safe no-op. Publisher retries use bounded exponential backoff plus jitter and persist their next attempt; they do not discard durable events after an arbitrary attempt count.

Consumer record acknowledgments occur after the processing transaction returns. Retry stays on the original partition: two retries, short exponential backoff with jitter; a 10-second SQL transaction budget, one record per poll and 120-second poll interval. Malformed/unsupported records go directly to quarantine. After retry exhaustion, technical failures go to quarantine while the transfer remains ACCEPTED. A fresh bounded DLT envelope includes original topic/partition/offset, SHA-256 and capped bytes, without copying untrusted headers or exception text. The recoverer returns only after DLT broker confirmation. Unconfirmed DLT publication throws, so the original offset cannot advance; the affected partition is paused for 1–1.5 seconds using a separate resume scheduler, preventing a tight recovery loop while polling continues. DLT publication itself may duplicate.

One local broker, three partitions and replication factor one are laboratory choices. They do not demonstrate broker fault tolerance. Seven-day topic retention is finite; SQL canonical events are retained. SASL/TLS/ACLs, independent Activity groups, distributed tracing, exports and rebalance/load testing remain later gates.

## Recovery and repair

An opt-in reconciliation job selects old ACCEPTED instructions and invokes the same idempotent posting case. It does not expire holds by clock. Separate recovery counters expose terminalizations/failures; operator must inspect backlog and its cause. Defaults keep recovery disabled so Kafka delivery failures are not silently masked. Concurrent recovery and listener processing are safe through transfer/account locks.

The local `requeue` command requires `DTP_ALLOW_REQUEUE=true`, tenant, transfer, bounded operator and reason. It audits REQUESTED before sending original canonical bytes, then CONFIRMED or UNCONFIRMED. A crash between send and outcome leaves explicit uncertainty. Already-terminal transfers produce an audited no-op. A concurrent terminalization may race with the initial status read, but any ensuing duplicate is harmless. No posting offset reset or new event identity is used. The command refuses to run inside a SQL transaction. Local CLI identity is a declared operator label, not production RBAC; complete OIDC/operator authorization belongs to F4.

## SQL defenses

V2 is append-only and preserves V1's checksum. Deferred commit guards require exact two-entry posting journals, matching active holds for ACCEPTED and no holds for terminal transfers, canonical processed identity, terminal event/outbox and materialized account balances/reservations. Entries/events/journals/audit/dedup and account/transfer identities are immutable. Funding remains an explicit synthetic journal. Database role least privilege and migration rollback policy remain unimplemented; these guards are not a substitute for runtime role design.

## Evidence boundaries

Integration tests use disposable PostgreSQL and Apache Kafka containers, real HTTP/JWT in the API suite and real broker offsets in worker tests. Fault injection models selected before/after-commit windows and unavailable bootstrap/DLT confirmations. A test-only child JVM additionally claims an outbox record, is forcibly terminated, and recovers after natural lease expiry. The test result must be checked in VALIDATION_F2.md; this does not prove whole worker process crashes at every commit boundary, broker cluster failover, production throughput, security ACLs or exactly-once behavior.

## References checked for the selected library versions

- [Spring Kafka 4.1.1 exception handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html): record acknowledgment, bounded retry and recoverer failure semantics.
- [ListenerContainerPauseService 4.1.1](https://docs.spring.io/spring-kafka/docs/4.1.1/api/org/springframework/kafka/listener/ListenerContainerPauseService.html): timed partition pause/resume through a managed scheduler.
- [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/): official Apache Kafka container support. The tests pin the downloaded broker digest rather than a moving image tag.
