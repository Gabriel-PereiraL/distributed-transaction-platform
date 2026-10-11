CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    owner_subject varchar(128) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('CUSTOMER', 'TECHNICAL')),
    currency varchar(3) NOT NULL CHECK (currency = 'BRL'),
    balance_cents bigint NOT NULL DEFAULT 0,
    reserved_cents bigint NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (reserved_cents >= 0),
    CHECK ((kind = 'CUSTOMER' AND balance_cents >= reserved_cents)
        OR (kind = 'TECHNICAL' AND reserved_cents = 0))
);
CREATE INDEX accounts_owner ON accounts(tenant_id, owner_subject, id);

CREATE TABLE transfers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    initiated_by varchar(128) NOT NULL,
    source_account_id uuid NOT NULL,
    destination_account_id uuid NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents BETWEEN 1 AND 1000000000),
    currency varchar(3) NOT NULL CHECK (currency = 'BRL'),
    status varchar(16) NOT NULL CHECK (status = 'ACCEPTED'),
    accepted_at timestamptz NOT NULL,
    rule_version varchar(32) NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version = 1),
    UNIQUE (tenant_id, id),
    CHECK (source_account_id <> destination_account_id),
    FOREIGN KEY (tenant_id, source_account_id) REFERENCES accounts(tenant_id, id),
    FOREIGN KEY (tenant_id, destination_account_id) REFERENCES accounts(tenant_id, id)
);
CREATE INDEX transfers_actor_cursor ON transfers(tenant_id, initiated_by, accepted_at DESC, id DESC);

CREATE TABLE admission_keys (
    tenant_id uuid NOT NULL,
    subject varchar(128) NOT NULL,
    client_id varchar(128) NOT NULL,
    operation varchar(32) NOT NULL CHECK (operation = 'POST /v1/transfers'),
    key varchar(64) NOT NULL CHECK (key ~ '^[A-Za-z0-9_-]{1,64}$'),
    request_hash char(64) NOT NULL,
    transfer_id uuid NOT NULL,
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, subject, client_id, operation, key),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES transfers(tenant_id, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE holds (
    transfer_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES transfers(tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts(tenant_id, id)
);
CREATE INDEX holds_account ON holds(tenant_id, account_id);

CREATE TABLE canonical_events (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type varchar(100) NOT NULL,
    schema_version integer NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    correlation_id varchar(64) NOT NULL,
    traceparent varchar(55),
    UNIQUE (tenant_id, aggregate_id, aggregate_version),
    FOREIGN KEY (tenant_id, aggregate_id) REFERENCES transfers(tenant_id, id)
);
CREATE TABLE outbox_deliveries (
    event_id uuid PRIMARY KEY REFERENCES canonical_events(event_id),
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status = 'PENDING'),
    created_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL
);
CREATE INDEX outbox_due ON outbox_deliveries(next_attempt_at, event_id);

-- Funding is synthetic double-entry, not an unrecorded balance mutation.
CREATE TABLE journals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    purpose varchar(32) NOT NULL CHECK (purpose = 'SYNTHETIC_FUNDING'),
    currency varchar(3) NOT NULL CHECK (currency = 'BRL'),
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE TABLE journal_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    account_id uuid NOT NULL,
    signed_cents bigint NOT NULL CHECK (signed_cents <> 0),
    FOREIGN KEY (tenant_id, journal_id) REFERENCES journals(tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts(tenant_id, id)
);
CREATE INDEX journal_entries_account ON journal_entries(tenant_id, account_id);
CREATE INDEX journal_entries_journal ON journal_entries(journal_id);

CREATE FUNCTION immutable_financial_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Financial records are immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER journals_immutable BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER entries_immutable BEFORE UPDATE OR DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER events_immutable BEFORE UPDATE OR DELETE ON canonical_events FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();

CREATE FUNCTION check_journal_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; total numeric; entry_count bigint;
BEGIN
    IF TG_TABLE_NAME = 'journals' THEN target := NEW.id; ELSE target := NEW.journal_id; END IF;
    SELECT COALESCE(SUM(signed_cents), 0), COUNT(*) INTO total, entry_count FROM journal_entries WHERE journal_id = target;
    IF total <> 0 OR entry_count < 2 THEN
        RAISE EXCEPTION 'Journal must have at least two balanced entries' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balanced_journal AFTER INSERT ON journals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();
CREATE CONSTRAINT TRIGGER balanced_entries AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

CREATE FUNCTION check_account_materialization() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; actual_balance bigint; actual_reserved bigint; expected_balance numeric; expected_reserved numeric;
BEGIN
    IF TG_TABLE_NAME = 'accounts' THEN target := NEW.id; ELSE target := NEW.account_id; END IF;
    SELECT balance_cents, reserved_cents INTO actual_balance, actual_reserved FROM accounts WHERE id = target;
    SELECT COALESCE(SUM(signed_cents), 0) INTO expected_balance FROM journal_entries WHERE account_id = target;
    SELECT COALESCE(SUM(amount_cents), 0) INTO expected_reserved FROM holds WHERE account_id = target;
    IF actual_balance IS DISTINCT FROM expected_balance OR actual_reserved IS DISTINCT FROM expected_reserved THEN
        RAISE EXCEPTION 'Account must match journal and holds' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER account_matches_journal AFTER INSERT OR UPDATE ON accounts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_materialization();
CREATE CONSTRAINT TRIGGER entry_matches_account AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_materialization();
CREATE CONSTRAINT TRIGGER hold_matches_account AFTER INSERT OR UPDATE ON holds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_materialization();

CREATE FUNCTION check_accepted_hold() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; matching bigint;
BEGIN
    IF TG_TABLE_NAME = 'transfers' THEN target := NEW.id; ELSE target := NEW.transfer_id; END IF;
    SELECT COUNT(*) INTO matching FROM transfers t JOIN holds h ON h.transfer_id = t.id AND h.tenant_id = t.tenant_id
        AND h.account_id = t.source_account_id AND h.amount_cents = t.amount_cents WHERE t.id = target;
    IF matching <> 1 THEN RAISE EXCEPTION 'Accepted transfer requires its matching hold' USING ERRCODE = '23514'; END IF;
    SELECT COUNT(*) INTO matching FROM canonical_events e JOIN outbox_deliveries o ON o.event_id = e.event_id
        WHERE e.aggregate_id = target AND e.aggregate_version = 1 AND e.event_type = 'TransferAccepted';
    IF matching <> 1 THEN RAISE EXCEPTION 'Accepted transfer requires its durable event and outbox' USING ERRCODE = '23514'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_requires_hold AFTER INSERT OR UPDATE ON transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_accepted_hold();
CREATE CONSTRAINT TRIGGER hold_matches_transfer AFTER INSERT OR UPDATE ON holds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_accepted_hold();
-- Phase 1 has no hold-release path. Deletes cannot silently orphan reservations.
CREATE TRIGGER holds_no_delete BEFORE DELETE ON holds FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
