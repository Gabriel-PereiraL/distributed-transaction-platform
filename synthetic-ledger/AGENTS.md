# Synthetic Ledger

Ler ../PROGRESSO.md e ../CODEX.md. F0/F1 autorizadas em 11/10/2026; fases posteriores exigem autorização.
Usar java-development/token-saver. Java 21; Maven Wrapper; Spring Boot BOM.
Sem deploy/cloud/AWS/apply; sem alterações nos Fulfillment Hubs.
Domínio/aplicação sem Spring/JPA. PostgreSQL é autoridade; testes usam Testcontainers sem reuse ou banco externo.
F2 local concluída: 71 testes passaram. F3–F8 locais aprovadas explicitamente em 11/10/2026 conforme proposta: Mongo/Redis, segurança, observabilidade, benchmark, containers e validação final. Validar cada gate; Terraform somente estático. ACCEPTED não significa dinheiro transferido; nenhum dinheiro real.
Antes de entregar: ./mvnw verify e ./mvnw -Pintegration verify com Docker; registrar execuções reais e atualizar ../PROGRESSO.md.
