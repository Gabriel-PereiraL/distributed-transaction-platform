terraform {
  required_providers { aws = { source = "hashicorp/aws" } }
}
variable "prefix" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "application_group" { type = string }
resource "aws_security_group" "postgres" {
  name        = "${var.prefix}-postgres"
  description = "Financial database reachable only by application identity group"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "postgres" {
  security_group_id            = aws_security_group.postgres.id
  referenced_security_group_id = var.application_group
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Private PostgreSQL application connectivity"
}
resource "aws_vpc_security_group_egress_rule" "postgres" {
  security_group_id            = var.application_group
  referenced_security_group_id = aws_security_group.postgres.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Application to private PostgreSQL"
}
resource "aws_db_subnet_group" "postgres" {
  name       = "${var.prefix}-postgres"
  subnet_ids = var.subnet_ids
}
resource "aws_db_instance" "postgres" {
  identifier                      = "${var.prefix}-postgres"
  engine                          = "postgres"
  engine_version                  = "17.6"
  instance_class                  = "db.t4g.small"
  allocated_storage               = 20
  max_allocated_storage           = 100
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "dtp"
  username                        = "dtp_owner"
  manage_master_user_password     = true
  publicly_accessible             = false
  multi_az                        = true
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.prefix}-postgres-final"
  backup_retention_period         = 7
  auto_minor_version_upgrade      = true
  db_subnet_group_name            = aws_db_subnet_group.postgres.name
  vpc_security_group_ids          = [aws_security_group.postgres.id]
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
}
resource "aws_security_group" "kafka" {
  name        = "${var.prefix}-kafka"
  description = "Private SASL SCRAM TLS client traffic"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "kafka" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = var.application_group
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
  description                  = "Application to private Kafka SCRAM TLS"
}
resource "aws_vpc_security_group_egress_rule" "kafka" {
  security_group_id            = var.application_group
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
  description                  = "Private Kafka egress"
}
resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/dtp/${var.prefix}/kafka"
  retention_in_days = 7
}
resource "aws_kms_key" "kafka" {
  description             = "Dedicated encryption key for private Kafka storage"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}
resource "aws_msk_cluster" "kafka" {
  cluster_name           = "${var.prefix}-kafka"
  kafka_version          = "4.2.x"
  number_of_broker_nodes = length(var.subnet_ids)
  broker_node_group_info {
    instance_type   = "kafka.m5.large"
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.kafka.id]
    storage_info {
      ebs_storage_info { volume_size = 100 }
    }
    connectivity_info {
      public_access { type = "DISABLED" }
    }
  }
  client_authentication {
    unauthenticated = false
    sasl { scram = true }
  }
  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.kafka.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }
}
resource "aws_vpc_security_group_ingress_rule" "kafka_peers" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "-1"
  description                  = "Private broker-to-broker traffic"
}
resource "aws_vpc_security_group_egress_rule" "kafka_peers" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "-1"
  description                  = "Private broker-to-broker egress"
}
output "postgres_address" { value = aws_db_instance.postgres.address }
output "kafka_bootstrap" { value = aws_msk_cluster.kafka.bootstrap_brokers_sasl_scram }
output "security" {
  value = { postgres_private = !aws_db_instance.postgres.publicly_accessible, postgres_encrypted = aws_db_instance.postgres.storage_encrypted,
    postgres_protected = aws_db_instance.postgres.deletion_protection, kafka_tls = aws_msk_cluster.kafka.encryption_info[0].encryption_in_transit[0].client_broker,
    kafka_anonymous    = aws_msk_cluster.kafka.client_authentication[0].unauthenticated,
    kafka_kms_key      = aws_msk_cluster.kafka.encryption_info[0].encryption_at_rest_kms_key_arn,
  kafka_key_rotation = aws_kms_key.kafka.enable_key_rotation }
}
