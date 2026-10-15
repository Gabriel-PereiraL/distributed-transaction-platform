#!/bin/bash
set -euo pipefail
cp /run/secrets/mongo.key /data/configdb/keyfile
chown mongodb:mongodb /data/configdb/keyfile
chmod 400 /data/configdb/keyfile
exec docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --keyFile /data/configdb/keyfile --wiredTigerCacheSizeGB 0.25
