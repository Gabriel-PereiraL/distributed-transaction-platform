terraform {
  required_providers { aws = { source = "hashicorp/aws" } }
}
variable "prefix" { type = string }
variable "region" { type = string }
variable "azs" { type = list(string) }
variable "cidr" { type = string }
resource "aws_vpc" "main" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.prefix }
}
resource "aws_subnet" "private" {
  count                   = length(var.azs)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.cidr, 8, count.index + 10)
  availability_zone       = var.azs[count.index]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.prefix}-private-${count.index}" }
}
resource "aws_security_group" "application" {
  name        = "${var.prefix}-application"
  description = "Private application traffic and explicit dependency egress"
  vpc_id      = aws_vpc.main.id
}
resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.application.id
  cidr_ipv4         = var.cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Private service endpoints only"
}
resource "aws_security_group" "endpoints" {
  name        = "${var.prefix}-endpoints"
  description = "TLS from application tasks"
  vpc_id      = aws_vpc.main.id
}
resource "aws_vpc_security_group_ingress_rule" "endpoints" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.application.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "Application TLS to private AWS endpoints"
}
resource "aws_vpc_endpoint" "interface" {
  for_each            = toset(["ecr.api", "ecr.dkr", "logs", "secretsmanager"])
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true
}
resource "aws_route_table" "private" {
  count  = length(var.azs)
  vpc_id = aws_vpc.main.id
}
resource "aws_route_table_association" "private" {
  count          = length(var.azs)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id
}
output "vpc_id" { value = aws_vpc.main.id }
output "subnet_ids" { value = aws_subnet.private[*].id }
output "application_group" { value = aws_security_group.application.id }
output "private_ip_assignment" { value = alltrue([for subnet in aws_subnet.private : !subnet.map_public_ip_on_launch]) }
