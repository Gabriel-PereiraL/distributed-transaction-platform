# ADR-003 — Private Activity projection and distributed quota

Date: 2026-10-11. F3–F8 were explicitly approved for local work. This document records F3 implementation decisions; final executed evidence belongs in VALIDATION_F3.md.

## Responsibility boundaries

Activity depends on `event-contracts`, Spring MVC/Security, MongoDB and Kafka. It has no ledger domain/application/adapter/JPA dependency or SQL datasource. PostgreSQL remains the financial authority. The read service returns derived receipts, bounded cursor pages and separate event history only for the authenticated tenant and initiating subject. Incoming-credit views for recipient owners and broad tenant auditing are not implemented. Its audience is `dtp-activity` and scope `activity:read`, separate from ledger mutation permissions.

The decoder validates supported v1 shapes, integer BRL amounts, UUIDs, envelope/payload identity, state/version/time consistency and bounded metadata. Older events without the optional traceparent decode with a null default. Canonical fingerprints use decoded typed facts, so JSON property order/whitespace do not create false duplicate conflicts. It cannot verify facts against SQL, because Activity must not depend on the ledger store; producer trust/ACLs are a later security gate.

## Two Mongo writes with recovery

History is unique by tenant/event and tenant/transfer/version. A duplicate is a no-op only when its stored fingerprint agrees; a conflicting identity/version is quarantinable. Snapshot updates use an atomic single-document predicate for a strictly newer version, immutable receipt identity and a unique key. Existing same/newer versions are safe no-ops. One retry handles the initial-upsert race where an older version was inserted concurrently. Integer BSON representations are compared by numeric value.

History insertion and snapshot advancement are deliberately separate writes. A failure between them leaves the history durable but the snapshot missing/stale. Redelivery runs both operations even when history is duplicate. The Kafka offset follows both writes. A terminal v2 snapshot may arrive before v1, which later fills history without regressing the receipt. Read history/snapshot are not transactionally co-visible; a receipt can lag behind a newly inserted fact. No financial balances are computed from these collections. [MongoDB atomicity](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/), [unique indexes](https://www.mongodb.com/docs/manual/core/index-unique/).

## Replay and availability

Activity uses an `activity-` consumer group namespace and its own DLT. A rebuild command requires `DTP_ALLOW_REBUILD=true`, a target different from the live prefix, an empty destination checked before consumption starts, and a freshly generated group. It does not reset posting offsets or update existing ledger data. Once caught up, a read-only instance can be configured to the new collection prefix; no automatic switch promises atomicity across deployments. Retention limits apply: replay cannot reconstruct events older than the Kafka log without a future canonical export.

The managed recoverer retains bounded bytes/hash/origin, confirms DLT publication before original acknowledgment, and pauses/resumes the failed partition when quarantine is unavailable. Mongo selection/connect/read budgets are three seconds. Mongo loss yields controlled 503 for reads and retry/quarantine for the projector; no fallback invents financial state. Whole ledger-to-Activity E2E, security ACLs and recovery monitoring are later gates.

## Redis quota

The HTTP admission edge checks one global anchored-window bucket per trusted tenant/client and operation, outside the financial SQL transaction. A single-key Lua script increments and assigns expiration atomically on first use; rejected requests do not refresh its TTL. Two API instances share the same counter. Bucket keys hash the validated identity; Redis stores no balance, reservation or authorization decision. Both idempotent replays and rejected business requests reaching the edge count as attempts, rather than counting only new transfers.

Exceeding the configured limit returns 429 with Retry-After. Redis timeout/failure returns sanitized 503 before opening admission; GET authorization/SQL reads do not depend on quota. Metadata/read timeouts are bounded. Counter response uncertainty can spend auxiliary quota despite no financial admission, which is an intentional fail-closed tradeoff. Redis failure/restart can reset quota state; strict permanent abuse accounting is not claimed. The explicit quota-off switch is for isolated laboratory/old foundation tests. Cache is deferred until a measured benefit exists. [Redis counter/expiration pattern](https://redis.io/docs/latest/commands/incr/).

## Limits

Local disposable tests are not evidence of production broker/Mongo/Redis HA, full OIDC/operator roles, TLS/ACLs or throughput. No cloud resources/deploy/apply or Fulfillment Hub changes are authorized. Security, observability, benchmarking and final packaging remain subsequent approved gates.
