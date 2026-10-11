# Distributed Transaction Processing Platform

Plataforma de portfólio para transferências sintéticas, construída em Java 21 e Spring Boot, com PostgreSQL como autoridade financeira, Kafka para transporte de eventos, MongoDB para histórico de leitura e Redis para quotas.

O código executável e suas instruções estão em [synthetic-ledger](synthetic-ledger/README.md). A proposta, matriz de requisitos, pesquisa e checkpoints estão nesta pasta.

## Estado em 11/10/2026

- F0–F4 concluídas localmente: 129 testes no gate completo da F4, além da validação de contratos. Evidências em [VALIDATION_F4.md](synthetic-ledger/docs/VALIDATION_F4.md).
- F5 parcial: propagação W3C de tracing no worker/outbox e três testes unitários aprovados. A última regressão Kafka teve 17 testes aprovados e um timeout no cenário de broker indisponível; precisa ser investigado antes do gate F5.
- Collector, dashboards, alertas, benchmarks e demais fases futuras continuam pendentes. Nenhum deploy cloud foi realizado.

Somente dados e valores sintéticos. O projeto não movimenta dinheiro real e não promete entrega exatamente uma vez ponta a ponta.

Leia [PROGRESSO.md](PROGRESSO.md) para a retomada e [CODEX.md](CODEX.md) para as instruções de trabalho.
