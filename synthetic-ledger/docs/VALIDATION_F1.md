# Foundation validation — 2026-10-11

Executed locally on Windows/Docker Desktop, 2026-10-11. This is synthetic laboratory evidence, not production experience.

## Final verification

Command: `./mvnw.cmd -B -ntp -Pintegration clean verify`

**BUILD SUCCESS — 39 tests, zero failures/errors/skips.** Maven reactor elapsed time: 1m52s. This is test duration, not an application performance benchmark.

| Suite | Executed | Scope |
|---|---:|---|
| DomainTest | 13 | Amount limits/currency, reservation without balance mutation, invalid snapshots and self-transfer |
| AdmissionTest | 4 | Replay without effect, ownership, insufficient funds, invalid key before persistence |
| ArchitectureTest | 2 | Domain/application framework independence and dependency direction |
| FoundationIT | 20 | Real HTTP/JWT/JWKS/PostgreSQL, atomicity, concurrency, recovery, SQL guards, migrations and local seed |

Environment: Temurin 21.0.12.1, Maven Wrapper 3.3.4/Maven 3.9.16, Spring Boot 4.1.1, Hibernate 7.4.5.Final, Flyway 12.4.0, Jackson Databind 3.1.5. PostgreSQL 17.6 container index digest: `sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94`. Testcontainers creates random ports and disposable state; no persistent development data is used.

## High-value assertions

- 20 simultaneous same-key admissions all return the same 202 identity; exactly one hold/transfer and 100 reserved cents.
- 20 competing admissions for the last 100 cents: one 202, nineteen 409, one surviving key and no oversubscription.
- Event persistence failure rolls back reservation, transfer, hold and key; HTTP returns sanitized storage failure.
- Committed admission with no original HTTP response can be replayed without creating another transfer.
- Hash mismatch is 422; wrong owner/tenant/cross-tenant destination is uniformly hidden.
- Expired JWT, wrong issuer/audience, missing tenant, unauthenticated mutation and insufficient scope are rejected.
- Integer-only amount and strict fields: fractional/string cents, malformed JSON, unknown tenant field and oversized body cannot reserve funds.
- Deferred PostgreSQL guards reject unrecorded balance changes, orphan reservations and unbalanced journal; entry mutation is rejected.
- Seed applied twice yields three synthetic accounts, one balanced journal, no duplicate funding. Technical accounts remain hidden even with a signed system subject.
- Public readiness/liveness respond; readiness includes database; broader Actuator remains protected.

## Contract checks

OpenAPI 3.1 validates with `openapi-spec-validator 0.7.2`. JSON Schema Draft 2020-12 validates with `jsonschema 4.25.1`, including the actual event emitted into `canonical_events` by the successful HTTP integration test. Tenant/aggregate/time consistency between envelope and payload also checked.

The machine-readable test/contract summary is [f1-validation.json](evidence/f1-validation.json). Maven XML and JaCoCo reports are generated under target and intentionally excluded from the source ZIP. No coverage percentage is presented as an acceptance guarantee; adapters exercised through HTTP integration require a future aggregate report if total coverage is desired.

Compiler uses release 21 and `-Xlint:all -Werror`. Mockito's dynamic test-agent/VM messages are present; they are not compiler warnings. Explicit Mockito agent setup is a future build-hardening option, not silently suppressed output.

The packaged `migrate` command was also invoked with `DTP_ALLOW_MIGRATION=false`; it rejected with exit 1 before reading database credentials/accessing a database. This checks the explicit migration guard; actual Flyway execution is covered by the integration fixture.

## Limits

No Kafka publication/consumer, posting/rejection/reversal, hold release, Mongo/Redis, Keycloak realm, TLS/ACLs, collector/export validation, benchmark, CI execution or cloud deployment. RSA integration issuer is an ephemeral test server. Trace hooks are present; cross-process trace continuity remains untested until F2/F5. API runtime requires a configured trusted issuer and a dedicated local database; tests reproduce F1 without those manual steps.

Runtime role least privilege, migration rollback/forward compatibility and full threat review remain later gates. All admitted instructions stay ACCEPTED in phase 1, with pending holds/outbox. No claim of exactly-once or financial settlement is made.

Both Fulfillment Hub source copies retained clean git status after this work. No deploy or cloud resources were created.
