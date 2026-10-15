#!/bin/bash
set -euo pipefail
export KAFKA_HEAP_OPTS='-Xms32m -Xmx128m'
bin=/opt/kafka/bin
config=/run/secrets/kafka-admin.properties
for topic in dtp.transfer-events.v1 dtp.transfer-events.v1.dlt; do
  "$bin/kafka-topics.sh" --bootstrap-server kafka:9093 --command-config "$config" --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=131072
done
"$bin/kafka-acls.sh" --bootstrap-server kafka:9093 --command-config "$config" --add --allow-principal User:worker --operation Read --operation Write --operation Describe --topic dtp.transfer-events.v1
"$bin/kafka-acls.sh" --bootstrap-server kafka:9093 --command-config "$config" --add --allow-principal User:worker --operation Write --operation Describe --topic dtp.transfer-events.v1.dlt
"$bin/kafka-acls.sh" --bootstrap-server kafka:9093 --command-config "$config" --add --allow-principal User:worker --operation Read --group ledger-posting-v1
"$bin/kafka-acls.sh" --bootstrap-server kafka:9093 --command-config "$config" --add --allow-principal User:worker --operation Describe --cluster
"$bin/kafka-acls.sh" --bootstrap-server kafka:9093 --command-config "$config" --add --allow-principal User:activity --operation Read --operation Describe --topic dtp.transfer-events.v1
"$bin/kafka-acls.sh" --bootstrap-server kafka:9093 --command-config "$config" --add --allow-principal User:activity --operation Read --group activity- --resource-pattern-type prefixed
echo 'Local topics and distinct Kafka ACLs completed'
