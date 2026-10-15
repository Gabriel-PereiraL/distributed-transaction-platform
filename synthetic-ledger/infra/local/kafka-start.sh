#!/bin/bash
set -euo pipefail
export KAFKA_HEAP_OPTS='-Xms256m -Xmx512m'
/opt/kafka/bin/kafka-storage.sh format --ignore-formatted -t MkU3OEVBNTcwNTJENDM2Qk -c /run/secrets/kafka-server.properties
exec /opt/kafka/bin/kafka-server-start.sh /run/secrets/kafka-server.properties
