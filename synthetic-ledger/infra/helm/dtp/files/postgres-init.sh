#!/bin/bash
set -euo pipefail
read_password() {
  local value
  value="$(cat "/run/secrets/$1")"
  [[ "$value" =~ ^[0-9a-f]{40}$ ]] || exit 2
  printf '%s' "$value"
}
owner="$(read_password postgres-owner.password)"
api="$(read_password postgres-api.password)"
worker="$(read_password postgres-worker.password)"
keycloak="$(read_password postgres-keycloak.password)"
psql --username "$POSTGRES_USER" --dbname postgres -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE dtp_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$owner';
CREATE ROLE dtp_api_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$api';
CREATE ROLE dtp_worker_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$worker';
CREATE ROLE dtp_keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$keycloak';
CREATE DATABASE dtp OWNER dtp_owner;
CREATE DATABASE keycloak OWNER dtp_keycloak;
SQL
