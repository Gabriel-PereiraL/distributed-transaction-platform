#!/bin/sh
set -eu
main="$(cat /opt/app/main-class)"
if [ "${1:-}" = migrate ]; then
  main=dev.dtp.api.LocalMigrations
  shift
fi
exec java -Xms64m -Xmx256m -cp '/opt/app/classes:/opt/app/lib/*' "$main" "$@"
