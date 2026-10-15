terraform {
  required_providers { aws = { source = "hashicorp/aws" } }
}
variable "prefix" { type = string }
variable "region" { type = string }
variable "account_id" { type = string }
variable "vpc_cidr" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "application_group" { type = string }
variable "certificate_arn" { type = string }
variable "services" {
  type = map(object({ image = string, port = number, environment = map(string), secret_arns = map(string) }))
}
resource "aws_ecs_cluster" "main" {
  name = var.prefix
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
resource "aws_cloudwatch_log_group" "application" {
  for_each          = var.services
  name              = "/dtp/${var.prefix}/${each.key}"
  retention_in_days = 7
}
locals {
  task_trust = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }] })
  web        = { for key, service in var.services : key => service if key != "ledger-worker" }
}
resource "aws_iam_role" "execution" {
  for_each           = var.services
  name               = "${var.prefix}-${each.key}-execution"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role" "task" {
  for_each           = var.services
  name               = "${var.prefix}-${each.key}-task"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role_policy" "execution" {
  for_each = var.services
  role     = aws_iam_role.execution[each.key].name
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"], Resource = "arn:aws:ecr:${var.region}:${var.account_id}:repository/${var.prefix}/${each.key}" },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.application[each.key].arn}:*" },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = values(each.value.secret_arns) }
  ] })
}
resource "aws_ecs_task_definition" "application" {
  for_each                 = var.services
  family                   = "${var.prefix}-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  container_definitions = jsonencode([{
    name                   = each.key, image = each.value.image, user = "10001:10001", essential = true,
    readonlyRootFilesystem = true, stopTimeout = 30,
    portMappings           = [{ containerPort = each.value.port, protocol = "tcp" }],
    environment            = [for key, value in each.value.environment : { name = key, value = value }],
    secrets                = [for key, value in each.value.secret_arns : { name = key, valueFrom = value }],
    logConfiguration       = { logDriver = "awslogs", options = { awslogs-group = aws_cloudwatch_log_group.application[each.key].name, awslogs-region = var.region, awslogs-stream-prefix = "runtime" } },
    healthCheck            = { command = ["CMD", "java", "-Xmx32m", "-cp", "/opt/app/health", "ContainerHealth"], interval = 15, timeout = 5, retries = 3, startPeriod = 120 },
    mountPoints            = [{ sourceVolume = "temporary", containerPath = "/tmp", readOnly = false }]
  }])
  volume { name = "temporary" }
}
resource "aws_security_group" "alb" {
  name        = "${var.prefix}-internal-alb"
  description = "Private HTTPS ingress only"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "alb" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Private HTTPS clients"
}
resource "aws_vpc_security_group_ingress_rule" "application" {
  for_each                     = local.web
  security_group_id            = var.application_group
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = "Internal ALB to selected HTTP host"
}
resource "aws_vpc_security_group_egress_rule" "alb" {
  for_each                     = local.web
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = var.application_group
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = "ALB to private application HTTP"
}
resource "aws_lb" "application" {
  name                       = var.prefix
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = var.subnet_ids
  enable_deletion_protection = true
  drop_invalid_header_fields = true
}
resource "aws_lb_target_group" "application" {
  for_each             = local.web
  name                 = "${var.prefix}-${each.key}"
  port                 = each.value.port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30
  health_check {
    path     = "/actuator/health/readiness"
    protocol = "HTTP"
    matcher  = "200"
  }
}
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.application.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.application["ledger-api"].arn
  }
}
resource "aws_lb_listener_rule" "activity" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.application["activity"].arn
  }
  condition {
    path_pattern { values = ["/v1/activity/*"] }
  }
}
resource "aws_ecs_service" "application" {
  for_each        = var.services
  name            = each.key
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.application[each.key].arn
  desired_count   = 1
  launch_type     = "FARGATE"
  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.application_group]
    assign_public_ip = false
  }
  dynamic "load_balancer" {
    for_each = each.key == "ledger-worker" ? [] : [each.key]
    content {
      target_group_arn = aws_lb_target_group.application[each.key].arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }
  depends_on = [aws_lb_listener.https]
}
output "runtime" {
  value = { for key, task in aws_ecs_task_definition.application : key => { containers = jsondecode(task.container_definitions), public_ip = aws_ecs_service.application[key].network_configuration[0].assign_public_ip } }
}
