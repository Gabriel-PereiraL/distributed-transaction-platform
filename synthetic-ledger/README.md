# DTP — Synthetic Ledger & Transaction Processing

Java 21 / Spring Boot 4.1.1 / PostgreSQL / Kafka / MongoDB / Redis. F1–F4 local: atomic admission, double-entry synthetic posting, outbox/dedup/recovery, private reconstructible Activity projection and global mutation quota. Real local Keycloak/PKCE/RBAC, TLS/SASL/ACL tests, process database grants and bounded read-only legacy integration are covered. Exporters/dashboards, benchmarks and final infrastructure remain subsequent gates.

## What is implemented

- Protected admission/customer/transfer reads plus scoped operator canonical requeue; tenant-bound auditor reads.
- Atomic admission: scoped idempotency identity + hold + reservation + canonical event + pending outbox.
- Ownership and tenant isolation in addition to OAuth scopes; RS256 signature/issuer/audience/time/claim validation through JWKS.
- Domain/application without Spring/JPA dependencies. SQL/JPA adapters compose short local transactions, without network effects.
- PostgreSQL deferred guards for balanced synthetic funding, materialized balance/reservations, matching transfer hold/event, and immutable records.
- Real HTTP/PostgreSQL tests including simultaneous requests, last funds, lost response recovery, failed event persistence and negative authorization.
- Atomic posting with one exact two-entry journal per transfer, irreversible terminal state, hold release and canonical terminal event.
- Separate worker: short fenced outbox claims, publication outside SQL transactions, at-least-once delivery, partition-local retry and confirmed bounded DLT.
- Opt-in overdue-admission recovery and explicitly gated/audited canonical requeue; no consumer offset reset.

`ACCEPTED` is not `POSTED`. The versioned fictitious `synthetic-v1` policy posts up to 500,000 BRL cents and rejects greater values or accounts inactive at processing time. Rejections release the reservation without moving balance. No real money, reversal or automatic hold expiry exists. Do not use it as a payment service.

## Build and tests

Prerequisites: JDK 21 (`JAVA_HOME`), internet for first dependency download; Docker Desktop/Engine for integration. Maven Wrapper downloads Maven 3.9.16 with its checksum. No global Maven installation is required.

```powershell
./mvnw.cmd -B -ntp clean verify
./mvnw.cmd -B -ntp -Pintegration clean verify
# alternatively:
./scripts/verify.ps1 -Integration
```

Linux/macOS: `./mvnw -B -ntp -Pintegration clean verify`.

Default verify runs domain/application/architecture tests. The integration profile creates disposable PostgreSQL 17.6 databases and Apache Kafka 4.2.0 (both pinned by digest), applies Flyway, starts the HTTP API with an ephemeral RSA test issuer, and tests the worker against real broker records/offsets. Ports, tenants, topics and groups are isolated; no reuse or external database URL. Tests never point to the Fulfillment Hubs. Selected Mockito repository/recoverer faults model rollback, transient failure and unavailable quarantine; SQL and Kafka effects are real. Named fault windows do not claim operating-system process kills.

Reports: each module's `target/surefire-reports`, API/worker `target/failsafe-reports`, and `target/site/jacoco`. Contracts: `contracts/openapi.yaml`, `transfer-accepted.v1.schema.json`, `transfer-terminal.v1.schema.json`, `quarantine.v1.schema.json`. Real SQL event samples: API `target/artifacts/{accepted,posted,rejected}-event.json`; quarantine sample in worker `target/artifacts/quarantine-event.json`.

Optional contract validation after integration (Python 3 in an isolated virtual environment): install `scripts/requirements-contracts.txt`, then run `python scripts/validate-contracts.py`. This validation has no service or cloud access.

## Running the API with a dedicated local database

Use a new database reserved for DTP; configure credentials outside the repo. Infrastructure orchestration and a bundled Keycloak realm are intentionally deferred. The HTTP acceptance tests above already reproduce the complete F1 path automatically, without configuring an identity provider manually.

Required runtime environment:

| Variable | Meaning |
|---|---|
| DTP_DATABASE_URL | JDBC URL of the DTP PostgreSQL database |
| DTP_DATABASE_USER / DTP_DATABASE_PASSWORD | Database credentials |
| DTP_ISSUER | Trusted OIDC issuer |
| DTP_JWK_SET_URI | Trusted JWKS endpoint; RS256 keys |
| DTP_AUDIENCE | Expected audience, default `dtp-ledger` |
| DTP_BIND_ADDRESS / DTP_PORT | Defaults loopback / 8085 |
| DTP_REDIS_HOST / DTP_REDIS_PORT / DTP_REDIS_PASSWORD | Dedicated local Redis quota store; defaults loopback/6379/empty password |
| DTP_QUOTA_ENABLED / DTP_QUOTA_LIMIT / DTP_QUOTA_WINDOW_MS | Default true / 60 / 60000; quota-off only for isolated laboratory/tests |

First apply migrations as an explicit one-off command. It does not require an identity provider:

```powershell
$env:DTP_ALLOW_MIGRATION = 'true'
java -jar ledger-api/target/ledger-api-0.1.0-SNAPSHOT.jar migrate
Remove-Item Env:DTP_ALLOW_MIGRATION
```

Apply `docs/local-seed.sql` with `psql -v ON_ERROR_STOP=1 --file docs/local-seed.sql` against that dedicated database; all values are synthetic. The seed is idempotent. Runtime defaults do not apply Flyway on startup (`DTP_MIGRATE_ON_START=false`). Then:

```powershell
java -jar ledger-api/target/ledger-api-0.1.0-SNAPSHOT.jar
```

JWT claims: `sub=alice`, `tenant_id=10000000-0000-0000-0000-000000000001`, authorized party `azp` identifying the OAuth client, `aud=dtp-ledger`, configured `iss`, expiration and scopes `transfer:write transfer:read`. No development signing secret or test private key is committed. This phase accepts correctly minted RSA tokens, but does not provision Keycloak or use password grants.

Admission example (send with bearer and `Idempotency-Key`, through any HTTP client):

```json
{
  "sourceAccountId": "20000000-0000-0000-0000-000000000001",
  "destinationAccountId": "20000000-0000-0000-0000-000000000002",
  "amountCents": 1000,
  "currency": "BRL"
}
```

Result: 202 + Location + stable admission. Same admitted key/payload replays it even after posting/rejection; a changed payload is 422. GET Location returns current status, aggregate version, terminal timestamp/rejection reason. Keys are scoped by tenant, subject, OAuth client and operation. A rolled-back admission leaves no key reservation, so a corrected request can retry. Unknown fields and fractional cents are rejected. Account and transfer queries require read scope and object authorization, returning uniform 404 outside it.

Health: `/actuator/health/liveness` and `/actuator/health/readiness` (including SQL); other Actuator paths are denied. API request bodies are limited to 16 KiB; responses are no-store with bounded correlation IDs. Trace context hooks capture actual server span when enabled; Kafka propagation/export/dashboards are not yet validated. Runtime database capability grants and local Keycloak/RBAC/Kafka TLS-SASL-ACL scenarios are validated in F4; full secure three-host demonstration remains F8.

## Run the worker

Apply V1/V2 migrations first through the explicit command above. Provide the same dedicated ledger SQL settings plus `DTP_KAFKA_BOOTSTRAP_SERVERS` for a local broker, then:

```powershell
java -jar ledger-worker/target/ledger-worker-0.1.0-SNAPSHOT.jar
```

The worker is a non-web executable. The local topic setup uses three partitions, one replica and seven-day retention; this demonstrates transport/recovery semantics, not broker cluster availability. Recovery is disabled by default; runtime meters have no exporter/dashboard yet. [F2 runbook](docs/RUNBOOK_F2.md) describes diagnosis, safe canonical requeue, opt-in recovery, meter names and remaining limits.

## Activity and quota

Admission checks the Redis Lua bucket before SQL, by authenticated tenant/client. Exceeding quota returns 429/Retry-After; Redis failure returns sanitized 503 without reservations. Reads remain authorized without Redis. Replays reaching the edge count as requests. Redis never stores balances or an authorization bypass; caching is not implemented.

Run `activity-service/target/activity-service-0.1.0-SNAPSHOT.jar` with `DTP_MONGO_URI`, local Kafka bootstrap and trusted issuer/JWKS. Its audience is `dtp-activity`, scope `activity:read` and signed role `transfer-client` or `auditor`; HTTP defaults loopback/8086. `GET /v1/activity/transfers/{id}`, cursor list and `/{id}/events` filter tenant/initiating subject for clients, or tenant for auditors. This derived receipt can lag behind the authoritative ledger; 404 may mean not yet projected. Activity has no SQL/ledger dependency.

History and snapshot use separate Mongo collections. A duplicate fact still repairs a missing snapshot; newer versions win atomically. Rebuild requires `DTP_ALLOW_REBUILD=true` and `java -jar activity-service/target/activity-service-0.1.0-SNAPSHOT.jar rebuild <new-prefix>` with an empty target different from live. It generates a new `activity-rebuild-` group and never resets financial offsets. Keep the rebuild running through catch-up, then configure a read-only instance to the new prefix (disable its listener and Kafka admin auto-create). Kafka retention limits reconstruction; no automatic switch is implemented.

See [ADR-001](docs/ADR-001-foundation-boundaries.md), [ADR-002](docs/ADR-002-posting-and-recovery.md), [ADR-003](docs/ADR-003-private-projection-and-quota.md), historical [F1](docs/VALIDATION_F1.md), [F2](docs/VALIDATION_F2.md) and [F3 evidence](docs/VALIDATION_F3.md). No cloud deployment, Terraform apply, resources or changes to the Fulfillment Hubs are part of this project.

## Local identity and security

Use signed realm roles together with operation scopes: `transfer-client` for admission, `transfer-client` or `auditor` for tenant-bound reads, `operator` plus `transfer:operate` for canonical requeue. `admin` alone grants no financial operation. [F4 runbook](docs/RUNBOOK_F4.md) describes the local realm, native seed, runtime database grants, secure Kafka profiles and optional legacy adapter. [F4 evidence](docs/VALIDATION_F4.md) records129 clean-verification tests; [ADR-004](docs/ADR-004-local-security-and-readonly-legacy.md) defines limits.
# Checkpoint de publicação — 11/10/2026

F0–F4 concluídas; F4 validada com 129 testes. O código inclui F5 parcial de tracing, cujo gate ainda não foi concluído: três novos testes unitários passaram, mas a última regressão Kafka teve um timeout no cenário de broker indisponível. Consulte [o progresso](../PROGRESSO.md) antes de retomar.
