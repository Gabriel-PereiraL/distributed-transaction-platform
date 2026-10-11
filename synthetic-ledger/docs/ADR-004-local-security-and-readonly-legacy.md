# ADR-004 — Local identity, bounded operations and read-only legacy

Accepted for F4 local implementation on 2026-10-11; validation is tracked separately.

The API and Activity validate RS256/JWKS, issuer, audience, expiry, tenant, subject
and authorized client. Signed realm roles are allowlisted and combined with an
operation scope. A transfer client may admit only from its own customer account.
An auditor may read customer objects within its tenant. An operator with
`transfer:operate` may queue the original accepted event for its tenant. `admin`
alone grants no financial or operational permission. Legacy metadata retains
client scope/role and current owner authorization. Role metadata in the pure
application identity is immutable; it never participates in the idempotency hash.

OIDC uses authorization code with mandatory S256 PKCE for the public local client.
Technical operations use separate client credentials. User tenant comes from an
issuer-managed user attribute. The technical operator is bound to one configured
tenant. Public password grant and implicit flow are disabled. Explicit basic/sub
mapping is required when importing custom Keycloak client scopes. Key rotation
refreshes the decoder's JWKS on a new kid; old unexpired tokens remain valid while
their public key remains published. This does not promise instant revocation.

Requeue serializes on transfer then delivery, using DB time for lease checks.
A live claim is never stolen. An expired claim may be reset and its token fenced.
The same canonical event, financial state and aggregate version are preserved.
The delivery reset and immutable audit insertion commit together. A terminal
transfer produces an audited no-op. The HTTP 202 means durably queued; publication
and financial completion require subsequent worker processing. Repeated operator
requests may create multiple audit attempts, bounded by their own Redis operation
bucket. No broker call occurs in this SQL transaction. Audit records store the
authenticated subject and tenant; rejected requests/live-lease conflicts do not
create a success audit row. The older local CLI remains a separate flag/DB gate.

Runtime database capability roles do not own schema or migrations. API grants
allow admission and operator outbox/audit mutations but exclude posting balances,
status updates and journal inserts. A column-level version grant permits the
transfer row lock; state/version constraints prohibit a standalone version change.
Deferred guards run as the invoking role, so required journal reads are explicit.
Worker may post and release holds; audit capability is read-only. This is database
least privilege by process capability, without tenant RLS; HTTP tenant checks
remain mandatory. Funding and migration use a separate schema owner.

The secure Kafka profile uses SASL_SSL, certificate hostname verification and
distinct worker/Activity credentials. ACLs separate canonical writes, DLT writes
and consumer groups. Topic creation is disabled for runtime secure profiles;
administrative provisioning is explicit. The local broker test also encrypts
inter-broker traffic and requires client certificates on its controller listener.
Only authenticated broker/admin or the controller certificate is a super-user.
One local broker remains a laboratory availability limit.

The fictitious legacy integration returns bounded reference/state metadata and
cannot affect admission or posting decisions. Ownership SQL completes before
remote I/O. The adapter permits GET only to an explicit literal loopback origin,
does not follow redirects, cancels oversized bodies before buffering more than
4 KiB, and limits network completion time, concurrency and retry attempts.
Bulkhead rejection does not count as a downstream breaker failure. Unavailability
returns a controlled 503; there is no synthesized financial fallback. Resilience4j
2.4.0 core modules are composed explicitly, without AOP or extra Boot starters.

Local `start-dev`, HTTP issuer/callback, disposable certificates, test bootstrap
identity and generated credentials are laboratory choices. The runtime realm
template excludes the test-only realm-management bootstrap client. Secrets and
private keys are absent from source/release archives. Full secure three-host
demonstration, exporter/alerts, benchmark and production deployment are separate
gates; no production or cloud acceptance is inferred from these fixtures.
