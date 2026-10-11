# F4 validation — 2026-10-11

**BUILD SUCCESS — 129 tests, zero failures/errors/skips.** Clean reactor command:
`./mvnw.cmd -B -ntp -Pintegration clean verify`. Elapsed 08:08 min is test execution
time, not performance evidence. Machine summary: [f4-validation.json](evidence/f4-validation.json).
All earlier admission, posting, crash/recovery, Redis and Mongo assertions are included.

F4 adds 35 tests to the F3 baseline of 94: role filters8, Keycloak8,
runtime privileges2, Kafka security4, legacy adapter6, tenant auditor2,
operator requeue4 and a separate operation-quota case1.

- Real Keycloak26.8.0 proves authorization code with mandatory S256 PKCE, nonce,
  single-use code, registered redirects, disabled password grant and technical
  client credentials. Wrong issuer/audience/signature fail. Rotating RSA key changes
  kid and refreshes the same decoder's cached JWKS; an unexpired old token remains
  valid while its key is available. User tenant maps from an issuer-managed attribute;
  the technical operator has only its explicit operational scope/role.
- Signed roles and operation scopes are both mandatory. Auditor reads of SQL
  customer/transfer and Mongo receipt/page/history remain tenant-bound; technical
  accounts stay hidden. Admin alone grants nothing financially. Native public
  and operational client configurations import from the committed environment
  template without the test-only realm-management bootstrap client.
- HTTP operator requeue preserves canonical identity, payload, money and aggregate
  version. Active lease returns409; an expired lease is fenced. Audit failure
  rolls back reset; POSTED/REJECTED requeue returns an audited200 no-op. Queued202
  means durable outbox work, without broker confirmation or offset reset.
- Redis uses a distinct tenant/client/operation bucket. Six requeues pass, the
  seventh returns429, and the admission bucket still admits independently. Failed
  or repeated requeue does not create another reservation.
- Dedicated restricted database logins execute real admission, HTTP requeue and
  posting. DDL, disabling triggers, Flyway-table reads and cross-capability writes
  fail. Column grants accommodate JPA DynamicUpdate and canonical row locks without
  giving API balance/status/journal posting privileges. Migration/funding remains
  schema-owner work; tenant RLS is not implemented.
- Authorized optional legacy HTTP returns bounded synthetic reference/state only.
  Remote observations of pg_stat_activity find no API idle-in-transaction; another
  owner's request produces404 without reaching legacy. Downstream failure produces
  sanitized503 with unchanged balances. Real chunked-server tests cover breaker
  open/recovery, immediate bulkhead rejection/permit release, deadline cancellation,
  4KiB response bound and refusal of extra financial fields. No financial fallback.
- Kafka tests use TLS1.3 with hostname verification, SASL_SSL broker traffic and
  mutually authenticated encrypted controller traffic. Four distinct generated
  credentials separate broker/admin/worker/Activity. Real ACLs permit worker publish
  and Activity consume/commit, but deny Activity financial publication, posting
  group access, another DLT and impersonation with its own secret.
- The Boot secure-profile probe binds producer and consumer TLS/SASL properties;
  Spring Kafka publishes with the restricted worker identity. Runtime profiles
  disable topic creation. The probe isolates Kafka configuration; it is not a
  full financial worker/Activity secure-host E2E, which remains F8.

`scripts/validate-contracts.py` passed both OpenAPI specifications and actual HTTP
requeue202/terminal200, legacy metadata, Activity receipt/page/history, SQL
accepted/posted/rejected and broker-quarantine samples. No raw token, private key,
password or environment dump is retained in this public evidence summary.

Limits: local HTTP/start-dev issuer/callback, one broker, disposable certificates,
no production/HA or instant revocation claim. No exporter/dashboard, benchmark,
recipient feed, cache, reversal, tenant RLS or cloud validation. The older CLI
uses its local flag/DB authorization, distinct from HTTP RBAC. Audit records identify
signed subject/tenant; denied requests do not create a success audit row. Mongo
history and snapshot remain separate writes. See [ADR-004](ADR-004-local-security-and-readonly-legacy.md)
and [F4 runbook](RUNBOOK_F4.md). F5–F8 remain approved local gates.

Primary version/compatibility references: [Keycloak downloads](https://www.keycloak.org/downloads),
[Keycloak OIDC](https://www.keycloak.org/securing-apps/oidc-layers),
[Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Resilience4j2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0).
