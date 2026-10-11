# F4 local runbook

This is a synthetic local laboratory. Use a new DTP-only PostgreSQL cluster,
never a Fulfillment Hub database. Apply Flyway with the explicit migration flag
and a schema owner. Execute `infra/postgres/runtime-grants.sql` once for that
cluster, then provision separate generated LOGIN credentials with membership
in `dtp_api`, `dtp_worker` or read-only `dtp_audit`. Runtime users must not own
tables or migrations. The script contains no passwords and grants no DDL.

For the local Keycloak26.8.0 realm, import `infra/keycloak/dtp-local-realm.json`
with `--import-realm`. Bind any manually started container port to127.0.0.1.
Required environment variables are `DTP_TENANT_ID`, `DTP_OPERATOR_SECRET` and
`DTP_USER_PASSWORD`, using generated values outside the repository. The template
has no test bootstrap administrator. Administrative Keycloak setup/rotation uses
the server's separately provisioned local admin identity.

For the fixed native Alice seed, choose tenant
`10000000-0000-0000-0000-000000000001` and execute
`docs/local-seed-keycloak.sql` in a fresh DTP database after migration. It uses
native subject `40000000-0000-0000-0000-000000000001`; the older local-seed.sql
uses the test subject string `alice` and is a different fresh-database option.
Do not apply both seeds and expect existing owners to change.

Set API/Activity `DTP_ISSUER` to the local realm URL and `DTP_JWK_SET_URI` to
its `/protocol/openid-connect/certs` endpoint. Resource audiences are respectively
`dtp-ledger` and `dtp-activity`. Public client `dtp-web` requires authorization
code, S256 PKCE, state and nonce, with registered callback
`http://127.0.0.1:8999/callback`. The caller must validate state/nonce and exchange
the code with its verifier. There is no password-grant shortcut or bundled frontend.
Technical client `dtp-operator` uses client credentials with its own generated secret.

| Operation | Scope | Signed role / object boundary |
|---|---|---|
| POST transfer | transfer:write | transfer-client; source owner and tenant |
| GET SQL customer/transfer | transfer:read | transfer-client owner/initiator, or auditor within tenant |
| GET Activity receipt/page/history | activity:read | transfer-client initiator, or auditor within tenant |
| POST transfer requeue | transfer:operate | operator within tenant |
| GET optional legacy metadata | transfer:read | transfer-client and current owner |

Admin alone has no implicit operation. Auditor/Operator roles require their
explicit scopes; adding a role cannot change the idempotency request identity.
Technical funding accounts are hidden. No reversal route exists.

Requeue request: `POST /v1/transfers/{id}/requeue` with bearer token and JSON
`{"reason":"recover a synthetic laboratory delivery"}`. A202 receipt means the
same canonical event is queued durably; it is not financial or broker confirmation.
A200 ALREADY_TERMINAL is an audited no-op. A409 delivery.busy means a live claim
must finish/expire; do not manually remove that lease. A429 has Retry-After and
uses a separate operation bucket. A503 storage error rolls back delivery and audit
together. Reconcile current ledger status and canonical delivery before retrying.
Success audit stores tenant/authenticated subject/reason/action; HTTP requeue does
not append the CLI's broker-confirmation record. The worker processes the original
event idempotently. Never reset financial group offsets to recover one transfer.

Activate `secure` on worker and Activity only after a local administrator has
provisioned topics and ACLs. Set `DTP_KAFKA_TRUSTSTORE` to an absolute PKCS12 path
and `DTP_KAFKA_TRUSTSTORE_PASSWORD` to its generated secret. Worker receives
`DTP_KAFKA_WORKER_PASSWORD`; Activity receives a different
`DTP_KAFKA_ACTIVITY_PASSWORD`. Their usernames are fixed to worker/activity.
TLS hostname verification stays enabled; never solve a certificate mismatch by
blanking that property. Runtime secure profiles disable NewTopic creation.

Worker ACLs: read/write canonical topic, write ledger DLT, read its posting group.
Activity ACLs: read canonical topic, write its own DLT, read activity-prefixed groups
including new rebuild groups. Grant idempotent producer capability explicitly.
Neither Activity financial publication nor financial group/DLT access is allowed.
The isolated integration fixture proves these permissions on an actual TLS/SASL
broker; full secure three-host catch-up/financial demonstration is the later F8 gate.
Fixture TLS certificates/private keys live only under target and are excluded from
archives. Do not reuse disposable test material as production infrastructure.

Optional legacy metadata: set `dtp.legacy.enabled=true`, an explicit
`dtp.legacy.origin=http://127.0.0.1:<port>`, deadline-ms (default500) and concurrency
(default4). The external fixture serves GET `/accounts/{uuid}` with only
`externalReference` and `state` ACTIVE/INACTIVE. No writes, redirect, remote origin,
balance fields or financial fallback are accepted. Responses are bounded4KiB;
network completion deadline, at most two attempts with25ms backoff, immediate
bulkhead rejection and circuit breaker bound downstream work. Legacy failure
cannot block admission/posting logic; its own read route returns sanitized503.

Validate with `./mvnw.cmd -B -ntp -Pintegration clean verify` and then
`python scripts/validate-contracts.py` using requirements-contracts.txt in an
isolated environment. Use the machine summary in docs/evidence rather than
interpreting reactor runtime as a benchmark. Local HTTP/start-dev, one broker,
absence of tenant RLS and separate Mongo writes remain explicit laboratory limits.
