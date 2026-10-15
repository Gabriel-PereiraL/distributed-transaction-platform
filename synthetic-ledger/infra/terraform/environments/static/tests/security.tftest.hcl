# All graph evaluation is mocked and plan-only; no real provider/account operations.
mock_provider "aws" {
  override_during = plan
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::000000000000:role/static-mocked" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/static/0000000000000000" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/static/0000000000000000" }
  }
  mock_resource "aws_msk_cluster" {
    defaults = { bootstrap_brokers_sasl_scram = "mocked.example.invalid:9096" }
  }
  mock_resource "aws_db_instance" {
    defaults = { address = "mocked.example.invalid" }
  }
}
run "private_encrypted_data" {
  command = plan
  assert {
    condition     = output.security.postgres_private && output.security.postgres_encrypted && output.security.postgres_protected
    error_message = "Financial database must be private, encrypted and deletion-protected."
  }
  assert {
    condition     = output.security.kafka_tls == "TLS" && !output.security.kafka_anonymous
    error_message = "Kafka client transport must use TLS and require authentication."
  }
  assert {
    condition     = startswith(output.security.kafka_kms_key, "arn:aws:kms:") && output.security.kafka_key_rotation
    error_message = "Kafka storage must use the dedicated rotating encryption key."
  }
}
run "bounded_unprivileged_runtime" {
  command = plan
  assert {
    condition     = length(output.runtime) == 3 && alltrue([for runtime in values(output.runtime) : !runtime.public_ip])
    error_message = "Exactly three runtime processes must stay private."
  }
  assert {
    condition     = alltrue([for runtime in values(output.runtime) : runtime.containers[0].user == "10001:10001" && runtime.containers[0].readonlyRootFilesystem && runtime.containers[0].stopTimeout == 30])
    error_message = "Runtime must be non-root/read-only with a bounded shutdown budget."
  }
  assert {
    condition     = alltrue([for runtime in values(output.runtime) : alltrue([for secret in runtime.containers[0].secrets : startswith(secret.valueFrom, "arn:aws:secretsmanager:")])])
    error_message = "Runtime credentials must be injected from explicit secret references."
  }
}
