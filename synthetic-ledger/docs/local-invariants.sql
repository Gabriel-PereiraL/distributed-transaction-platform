-- Read-only aggregate evidence for the successful synthetic local smoke workload.
BEGIN TRANSACTION READ ONLY;
SELECT json_build_object(
 'transfers', (SELECT count(*) FROM transfers),
 'not_posted', (SELECT count(*) FROM transfers WHERE status <> 'POSTED'),
 'holds', (SELECT count(*) FROM holds),
 'reservations', (SELECT count(*) FROM accounts WHERE reserved_cents <> 0),
 'negative_customer_balances', (SELECT count(*) FROM accounts WHERE kind = 'CUSTOMER' AND balance_cents < 0),
 'unbalanced_journals', (SELECT count(*) FROM (SELECT journal_id FROM journal_entries GROUP BY journal_id HAVING sum(signed_cents) <> 0) j),
 'posting_journals', (SELECT count(*) FROM journals WHERE purpose = 'TRANSFER_POSTING'),
 'bad_materialized_balances', (SELECT count(*) FROM accounts a LEFT JOIN
    (SELECT account_id, sum(signed_cents) AS balance FROM journal_entries GROUP BY account_id) e
    ON a.id = e.account_id WHERE a.balance_cents <> coalesce(e.balance, 0)),
 'unpublished', (SELECT count(*) FROM outbox_deliveries WHERE status <> 'PUBLISHED'),
 'api_can_insert_journal', has_table_privilege('dtp_api_login', 'journal_entries', 'INSERT'),
 'api_can_change_balance', has_column_privilege('dtp_api_login', 'accounts', 'balance_cents', 'UPDATE'),
 'worker_can_admit', has_table_privilege('dtp_worker_login', 'transfers', 'INSERT'),
 'runtime_superusers', (SELECT count(*) FROM pg_roles WHERE rolname IN ('dtp_api_login', 'dtp_worker_login') AND rolsuper)
);
COMMIT;
