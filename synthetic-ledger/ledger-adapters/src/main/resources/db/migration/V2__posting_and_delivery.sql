-- Append-only upgrade: V1 must retain its original checksum.
ALTER TABLE transfers DROP CONSTRAINT transfers_status_check;
ALTER TABLE transfers DROP CONSTRAINT transfers_aggregate_version_check;
ALTER TABLE transfers ADD COLUMN terminal_at timestamptz;
ALTER TABLE transfers ADD COLUMN rejection_reason varchar(64);
ALTER TABLE transfers ADD CONSTRAINT transfer_state CHECK (
 (status='ACCEPTED' AND aggregate_version=1 AND terminal_at IS NULL AND rejection_reason IS NULL) OR
 (status='POSTED' AND aggregate_version=2 AND terminal_at IS NOT NULL AND rejection_reason IS NULL) OR
 (status='REJECTED' AND aggregate_version=2 AND terminal_at IS NOT NULL AND rejection_reason IS NOT NULL AND rejection_reason IN ('ACCOUNT_INACTIVE','SYNTHETIC_LIMIT')));
CREATE INDEX transfers_recovery ON transfers(accepted_at,id) WHERE status='ACCEPTED';

ALTER TABLE journals DROP CONSTRAINT journals_purpose_check;
ALTER TABLE journals ADD COLUMN transfer_id uuid;
ALTER TABLE journals ADD CONSTRAINT journal_purpose CHECK (
 (purpose='SYNTHETIC_FUNDING' AND transfer_id IS NULL) OR (purpose='TRANSFER_POSTING' AND transfer_id IS NOT NULL));
ALTER TABLE journals ADD FOREIGN KEY (tenant_id,transfer_id) REFERENCES transfers(tenant_id,id);
CREATE UNIQUE INDEX one_posting_journal ON journals(transfer_id) WHERE transfer_id IS NOT NULL;

CREATE TABLE processed_events (
 event_id uuid PRIMARY KEY REFERENCES canonical_events(event_id),
 tenant_id uuid NOT NULL,
 transfer_id uuid NOT NULL UNIQUE,
 processed_at timestamptz NOT NULL,
 FOREIGN KEY(tenant_id,transfer_id) REFERENCES transfers(tenant_id,id));
CREATE TRIGGER processed_immutable BEFORE UPDATE OR DELETE ON processed_events FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();

ALTER TABLE outbox_deliveries DROP CONSTRAINT outbox_deliveries_status_check;
ALTER TABLE outbox_deliveries ADD CONSTRAINT delivery_status CHECK (status IN ('PENDING','CLAIMED','PUBLISHED'));
ALTER TABLE outbox_deliveries ADD COLUMN claim_token uuid;
ALTER TABLE outbox_deliveries ADD COLUMN lease_until timestamptz;
ALTER TABLE outbox_deliveries ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0);
ALTER TABLE outbox_deliveries ADD COLUMN published_at timestamptz;
ALTER TABLE outbox_deliveries ADD COLUMN last_error varchar(64);
ALTER TABLE outbox_deliveries ADD CONSTRAINT delivery_claim CHECK (
 (status='CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND published_at IS NULL) OR
 (status='PENDING' AND claim_token IS NULL AND lease_until IS NULL AND published_at IS NULL) OR
 (status='PUBLISHED' AND claim_token IS NULL AND lease_until IS NULL AND published_at IS NOT NULL));

-- Deleted holds must also validate the source account's final materialization.
DROP TRIGGER holds_no_delete ON holds;
CREATE OR REPLACE FUNCTION check_account_materialization() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; actual_balance bigint; actual_reserved bigint; expected_balance numeric; expected_reserved numeric;
BEGIN
 IF TG_TABLE_NAME='accounts' THEN target:=NEW.id;
 ELSIF TG_OP='DELETE' THEN target:=OLD.account_id; ELSE target:=NEW.account_id; END IF;
 SELECT balance_cents,reserved_cents INTO actual_balance,actual_reserved FROM accounts WHERE id=target;
 SELECT COALESCE(SUM(signed_cents),0) INTO expected_balance FROM journal_entries WHERE account_id=target;
 SELECT COALESCE(SUM(amount_cents),0) INTO expected_reserved FROM holds WHERE account_id=target;
 IF actual_balance IS DISTINCT FROM expected_balance OR actual_reserved IS DISTINCT FROM expected_reserved THEN
  RAISE EXCEPTION 'Account must match journal and holds' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER hold_deleted_matches_account AFTER DELETE ON holds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_materialization();

CREATE FUNCTION immutable_transfer_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Transfers cannot be deleted' USING ERRCODE='23514'; END IF;
 IF (NEW.id,NEW.tenant_id,NEW.initiated_by,NEW.source_account_id,NEW.destination_account_id,NEW.amount_cents,NEW.currency,NEW.accepted_at,NEW.rule_version)
 IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.initiated_by,OLD.source_account_id,OLD.destination_account_id,OLD.amount_cents,OLD.currency,OLD.accepted_at,OLD.rule_version)
 OR OLD.status<>'ACCEPTED' OR NEW.status='ACCEPTED' THEN
  RAISE EXCEPTION 'Only first terminalization may update a transfer' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER transfer_identity BEFORE UPDATE OR DELETE ON transfers FOR EACH ROW EXECUTE FUNCTION immutable_transfer_identity();

-- Every final state is checked at commit, after all related writes.
CREATE OR REPLACE FUNCTION check_accepted_hold() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; t transfers%ROWTYPE; matching bigint; journal uuid;
BEGIN
 IF TG_TABLE_NAME='transfers' THEN target:=NEW.id;
 ELSIF TG_OP='DELETE' THEN target:=OLD.transfer_id; ELSE target:=NEW.transfer_id; END IF;
 SELECT * INTO t FROM transfers WHERE id=target;
 IF t.status='ACCEPTED' THEN
  SELECT COUNT(*) INTO matching FROM holds h WHERE h.transfer_id=t.id AND h.tenant_id=t.tenant_id AND h.account_id=t.source_account_id AND h.amount_cents=t.amount_cents;
  IF matching<>1 THEN RAISE EXCEPTION 'Accepted transfer requires matching hold' USING ERRCODE='23514'; END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM holds WHERE transfer_id=target) THEN RAISE EXCEPTION 'Terminal transfer cannot retain hold' USING ERRCODE='23514'; END IF;
  SELECT COUNT(*) INTO matching FROM processed_events p JOIN canonical_events e ON e.event_id=p.event_id
    WHERE p.transfer_id=target AND p.tenant_id=t.tenant_id AND e.aggregate_id=target AND e.tenant_id=t.tenant_id AND e.event_type='TransferAccepted' AND e.aggregate_version=1;
  IF matching<>1 THEN RAISE EXCEPTION 'Terminal transfer requires canonical dedup' USING ERRCODE='23514'; END IF;
  SELECT COUNT(*) INTO matching FROM canonical_events e JOIN outbox_deliveries o USING(event_id)
    WHERE e.aggregate_id=target AND e.tenant_id=t.tenant_id AND e.aggregate_version=2 AND e.event_type=CASE WHEN t.status='POSTED' THEN 'TransferPosted' ELSE 'TransferRejected' END;
  IF matching<>1 THEN RAISE EXCEPTION 'Terminal transfer requires durable event and outbox' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT COUNT(*) INTO matching FROM canonical_events e JOIN outbox_deliveries o USING(event_id)
  WHERE e.aggregate_id=target AND e.tenant_id=t.tenant_id AND e.aggregate_version=1 AND e.event_type='TransferAccepted';
 IF matching<>1 THEN RAISE EXCEPTION 'Transfer requires durable admission event and outbox' USING ERRCODE='23514'; END IF;
 SELECT id INTO journal FROM journals WHERE transfer_id=target;
 IF t.status='POSTED' THEN
  IF journal IS NULL OR (SELECT COUNT(*) FROM journal_entries WHERE journal_id=journal)<>2
    OR NOT EXISTS(SELECT 1 FROM journal_entries WHERE journal_id=journal AND account_id=t.source_account_id AND signed_cents=-t.amount_cents)
    OR NOT EXISTS(SELECT 1 FROM journal_entries WHERE journal_id=journal AND account_id=t.destination_account_id AND signed_cents=t.amount_cents) THEN
   RAISE EXCEPTION 'Posted transfer requires exact double-entry journal' USING ERRCODE='23514';
  END IF;
 ELSIF journal IS NOT NULL THEN RAISE EXCEPTION 'Only posted transfers have posting journals' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER hold_deleted_matches_transfer AFTER DELETE ON holds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_accepted_hold();
CREATE FUNCTION check_posting_journal_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.transfer_id IS NOT NULL AND (SELECT status FROM transfers WHERE id=NEW.transfer_id)<>'POSTED' THEN
  RAISE EXCEPTION 'Posting journal requires posted transfer' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER posting_journal_transfer AFTER INSERT ON journals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_posting_journal_transfer();

CREATE FUNCTION check_posting_entries_exact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; t transfers%ROWTYPE;
BEGIN
 SELECT transfer_id INTO target FROM journals WHERE id=NEW.journal_id;
 IF target IS NOT NULL THEN
  SELECT * INTO t FROM transfers WHERE id=target;
  IF t.status<>'POSTED' OR (SELECT COUNT(*) FROM journal_entries WHERE journal_id=NEW.journal_id)<>2
   OR NOT EXISTS(SELECT 1 FROM journal_entries WHERE journal_id=NEW.journal_id AND account_id=t.source_account_id AND signed_cents=-t.amount_cents)
   OR NOT EXISTS(SELECT 1 FROM journal_entries WHERE journal_id=NEW.journal_id AND account_id=t.destination_account_id AND signed_cents=t.amount_cents) THEN
   RAISE EXCEPTION 'Posting entries must match immutable transfer' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER posting_entries_exact AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_posting_entries_exact();
CREATE FUNCTION check_processed_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfers t JOIN canonical_events e ON e.aggregate_id=t.id AND e.tenant_id=t.tenant_id
  WHERE t.id=NEW.transfer_id AND t.tenant_id=NEW.tenant_id AND t.status IN ('POSTED','REJECTED')
   AND e.event_id=NEW.event_id AND e.event_type='TransferAccepted' AND e.aggregate_version=1 AND e.schema_version=1) THEN
  RAISE EXCEPTION 'Dedup must correspond to terminal canonical transfer' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER processed_requires_terminal AFTER INSERT ON processed_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_processed_terminal();
CREATE TRIGGER holds_no_update BEFORE UPDATE ON holds FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER admission_keys_immutable BEFORE UPDATE OR DELETE ON admission_keys FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER outbox_no_delete BEFORE DELETE ON outbox_deliveries FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE FUNCTION immutable_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.owner_subject,NEW.kind,NEW.currency) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.owner_subject,OLD.kind,OLD.currency) THEN
  RAISE EXCEPTION 'Account identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER account_identity BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION immutable_account_identity();

CREATE TABLE operational_audit (
 id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL,
 tenant_id uuid NOT NULL,
 transfer_id uuid NOT NULL,
 actor varchar(128) NOT NULL CHECK (length(actor) BETWEEN 1 AND 128),
 reason varchar(256) NOT NULL CHECK (length(reason) BETWEEN 1 AND 256),
 action varchar(32) NOT NULL CHECK (action IN ('REQUEUE_REQUESTED','REQUEUE_CONFIRMED','REQUEUE_UNCONFIRMED','REQUEUE_ALREADY_TERMINAL')),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,transfer_id) REFERENCES transfers(tenant_id,id),
 UNIQUE(attempt_id,action));
CREATE TRIGGER operational_audit_immutable BEFORE UPDATE OR DELETE ON operational_audit FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
