# This root is a static/mocked blueprint, not an authorized deployment target.
terraform {
  required_version = ">= 1.16.0, < 1.17.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.69.0"
    }
  }
}
provider "aws" {
  region                      = "us-east-1"
  access_key                  = "static-validation-only"
  secret_key                  = "static-validation-only"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
}
locals {
  prefix     = "dtp-static"
  region     = "us-east-1"
  account_id = "000000000000"
  cidr       = "10.20.0.0/16"
  issuer     = "https://idp.example.invalid/realms/dtp"
  profiles   = "container"
  services = {
    ledger-api = {
      image = "example.invalid/dtp-ledger-api@sha256:0000000000000000000000000000000000000000000000000000000000000000"
      port  = 8085
      environment = { SPRING_PROFILES_ACTIVE = local.profiles, DTP_PORT = "8085", DTP_DATABASE_URL = "jdbc:postgresql://${module.data.postgres_address}:5432/dtp?sslmode=verify-full",
      DTP_DATABASE_USER = "dtp_api_login", DTP_ISSUER = local.issuer, DTP_JWK_SET_URI = "${local.issuer}/protocol/openid-connect/certs", DTP_REDIS_HOST = "redis.example.invalid" }
      secret_arns = { DTP_DATABASE_PASSWORD = "arn:aws:secretsmanager:us-east-1:000000000000:secret:dtp-api-password-abcdef",
      DTP_REDIS_PASSWORD = "arn:aws:secretsmanager:us-east-1:000000000000:secret:dtp-redis-password-abcdef" }
    }
    ledger-worker = {
      image = "example.invalid/dtp-ledger-worker@sha256:0000000000000000000000000000000000000000000000000000000000000000"
      port  = 8090
      environment = { SPRING_PROFILES_ACTIVE = "container,requires-approved-msk-auth-profile", DTP_PORT = "8090", DTP_DATABASE_URL = "jdbc:postgresql://${module.data.postgres_address}:5432/dtp?sslmode=verify-full",
      DTP_DATABASE_USER = "dtp_worker_login", DTP_KAFKA_BOOTSTRAP_SERVERS = module.data.kafka_bootstrap }
      secret_arns = { DTP_DATABASE_PASSWORD = "arn:aws:secretsmanager:us-east-1:000000000000:secret:dtp-worker-password-abcdef",
      DTP_KAFKA_WORKER_PASSWORD = "arn:aws:secretsmanager:us-east-1:000000000000:secret:AmazonMSK_dtp_worker-abcdef" }
    }
    activity = {
      image = "example.invalid/dtp-activity@sha256:0000000000000000000000000000000000000000000000000000000000000000"
      port  = 8086
      environment = { SPRING_PROFILES_ACTIVE = "container,requires-approved-msk-auth-profile", DTP_PORT = "8086", DTP_ISSUER = local.issuer,
      DTP_JWK_SET_URI = "${local.issuer}/protocol/openid-connect/certs", DTP_KAFKA_BOOTSTRAP_SERVERS = module.data.kafka_bootstrap }
      secret_arns = { DTP_MONGO_URI = "arn:aws:secretsmanager:us-east-1:000000000000:secret:dtp-mongo-uri-abcdef",
      DTP_KAFKA_ACTIVITY_PASSWORD = "arn:aws:secretsmanager:us-east-1:000000000000:secret:AmazonMSK_dtp_activity-abcdef" }
    }
  }
}
module "network" {
  source = "../../modules/network"
  prefix = local.prefix
  region = local.region
  cidr   = local.cidr
  azs    = ["us-east-1a", "us-east-1b", "us-east-1c"]
}
module "data" {
  source            = "../../modules/data-messaging"
  prefix            = local.prefix
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.subnet_ids
  application_group = module.network.application_group
}
module "application" {
  source            = "../../modules/application-identity"
  prefix            = local.prefix
  region            = local.region
  account_id        = local.account_id
  vpc_cidr          = local.cidr
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.subnet_ids
  application_group = module.network.application_group
  certificate_arn   = "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"
  services          = local.services
}
output "security" { value = module.data.security }
output "runtime" { value = module.application.runtime }
