-- Local bootstrap, executed by a dedicated schema owner AFTER Flyway.
-- NOLOGIN capability roles; provision LOGIN identities/passwords separately.
-- Not a Flyway migration: runtime users must never own the schema or execute DDL.
CREATE ROLE dtp_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE dtp_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE dtp_audit NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO dtp_api, dtp_worker, dtp_audit;
-- Deferred invariants read journals/entries as the invoking role; they never use SECURITY DEFINER.
GRANT SELECT ON accounts, transfers, admission_keys, holds, journals, journal_entries, canonical_events, outbox_deliveries TO dtp_api;
GRANT INSERT ON transfers, admission_keys, holds, canonical_events, outbox_deliveries TO dtp_api;
GRANT UPDATE (reserved_cents, version) ON accounts TO dtp_api;
-- API also hosts the explicitly authorized operator requeue endpoint.
GRANT INSERT ON operational_audit TO dtp_api;
-- SELECT FOR UPDATE on the canonical transfer requires UPDATE on at least one column.
-- State/version CHECKs prevent standalone version changes; API has no status/balance posting grants.
GRANT UPDATE (aggregate_version) ON transfers TO dtp_api;
GRANT UPDATE (status, next_attempt_at, claim_token, lease_until, published_at, last_error) ON outbox_deliveries TO dtp_api;

GRANT SELECT ON accounts, transfers, holds, journals, journal_entries, canonical_events, outbox_deliveries, processed_events, operational_audit TO dtp_worker;
GRANT INSERT ON journals, journal_entries, canonical_events, outbox_deliveries, processed_events, operational_audit TO dtp_worker;
GRANT UPDATE (balance_cents, reserved_cents, version) ON accounts TO dtp_worker;
GRANT UPDATE (status, aggregate_version, terminal_at, rejection_reason) ON transfers TO dtp_worker;
GRANT UPDATE ON outbox_deliveries TO dtp_worker;
GRANT DELETE ON holds TO dtp_worker;

GRANT SELECT ON accounts, transfers, journals, journal_entries, canonical_events, operational_audit TO dtp_audit;
-- These roles do not implement tenant RLS. JWT/owner checks remain mandatory.
-- New tables receive no automatic runtime grants. Seed/funding remain owner-only.
