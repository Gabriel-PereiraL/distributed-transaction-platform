# Local F2 runbook

Use only a dedicated synthetic DTP database and local Kafka. Never point these commands to either Fulfillment Hub or a cloud account. Run migrations explicitly with the API jar's `migrate` command before starting workers; V2 upgrades existing V1 admissions.

## Start worker

Build both executables with `./mvnw.cmd -B -ntp -Pintegration verify`. Provide `DTP_DATABASE_URL`, `DTP_DATABASE_USER`, `DTP_DATABASE_PASSWORD`, and `DTP_KAFKA_BOOTSTRAP_SERVERS` outside source control. The API additionally needs its configured JWT issuer/JWKS. The worker does not expose an HTTP admission surface and does not need user JWTs.

```powershell
java -jar ledger-worker/target/ledger-worker-0.1.0-SNAPSHOT.jar
```

Worker defaults create the two laboratory topics (`dtp.transfer-events.v1`, `.dlt`) with three partitions, one replica and seven-day retention. The posting group is `ledger-posting-v1`. Producer idempotence and acknowledgments are configured; consumer auto-commit is disabled. Infrastructure security is deferred. Avoid multiple unrelated datasets sharing these topic/group names. For a separate laboratory, override `--dtp.kafka.events-topic=... --dtp.kafka.dlt-topic=... --dtp.kafka.posting-group=...` consistently.

`POST /v1/transfers` returns ACCEPTED even when later rejected by the synthetic policy. Poll the authenticated GET Location for the terminal state. `synthetic-v1` has a 500,000-cent posting limit; no external money is moved.

## Inspect without changing financial state

```sql
SELECT status, count(*) FROM transfers GROUP BY status;
SELECT status, count(*), min(created_at), max(attempts) FROM outbox_deliveries GROUP BY status;
SELECT t.id,t.tenant_id,t.accepted_at,h.amount_cents FROM transfers t
 JOIN holds h ON h.transfer_id=t.id WHERE t.status='ACCEPTED' ORDER BY t.accepted_at;
SELECT a.id,a.balance_cents,COALESCE(j.cents,0) AS journal_cents,
 a.reserved_cents,COALESCE(h.cents,0) AS held_cents FROM accounts a
 LEFT JOIN (SELECT account_id,sum(signed_cents) cents FROM journal_entries GROUP BY account_id) j ON j.account_id=a.id
 LEFT JOIN (SELECT account_id,sum(amount_cents) cents FROM holds GROUP BY account_id) h ON h.account_id=a.id
 WHERE a.balance_cents<>COALESCE(j.cents,0) OR a.reserved_cents<>COALESCE(h.cents,0);
```

The last query must return zero rows. Do not patch balances, release holds by age, edit canonical events, or reset the posting group's offsets to rebuild finances. Diagnose connectivity, logs and DLT identity first. PENDING deliveries retry automatically after the stored backoff; CLAIMED deliveries become reclaimable after the database-clock lease. PUBLISHED does not guarantee exactly one message in Kafka.

## Requeue canonical instruction

Read the DLT's original identity, inspect the matching SQL transfer/event, and resolve the cause. DLT bytes are diagnostic evidence, not trusted replacement instructions. Repair republishes the canonical SQL admission under the same identity:

```powershell
$env:DTP_ALLOW_REQUEUE = 'true'
java -jar ledger-worker/target/ledger-worker-0.1.0-SNAPSHOT.jar requeue '<tenant-uuid>' '<transfer-uuid>' 'operator.local' 'resolved local broker outage'
Remove-Item Env:DTP_ALLOW_REQUEUE
```

The command starts a one-off context with publisher/consumer/recovery disabled. Inspect `operational_audit` by transfer/attempt: REQUESTED with no outcome means uncertainty; retrying the same canonical identity is financially safe. CONFIRMED means broker acceptance, not financial completion. ALREADY_TERMINAL is an audited no-op. Local database credentials and explicit flag authorize this laboratory command; production operator RBAC is not implemented.

Recovery can be opted in with `--dtp.recovery.enabled=true --dtp.recovery.age-seconds=300`. It checks up to five overdue admissions per tick through the same posting transaction. It must be accompanied by inspection of why Kafka did not process the admission. No automatic hold cancellation exists.

Meters in the worker registry: `dtp.outbox.published`, `.unconfirmed`, `.stale_claim`; `dtp.posting.records.completed`; `dtp.quarantine.confirmed`, `.unconfirmed`, `.recovery_pauses`; `dtp.recovery.terminalized`, `.failed`. These are in-process meters, with no claimed dashboard/export/alerts yet. Worker is a non-web executable; worker probe/draining design remains later work.

## Reproduce tests

```powershell
./mvnw.cmd -B -ntp -Pintegration clean verify
python scripts/validate-contracts.py
```

The API suite uses a disposable database and ephemeral RSA test issuer. The worker suite creates its own disposable database, digest-pinned Apache Kafka broker, unique topics/group and observer groups; cleanup never targets a manual database. Testcontainers releases its containers. Detailed results and limits are recorded in VALIDATION_F2.md after final verification.
