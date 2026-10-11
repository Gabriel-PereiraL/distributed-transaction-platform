# Pesquisa de vagas e fontes — 11/10/2026

Consulta em America/Sao_Paulo. Amostra intencional de oito anúncios, não estatística do mercado. Todas as cinco URLs fornecidas tiveram descrição acessível. Datas relativas do LinkedIn não foram convertidas em datas exatas; anúncio acessível não garante vaga ainda aberta.

## Vagas e classificação

R = requisito declarado; D = diferencial/desejável; A = responsabilidade/ambiente sem classificação de obrigatoriedade. “Pleno” no título não torna uniformes os níveis e a senioridade dos anúncios.

| ID | Empresa / anúncio | Conteúdo verificado e distinções |
|---|---|---|
| V1 | [Cedro — Java 21 PL/SR](https://br.linkedin.com/jobs/view/4460554625) | R: Java 21, Boot, APIs, serviços distribuídos, padrões, SQL (PostgreSQL ou Oracle), NoSQL, testes/mocks/cobertura, Git/CI, AWS, observabilidade, conhecimentos de mercado de capitais. D: Kafka ou SQS/SNS, ElastiCache, DynamoDB, bases temporais/colunares, FIX, containers, experiência financeira e baixa latência. Redis aparece no conjunto NoSQL e também em diferenciais especializados. Não converter todos os serviços AWS em requisitos do novo produto. |
| V2 | [4DF Connect — Java Backend/AWS](https://br.linkedin.com/jobs/view/4470641569) | R: Java 17+, família Spring/JPA, REST/OpenAPI, sistemas distribuídos, SQL/NoSQL, Redis, AWS, hexagonal/DDD/SOLID, concorrência, idempotência, resiliência, Docker, Kubernetes ou ECS, API Gateway, CI e observabilidade. D: volumetria/baixa latência, otimização SQL, JVM/GC e ferramentas como Prometheus/Grafana. O anúncio também pede orientação técnica; tecnologia sozinha não demonstra isso. |
| V3 | [Nava — Java Pleno](https://br.linkedin.com/jobs/view/4409037386) | A: Java/Boot, Kafka, API-First, PostgreSQL/MongoDB, conexão com legado mainframe, testes, CI/Kubernetes, observabilidade/resiliência e revisão. A descrição não separa lista obrigatória/diferenciais; matriz conserva classificação A. Mainframe não pode ser declarado coberto por um mock HTTP. |
| V4 | [South System — Java Pleno](https://br.linkedin.com/jobs/view/4463122444) | R: Java 8+, Boot/Data/Security, PostgreSQL/MySQL, MongoDB/Redis, serviços distribuídos, Kafka/RabbitMQ/SQS, Docker/Kubernetes, CI, Git e práticas ágeis. D: Open Finance. A enumeração de brokers não justifica usar os três simultaneamente. |
| V5 | [People Partners — Java Pleno/Sênior](https://br.linkedin.com/jobs/view/4435439317) | R: Java 21, Boot, MongoDB, serviços independentes e DDD. D: Kafka, Keycloak, Grafana, Vue e domínio segurador. A proposta cobre a parte backend; Vue e seguros ficam explicitamente fora. |
| V6 | [CashMe — Java Backend PL](https://br.linkedin.com/jobs/view/4464145645) | R: fundamentos Java, JPA, Boot, APIs, JUnit/Mockito, SQL, padrões/SOLID/MVC e fluxo Git. D/desejável: Docker/Kubernetes/AWS; Testcontainers e WireMock aparecem como fatores de destaque. O metadata de nível diverge do título; usar descrição técnica sem inferir senioridade pelo campo automático. |
| V7 | [Bradesco — Backend Java PL](https://br.linkedin.com/jobs/view/4449688067) | R: Java 21, Boot 4, hexagonal/MVC/BFF, MongoDB/Redis, Azure, REST/Git/CI. D: microsserviços/volume, Docker/Kubernetes, observabilidade e OAuth2/JWT/OIDC, setor financeiro. Uma arquitetura AWS-ready não comprova Azure; API backend sem frontend não comprova BFF. |
| V8 | [Sicredi — CAS / Engenharia de Software PL](https://sicredi.gupy.io/jobs/12418986?jobBoardSource=gupy_public_page) | R: Java OO/padrões, React, REST, arquitetura, Kubernetes/Spring Cloud, Kafka, testes unitários/serviços distribuídos, desenvolvimento seguro e colaboração. Não há exigência de MongoDB/Redis/PostgreSQL na lista acessível. Não transportar esses requisitos de outro anúncio agregado para esta vaga. |

Anúncio adicional pesquisado: [Docket / backend pleno](https://docket.gupy.io/jobs/11875192) apareceu na busca, mas a página original não foi acessível pela ferramenta. Excluído da matriz verificada. Sites agregadores foram usados somente para descoberta, não para acrescentar requisitos às vagas acima.

## Consolidação crítica

O núcleo recorrente da amostra é Java/Spring, API, qualidade e integração, com variações em SQL/NoSQL, mensageria, cloud e operação. Não é correto transformar a união dos anúncios em uma stack obrigatória. Kafka, Redis e MongoDB podem contribuir para aprendizado específico; Oracle, FIX, Vue, React, mainframe, Azure e vários brokers não devem entrar no produto por contagem de palavras.

Experiência profissional, operação de alta disponibilidade, liderança e domínio financeiro exigem evidência além de um projeto individual. A matriz distingue requisito de demonstração em laboratório. Nenhuma cobertura implementada será declarada nesta etapa.

## Baseline Java/Spring

A página oficial apresenta Spring Boot 4.1.1 como estável e informa compatibilidade Java 17–26, incluindo Java 21. O anúncio V7 também pede Boot 4. Recomenda-se **Java 21 + Boot 4.1.x**, patch suportado revalidado antes do scaffold. [Requisitos oficiais](https://docs.spring.io/spring-boot/system-requirements.html)

Spring informa que 3.5.16 foi a última release OSS da linha 3.5 e orienta migração para 4.0/4.1 ou suporte comercial. Escolher Boot 3 apenas por familiaridade iniciaria este novo portfólio numa linha sem continuidade OSS. A autorização do pedido permite versão estável compatível, portanto Boot 4 é uma escolha justificável. [Comunicado de suporte](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

JUnit Jupiter e bibliotecas Spring terão versões compatíveis com o BOM escolhido, sem impor JUnit 5 por cima de outra geração gerenciada. Testcontainers, WireMock, JaCoCo, cliente Kafka e documentação OpenAPI precisam de resolução/compilação no Gate de fundação; ainda não existe build que comprove compatibilidade conjunta.

## Referências técnicas que sustentam a proposta

| Tema | Fonte primária | Uso no desenho |
|---|---|---|
| Kafka | [Design e entrega](https://kafka.apache.org/41/design/design/) | Offset, replay, deduplicação fora do broker e ordenação por partição; não fixar versão do broker pela URL documental |
| Retries Kafka | [Spring Kafka — retries](https://docs.spring.io/spring-kafka/reference/retrytopic.html), [guia de semânticas da estratégia](https://docs.spring.io/spring-kafka/docs/3.0.x/reference/html/) | Avaliar perda de ordenação por retry topics; o guia antigo serve à semântica, não ao código/configuração da linha nova |
| Redis | [INCR](https://redis.io/docs/latest/commands/incr/) | Contador/TTL precisam de operação atômica; cache não é saldo autoritativo |
| MongoDB | [Atomicidade](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/), [índices únicos](https://www.mongodb.com/docs/manual/core/index-unique/) | Projeção idempotente, documentos e índices; atomicidade por documento não equivale a atomicidade do conjunto |
| OIDC/JWT | [Keycloak](https://www.keycloak.org/securing-apps/oidc-layers), [Spring Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) | Issuer/JWKS, validação JWT e mapeamento de autorização |
| Testes isolados | [Testcontainers Java](https://java.testcontainers.org/quickstart/junit_5_quickstart/) | Serviços descartáveis; não usar bases dos Fulfillment Hubs |
| Contexto distribuído | [OpenTelemetry](https://opentelemetry.io/pl/docs/concepts/context-propagation/) | Inject/extract em outbox e Kafka; não só MDC |
| Métricas | [Prometheus](https://prometheus.io/docs/practices/histograms/) | Histogramas e agregação de quantis |
| Carga | [k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/), [métricas](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/) | Taxa de chegada, percentis, falhas e dropped iterations |
| Containers AWS | [Guia de decisão AWS](https://docs.aws.amazon.com/decision-guides/latest/decision-guides/choosing-aws-container-service.html) | ECS/EKS são orquestração; Fargate é capacidade, não alternativa do mesmo nível |
| Custos | [Fargate](https://aws.amazon.com/fargate/pricing/), [EKS](https://aws.amazon.com/eks/pricing/), [MSK](https://aws.amazon.com/msk/pricing/), [RDS PostgreSQL](https://aws.amazon.com/rds/postgresql/pricing/), [ElastiCache](https://aws.amazon.com/elasticache/pricing/), [DocumentDB](https://aws.amazon.com/documentdb/pricing/) | Estimativa parcial explícita e fatores que ainda precisam de dimensionamento; DocumentDB não assumido equivalente a Mongo |
| Codex | [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md), [skills](https://learn.chatgpt.com/docs/build-skills) | AGENTS.md encaminha para CODEX.md/PROGRESSO.md; skill não preserva contexto não salvo |

As fontes de OTel/Prometheus/Testcontainers/k6 também foram consultadas na auditoria anterior desta mesma conversa. Não repetir consultas para conclusões estáveis; revalidar versões e detalhes de API antes da implementação.

## Proveniência das skills

- [java-development](https://github.com/zander-zyx/java-development-skill/tree/66e9a49b06d44ae0acb0e2b90d1f09fedef1e5a0): roteiro Java, estrutura por contexto, JPA e testes; não usar defaults de templates que contradigam JPA/Java 21.
- [token-saver](https://github.com/ryohgicc/token-saver-skill/tree/734e6910c25742906f5003dd80f38559c15c859a): leituras focadas, saídas limitadas e handoff compacto. Não há benchmark de economia de tokens nesta sessão.
- Instalação via helper local da skill-installer, com revisão fixada. Arquivos instalados lidos; nenhum script de projeto de terceiros executado.
