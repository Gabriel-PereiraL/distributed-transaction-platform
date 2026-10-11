# ADR-001 — atomic admission, authorizations and staged delivery

Status: accepted for F0/F1, approved by the user on 2026-10-11.

Java 21/Spring Boot 4.1.1/Maven 3.9.16. Four implemented modules, no empty Kafka/Mongo/Redis/worker scaffolds. Domain and application are framework independent; Spring/JPA transaction composition lives in adapters.

Admission is one PostgreSQL transaction: scoped idempotency key, customer-account locks in UUID order, available-funds reservation, ACCEPTED transfer, matching hold, canonical event and pending outbox. Unique-constraint conflict in the specific admission INSERT waits for the winner; a rejected transaction leaves no claimed key. No network call runs inside this boundary. A response lost after commit is recovered with the same identity and immutable admission timestamp.

The logical scope is `(tenant, subject, OAuth client azp, operation, key)`. A change of OAuth client is a different operation scope, documented and tested. A key bound to a committed instruction is not expired in F1; HTTP response retention will not silently release logical identity. Principal/tenant/authorized-party come from RS256 JWT validated against configured issuer, audience and JWKS, before storage. OAuth scopes are the first gate; current ownership/tenant and customer account kind are the second. Technical funding accounts cannot admit or expose transfers.

Positive BRL cents, maximum 1,000,000,000 per transfer. Initial balances are recorded through a balanced synthetic funding journal; the technical contra-account may be negative. Deferred triggers check journal balance and account materialization/holds at commit. This stronger SQL guard costs aggregate queries; it is a correctness-first baseline to profile in F6, not a throughput claim. Immutability applies to record updates/deletes; runtime database credentials must not be a superuser in deployment.

F1 only admits/reserves. It does not POST, settle, release holds, reverse, publish Kafka messages, expire operations, run reconciliation or claim exactly-once. F2 will add those transitions through migrations and must replace F1 hold/status constraints intentionally. Pending holds remain visible; don't use this foundation as a functioning payment system.

JWT integration tests use an ephemeral RSA issuer/JWKS HTTP server with real signature verification, not Keycloak. Keycloak realm/roles/token lifecycle, quota, distributed tracing/export and advanced security are later acceptance gates. Current request tracing is captured from the actual server span when configured; no raw incoming traceparent is mistaken for a consumer span.

Every integration run owns a new PostgreSQL container, random host port, database and unique tenants per scenario. No reuse, no external URL/truncate, no modification of the Fulfillment Hub environments. Flyway migrations are applied automatically only in that test fixture; runtime defaults require explicit migration authorization/configuration.
