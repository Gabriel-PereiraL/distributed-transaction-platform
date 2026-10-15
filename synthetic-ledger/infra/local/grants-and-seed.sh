#!/bin/bash
set -euo pipefail
export PGPASSWORD="$(cat /run/secrets/postgres-admin.password)"
psql -h postgres -U dtp_admin -d dtp -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'CREATE ROLE ' || name || ' NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
FROM unnest(ARRAY['dtp_api','dtp_worker','dtp_audit']) AS name
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=name) \gexec
SQL
sed '/^CREATE ROLE /d' /infra/runtime-grants.sql | psql -h postgres -U dtp_admin -d dtp -v ON_ERROR_STOP=1
psql -h postgres -U dtp_admin -d dtp -v ON_ERROR_STOP=1 <<'SQL'
GRANT dtp_api TO dtp_api_login;
GRANT dtp_worker TO dtp_worker_login;
REVOKE CONNECT ON DATABASE dtp FROM PUBLIC;
GRANT CONNECT ON DATABASE dtp TO dtp_owner,dtp_api_login,dtp_worker_login,dtp_admin;
SQL
export PGPASSWORD="$(cat /run/secrets/postgres-owner.password)"
psql -h postgres -U dtp_owner -d dtp -v ON_ERROR_STOP=1 -f /infra/local-seed-keycloak.sql
echo 'Dedicated runtime grants and synthetic seed completed'
