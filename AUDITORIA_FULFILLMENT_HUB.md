# Auditoria arquitetural e técnica — Fulfillment Hub Java e .NET

Data: 11/10/2026. Escopo: análise e planejamento; nenhuma implementação autorizada ou realizada.

## 1. Parecer executivo

**Eu não acrescentaria Kafka, Redis, MongoDB, RabbitMQ e Kubernetes à arquitetura principal agora.** Os projetos já possuem os mecanismos mais importantes para este domínio: PostgreSQL, outbox transacional, idempotência, SQS, inbox de webhooks, reconciliação e compensação. O maior retorno está em demonstrar que essas garantias sobrevivem a falhas, em corrigir contratos inconsistentes e em produzir evidência operacional reproduzível.

O Java está mais avançado na demonstração de infraestrutura AWS: Terraform já existe e descreve EC2, S3, IAM, SSM, SQS/DLQ e controles de custo. Kubernetes também já existe como manifests locais. Recomendar “adicionar Terraform/Kubernetes” sem ler o repositório duplicaria trabalho. O .NET está mais avançado na separação entre aplicação e infraestrutura, em testes isolados e em observabilidade instrumentada. Não existe razão para criar paridade artificial.

Há problemas e riscos que merecem prioridade sobre novas tecnologias:

1. No .NET, o dispatcher SQS abre uma transação envolvendo o handler completo, inclusive HTTP externo. Isso invalida a durabilidade intermediária que comentários de pagamento/entrega prometem e mantém transações abertas durante retries.
2. No Java, após expirar uma chave associada a pedido, uma requisição com payload diferente pode receber o pedido antigo como replay. Uma reserva abandonada antes do pedido, por sua vez, permanece ocupada até o prazo de 24 horas.
3. A deduplicação de recebimento dos webhooks .NET não equivale à exclusão mútua do processamento: não há claim persistente no processador. O risco de disputa entre consumidores precisa de teste específico.
4. O Java registra `traceparent` em MDC e em mensagens, mas isso não cria spans consumidores nem garante continuidade de contexto. A presença das dependências de observabilidade não comprova a cadeia operacional completa.

**Três investimentos de maior retorno:** confiabilidade com testes de crash/convergência; observabilidade distribuída verificável no Java; benchmark com k6 e otimização orientada por SQL e métricas. Testcontainers Java é parte importante do primeiro investimento. Terraform deve ser aperfeiçoado, não reintroduzido.

## 2. Método, revisões e limites

Foram localizadas cópias antigas na máquina. Para evitar auditar um estado desatualizado, foram obtidas cópias novas dos HEADs remotos, em `work/`, sem alterar os repositórios originais:

| Projeto | Revisão auditada | Último commit |
|---|---|---|
| [.NET](https://github.com/Gabriel-PereiraL/fulfillment-hub/tree/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86) | `2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86` | 30/09/2026 — recuperação de retries de webhook e idempotência |
| [Java](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/tree/f82baa173a010507d5d6bcd332013d9d0dfb0cad) | `f82baa173a010507d5d6bcd332013d9d0dfb0cad` | 03/10/2026 — demonstração AWS com controles de custo |

A inspeção abrangeu fontes de domínio, endpoints, serviços transacionais, providers, consumidores, migrations, configuração, Docker, workflows, testes, manifests e infraestrutura. Documentação foi confrontada com código; seus relatos históricos não foram tratados como execução atual.

Validação executada nesta auditoria:

- `.NET`: `dotnet test --project tests/FulfillmentHub.UnitTests --configuration Release` — **137 passaram, 0 falharam, 0 ignorados**.
- `.NET`: `dotnet test --project tests/FulfillmentHub.ArchitectureTests --configuration Release` — **6 passaram, 0 falharam, 0 ignorados**.
- Docker: daemon Linux indisponível. Integração, E2E e benchmarks não foram executados.
- Java/Maven não estavam disponíveis no PATH; o caminho de build previsto usa Docker, também indisponível. Nenhuma suíte Java foi executada nesta sessão.
- Não foram consultados segredos, estado Terraform privado ou recursos da conta AWS. Não houve deploy, apply, criação de recursos ou teste de pedido na demonstração pública.
- O deploy AWS de 03/10 está documentado no repositório; **a disponibilidade e o funcionamento atuais não foram certificados**.

Os testes de integração Java usam banco configurável e executam `TRUNCATE ... CASCADE`. Não são seguros para apontar indiscriminadamente para o PostgreSQL já usado pelo projeto. Essa inspeção é uma razão concreta para priorizar isolamento, além da indisponibilidade do Docker.

Classificação usada: **C** = comportamento/estrutura comprovado por código ou execução; **R** = risco plausível cuja manifestação não foi reproduzida; **A** = oportunidade de aprendizado; **M** = evolução com valor profissional; **D** = complexidade sem requisito demonstrado. “Comprovado por código” não significa incidente observado em produção.

As avaliações profissionais são julgamento técnico para entrevistas backend, não estatística de vagas ou garantia de contratação. Não foi realizada pesquisa quantitativa de mercado. Custos são estimativas de engenharia, não cotações de serviços.

## 3. GATE 1 — inventário real

| Área | Java | .NET |
|---|---|---|
| Plataforma declarada | Java 25, Spring Boot 4.1.1, Maven multimódulo | C#/.NET 10, ASP.NET Core Minimal APIs |
| Organização | `domain`, `runtime`, `api`, `worker`, `provider-simulator` | Domain, Application, Infrastructure, Api, Worker, ProviderSimulator |
| Persistência | JPA/Hibernate, PostgreSQL, Flyway V1–V6 | EF Core/Npgsql, PostgreSQL, migrations EF |
| Consistência | `@Version`, locks pessimistas, outbox e claims com owner | `xmin`, constraints, outbox, dedup no dispatcher |
| Mensageria | SQS SDK v2, filas padrão, DLQs, LocalStack | SQS SDK .NET, filas padrão, DLQs, LocalStack; modo em processo |
| Providers | RestClient, Resilience4j | HttpClient, Microsoft.Extensions.Http.Resilience/Polly |
| Identidade | JWT HS256 + sessão persistida + refresh rotativo | JWT HS256, papéis; validação sem consulta de sessão |
| Rate limiting | Mapa local por IP em rotas de autenticação | Limitadores locais para login, webhooks e pedidos por usuário |
| Cache | Sem Redis; token de provider obtido repetidamente | Sem Redis; cache local do token de entrega com renovação coordenada |
| Observabilidade | Actuator, Micrometer/Prometheus, bridge OTel/OTLP; LGTM opcional | OTel HTTP/SQL/AWS/runtime, spans próprios, métricas de negócio, LGTM e provisioning Grafana |
| Testes | JUnit, IT PostgreSQL/LocalStack via scripts/CI, E2E PowerShell | xUnit, Shouldly, NetArchTest, Testcontainers PostgreSQL/LocalStack, E2E HTTP |
| Containers | Dockerfile multistage não-root; Compose | Dockerfiles multistage não-root; Compose |
| Infraestrutura | Terraform AWS EC2 já implementado; manifests Kubernetes locais | Terraform/ECS/RDS aparecem como planejamento, sem arquivos `.tf` encontrados |
| CI | Build/unitários, IT, E2E, Terraform validate, CodeQL, Gitleaks, Trivy | Locked restore, build/analyzers, format, advisories, testes, dependency review, Gitleaks, Trivy, E2E, CodeQL |
| Candidatas ausentes | Kafka, Redis, MongoDB, RabbitMQ, Elasticsearch/OpenSearch, k6, gRPC, Testcontainers Java | Kafka, Redis, MongoDB, RabbitMQ, Elasticsearch/OpenSearch, k6, gRPC, Terraform implementado, Kubernetes |

Evidências: J1–J12 e N1–N11 no catálogo ao final. Versões acima são as declaradas nos arquivos, não uma validação de todas as versões publicadas ou recomendação de upgrade.

## 4. Diagnóstico Java

### Arquitetura e fluxo

É um sistema com banco compartilhado e processos API/worker separados; não uma coleção de microsserviços independentes. `domain` é independente de Spring, mas vários workflows em `runtime` manipulam diretamente rows JPA, SQL, status textuais e histórico, em vez de reutilizar os agregados de domínio. A criação de pedido usa `Order`/`Product`; pagamento e entrega possuem sua própria lógica em serviços. Essa diferença aumenta o risco de regras divergirem. Não exige copiar a arquitetura .NET: exige definir onde cada invariante é realmente garantida. [J2–J6]

Fluxo observado: HTTP autenticado → reserva idempotente → pedido + estoque + outbox + associação da chave no mesmo commit → worker publica SQS → pagamento com estado local durável → webhook persistido e verificação no provider → `OrderPaid` → entrega → reconciliação. Cancelamento devolve estoque e publica compensação. A separação entre gravações locais e HTTP nos workflows de pagamento/entrega é um ponto positivo real. [J2–J5]

### JF1 — contrato de idempotência após expiração — C, P0

Em `IdempotencyService.beginTransaction`, o mismatch só é recusado enquanto `expiresAt` está no futuro. Uma chave expirada só é reiniciada se `orderId == null`. Se já tiver pedido e status `Completed`, retorna replay, mesmo quando o novo hash é diferente. Portanto, expiração não libera a chave e pode ocultar a diferença de payload. Isso é dedutível pelo encadeamento das condições; não foi reproduzido em execução. [J2]

Além disso, uma chave `InProgress` sem pedido recebe vencimento de 24 horas, sem lease curta própria. Um crash entre `begin` e o commit do pedido deixa a operação ocupada por esse período. A recuperação de pedido já associado é boa, mas não cobre essa janela. [J2]

**Decisão:** manter PostgreSQL e especificar dois contratos: prazo de resposta replayável e identidade durável da operação. Para uma chave já associada a pedido, escolher explicitamente rejeitar novo payload independentemente do prazo; para reservas sem pedido, lease curta com owner e recuperação. Testar antes/depois de expiração e crash em ambos os lados do commit. Redis não corrige essa ambiguidade.

### JF2 — N+1 na listagem — C na estrutura, R no impacto, P1

`OrderService.list` carrega `OrderRow` e acessa `o.items.size()` para cada resumo. `OrderRow.items` é `@OneToMany` sem fetch específico para essa query. Há um padrão concreto propenso a consultas adicionais por pedido. A latência e o número exato de queries precisam de medição; não foram inventados. [J6]

Priorizar projeção com contagem no SQL e plano de execução. A paginação já usa keyset por número; o índice por cliente é `(customer_id, created_at)`, não a mesma ordenação. Medir antes de criar `(customer_id, number DESC)`. Ambos os catálogos retornam todos os produtos ativos, sem paginação; isso só se torna prioridade quando o volume justificar. [J6, N9]

### JF3 — worker com orçamento de tempo pouco explícito — C/R, P1

`OutboxPoller` reúne quatro tarefas agendadas. `SqsBroker` recebe uma mensagem por chamada e espera um segundo, apesar de configurar filas com long polling de 20 segundos. `drain(50)` é limitado internamente a dez. Não há configuração explícita de isolamento/concurrency das tarefas no código inspecionado. O scheduler efetivo deve ser confirmado no Spring Boot utilizado antes de atribuir um gargalo à sua configuração padrão. [J3, J7]

Claims de mensagens/webhooks duram 60 segundos, enquanto a visibilidade SQS é estendida para 300 segundos. A outbox renova uma vez antes de cada dispatch, sem heartbeat contínuo e sem abortar o dispatch se a renovação não atualizar nenhuma linha. Esses mecanismos reduzem disputas, mas não provam exclusão durante pausas longas ou processamento acima da lease. Owner fencing na finalização não impede, por si só, efeito externo de um dono antigo. [J3–J5]

**Decisão:** definir deadline total por operação, limite de concorrência, relação entre timeout/lease/visibilidade e política de renovação; testar duas instâncias e pausa maior que a lease. Não aumentar threads sem medir pool JDBC e pressão nos providers.

### JF4 — observabilidade parcial — C, P1

O contexto HTTP é copiado de um header de entrada para MDC e a outbox. Quando o cliente não envia `traceparent`, o filtro não grava o contexto do span servidor criado pela instrumentação. O consumidor restaura MDC, mas não extrai contexto no Tracer/Propagator nem cria span consumidor. Os RestClients são construídos diretamente com `RestClient.builder()`, sem evidência de configuração de observação equivalente ao builder gerenciado. [J3, J5, J8]

As métricas próprias encontradas no Java concentram-se em retries e estado do circuit breaker. Não foi encontrado o conjunto de métricas de pedidos, lag, backlog e DLQ equivalente ao .NET. O worker é configurado como aplicação sem web server; declarar exposição Actuator web não garante endpoint Prometheus acessível. Compose LGTM recebe endpoint de traces, mas não mostra pipeline completo de exportação/scrape das métricas; sem dashboard/alertas próprios no Java. No Compose AWS o endpoint de tracing fica vazio. [J7–J10]

**Decisão:** completar instrumentação, exportação e prova de funcionamento. Não chamar o Java de “observável ponta a ponta” apenas pelas dependências.

### JF5 — proteção HTTP atrás do proxy — C/R, P1

`RequestFilter` usa `getRemoteAddr()`; `forward-headers-strategy: none` e Caddy na AWS indicam que a partição por IP pode concentrar usuários no endereço do proxy. O comportamento real da origem deve ser medido. Há limite local apenas em autenticação; não se encontrou limite equivalente para criação de pedidos/webhooks. [J8, J10]

Primeiro corrigir reconhecimento de proxy confiável e política por usuário onde adequado. Depois considerar rate limiting compartilhado para duas réplicas, já previstas no laboratório Kubernetes. Aceitar headers encaminhados de qualquer origem seria uma regressão de segurança.

A validação JWT consulta usuário e sessão no PostgreSQL a cada requisição: custo real em queries, ainda sem evidência de saturação. Cache de sessão é arriscado porque altera o prazo de revogação. Manter a garantia atual até medir e definir explicitamente quanto atraso de revogação é aceitável. [J8]

### JF6 — dados e manutenção — C/R, P1/P2

As migrations têm constraints úteis de estoque, unicidade de payment ativo e deduplicação. Em contrapartida, diversas referências entre pedido, customer, payment e delivery são UUIDs sem FK; há diferenças de contrato, como postal code máximo de 12 no DTO e 10 no schema. O serviço de criação passa `true` para o cliente ativo sem carregar a entidade customer. Isso merece testes de integridade/validação e uma decisão explícita, especialmente se desativação de cliente for suportada. [J2, J6]

O checkout usa taxa fixa de R$ 15; o provider de entrega usa nome/telefone de destinatário fixos. São simplificações visíveis do laboratório, não falhas comprovadas de negócio com clientes reais. Tornar a política de preço explícita e separar valor cobrado de custo do provider é mais defensável que alegar integração logística real. [J2, J5]

Classes de workflows e controllers concentram responsabilidades e possuem trechos densos em uma única linha. Refatoração incremental com testes e ports de provider facilita revisão; adicionar camadas genéricas por estética não ajuda.

### JF7 — AWS e Terraform já existem — C/R, P0/P1

`main.tf` contém recursos concretos, não só um ADR. O deploy usa IAM role e SSM sem chave AWS persistida no host; EBS criptografado, IMDSv2 obrigatório, S3 privado e nenhuma porta SSH. Esses são bons sinais. [J10]

Limites: PostgreSQL e aplicações na mesma EC2; root volume com `delete_on_termination`; ausência de rotina de backup/restore no material inspecionado; S3 `force_destroy`; AMI mais recente e atualização apt/dnf durante build/bootstrap reduzem reprodução bit a bit. É uma demonstração descartável, como a documentação reconhece, não alta disponibilidade. [J10–J12]

Segredos `random_password.result` são usados em parâmetros SSM `value`: o state precisa ser tratado como segredo. `versions.tf` não declara backend remoto; isso não prova que o state esteja desprotegido na máquina do autor, pois ACLs e configuração privada não foram inspecionadas. A política SQS usa `fh-*`, mais ampla que as quatro filas que a documentação descreve. Os nomes de fila não incluem `environment`, criando risco de colisão ao provisionar dois ambientes na mesma conta/região. [J10]

**Decisão:** P0 para confirmar proteção/recuperação do state antes do próximo apply; P1 para referências exatas de ARN, isolamento por ambiente e teste de recuperação conforme o SLA escolhido. Não migrar automaticamente para EKS/RDS. Budget de parada é um controle atrasado, não limite absoluto de gasto.

## 5. Diagnóstico .NET

### Arquitetura e fluxo

Domain possui agregados e eventos; Application possui casos de uso e ports; Infrastructure implementa EF, SQS, providers e telemetria. Há testes de dependência entre camadas. API e worker são hosts distintos sobre banco compartilhado. O provider simulator é uma dependência de laboratório. Essa organização é coerente e não precisa de MediatR, repository genérico ou novos microsserviços. [N1–N4]

Pedido reserva estoque com concorrência otimista `xmin`; pedido e eventos capturados pelo interceptor entram no mesmo `SaveChanges`. A migração atual impõe chave única por cliente e idempotency key. Providers possuem retry seletivo, deadline total e por tentativa. Token de entrega já tem cache local e renovação única por processo. [N2, N3, N6, N9]

### NF1 — transação do consumidor envolve rede — C, P0

`OutboxDispatcher.DispatchAsync`, em modo SQS, inicia uma transação, salva `ProcessedMessage`, chama o handler e só depois commita; em `Retry`, faz rollback. `OrderPlacedHandler` chama `CreatePaymentForOrderHandler`; este grava tentativa antes de HTTP, mas essa gravação participa da transação externa. `OrderPaidHandler` faz o mesmo com `RequestDeliveryHandler`. [N3, N4]

Consequências comprováveis pela composição:

- O `SaveChanges` intermediário não é um commit independente quando existe a transação externa.
- Uma falha transitória devolvida como `Retry` desfaz tentativa/estado local que comentários descrevem como duráveis.
- Uma interrupção depois de criar entrega remotamente e antes do commit pode perder a reserva local da chave/quote. A chave de pagamento é derivada do pedido e continua estável; isso reduz risco de pagamento duplicado, mas não torna toda a sequência robusta.
- Transação e conexão permanecem ocupadas durante HTTP e retries.

Não foi observada duplicação financeira; o efeito remoto e a extensão de locks precisam de testes. No modo sem broker, a ausência dessa transação externa muda a garantia, fazendo testes em processo insuficientes para validar modo SQS.

**Decisão:** commit curto de intenção/claim, HTTP fora da transação, commit curto de resultado + evento + conclusão. Deduplicação final deve acompanhar a mudança local quando possível. Segurança do efeito remoto depende de chave estável e reconciliação; não prometer exactly-once remoto.

### NF2 — claims de webhooks e lease de outbox — C/R, P0/P1

`WebhookInbox.TryRecordAsync` deduplica a entrada, mas `WebhookEventProcessor.ProcessAsync` carrega o evento com `AsNoTracking`, verifica status e executa sem reservar owner/lease. Dois consumidores podem passar pela mesma verificação antes da conclusão. A aplicação e a atualização do inbox ocorrem em unidades de trabalho distintas. Concorrência otimista e regras de domínio mitigam efeitos repetidos; não provam que haverá apenas um processador nem que métricas/contagem de tentativas ficarão corretas. [N5]

`OutboxProcessor` usa lock curto e lease, mais `xmin`; isso protege atualização concorrente, mas não impede nova publicação se a lease do lote expirar enquanto o primeiro publisher ainda envia mensagens. A lease é definida para todo o lote; não há renovação individual encontrada. Mensageria já é at-least-once, portanto republicar não é automaticamente defeito; falta validar comportamento com publisher lento e múltiplas instâncias. [N3]

**Decisão:** P0 para validar/corrigir exclusão e finalização de webhook sob consumidores concorrentes; P1 para stress das leases da outbox. O comentário “exactly once” do processador de webhooks é mais forte que o código comprova.

### NF3 — idempotência recupera pedido, mas ownership merece teste — C/R, P1

A recuperação de pedido por `(CustomerId, IdempotencyKey)` resolve parte da janela de commit sem resposta. O registro usa lease de dois minutos, retenção de resposta de 24 horas e `xmin`. Entretanto `CompleteAsync`/`ReleaseAsync` não recebem owner token; um handler antigo pode disputar finalização/liberação após takeover. O token de concorrência protege o update observado, mas não identifica o dono lógico da operação. [N2, N6]

Também se compara o hash antes de tratar expiração: payload diferente permanece mismatch mesmo com registro expirado. Isso pode ser um contrato válido de chave permanente, mas precisa ser consistente com a documentação de TTL e retenção. **Não substituir o banco por Redis.** Definir a semântica e testar old owner, takeover e replay após expiração.

### NF4 — observabilidade já é substancial — C, P1 incremental

Há instrumentação ASP.NET, HTTP, Npgsql, AWS e runtime, spans consumidores, `ActivitySource`, métricas de negócio e provisionamento de dashboard/alertas. `TraceContinuityTests` já existe. O repositório contém relatório histórico de exercícios de alertas. Não é uma lacuna que justifique “adicionar OpenTelemetry/Prometheus/Grafana”. [N7]

A evolução correta é testar o exportador/collector e dashboards no ambiente real escolhido, acompanhar tempo de convergência e compensações, e verificar se métricas emitidas antes de um rollback do dispatcher contam efeitos não commitados. Não usar identificadores de pedidos/usuários como labels. Histórico de incidente local não comprova SLA de produção.

### NF5 — identidade, infraestrutura e desempenho — C/R, P1/P2

JWT valida assinatura, algoritmo, issuer, audience e expiração, mas não há sessão/revogação por consulta persistida como no Java. Esse modelo pode ser adequado com tokens curtos; não é falha de autenticação por si só. O prazo de acesso após desativar usuário deve ser explícito e testado. O pipeline já comenta a pendência de forwarded headers para deploy atrás de load balancer. Rate limiting é por processo. [N8]

Listagem de pedidos já projeta resumo com keyset, uma escolha melhor que carregar todo o agregado. O índice cliente/data não coincide com cliente/número; medir plano antes de alterar. Catálogo retorna lista completa. Retenção de inbox/outbox/dedup não tem rotina de limpeza encontrada; TTL lógico não remove linhas. Crescimento e queries de backlog precisam de baseline. [N3, N6, N9]

Não há Terraform/ECS implantado no repositório atual; ADR e roadmap são intenção. Não declarar experiência operacional de ECS a partir deles. Um deploy adicional só tem bom retorno se demonstrar algo diferente da EC2 Java e se houver disponibilidade para mantê-lo. [N10]

## 6. GATE 3 — matriz comparativa

Legenda de custo: B = baixo, M = médio, A = alto, considerando implementação e manutenção. Prioridade é contextual; tecnologias já presentes devem ser completadas em vez de reimplementadas.

| Tecnologia | Estado/problema candidato | Projeto preferido | Decisão | Prioridade | Custo/retorno |
|---|---|---|---|---|---|
| Kafka | Replay e projeções independentes ainda não são requisito | Java, laboratório | Experimentar isoladamente após P0/P1 | P2 | A / aprendizado alto, retorno operacional atual baixo |
| Redis | Limites locais em múltiplas réplicas; leituras repetidas sem benchmark | Java, rate limiter opcional | Experimento condicionado; adiar cache geral | P2 | M / valor depende de prova |
| MongoDB | Sem domínio documental independente demonstrado | Nenhum | Descartar do núcleo | P3 | A / baixo retorno atual |
| Kubernetes | Manifests Java já presentes; não há demanda por cluster gerenciado | Java local | Validar laboratório; adiar EKS | P2/P3 | M local, A operação / retorno limitado |
| Terraform | AWS Java já gerida declarativamente; state e isolamento merecem atenção | Java | Aperfeiçoar existente | P0/P1 | M / alto |
| RabbitMQ | Duplicaria SQS; roteamento AMQP não é requisito | .NET apenas como alternativa isolada | Adiar | P3 | M–A / baixo atual |
| Elasticsearch/OpenSearch | Busca avançada não aparece nos fluxos | Nenhum agora | Adiar | P3 | A / baixo atual |
| OpenTelemetry | .NET completo em código; Java com continuidade/exportação parcial | Java | Completar; validar .NET incrementalmente | P1 | M / alto |
| Prometheus/Grafana | Métricas .NET existentes; Java sem cadeia operacional demonstrada | Java | Completar dashboards, alertas e exportação | P1 | M / alto |
| Testcontainers | Presente .NET; Java usa banco compartilhável com truncates | Java | Implementar isolamento dos IT | P1 | M / alto |
| k6 | Ausência de baseline de capacidade e p95/p99 | Ambos, começar Java | Implementar campanha reproduzível | P1 | B–M / alto |
| gRPC | Sem comunicação interna de baixa latência que exija outro protocolo | Laboratório Java/.NET opcional | Adiar; experimento se objetivo explícito | P3/P2 | M / baixo atual |

### Avaliação individual — dez perguntas por tecnologia

Cada ficha cobre: (1) problema, (2) solução atual, (3) alternativa simples, (4) benefício mensurável, (5) custo, (6) complexidade, (7) projeto, (8) demonstração, (9) portfólio, (10) decisão. Esforços em dias de trabalho concentrado de uma pessoa, com familiaridade razoável; não incluem espera por conta, quota, revisão ou aprendizado inicial extenso. Estimativas não são compromissos de prazo.

#### Kafka

**1–3.** Não foi encontrado consumidor independente que precise reconstruir estado a partir do log. Eventos atuais comandam pagamento, entrega e compensação; SQS + PostgreSQL atendem esse propósito. Fanout simples pode ser SNS + filas dedicadas; reconstrução de um relatório pode começar por SQL/snapshot. **4.** Benefício seria reconstruir uma projeção após perder seu banco, sem repetir cobrança/entrega, e permitir consumidores com offsets independentes. **5–6.** PoC 3–5 dias; robusto 10–20+, incluindo schema evolution, particionamento, lag, segurança e recuperação. Complexidade injustificada no núcleo atual. **7–8.** Java, laboratório de projeção de histórico operacional: dois consumer groups, replay desde o início, chave por pedido, crash antes/depois do commit da projeção e dedup. **9–10.** Bom conteúdo de entrevista se explicar ordenação por partição, replay e consistência; **experimentar isoladamente, P2**. [J3, N3; T1]

Não conectar replay aos handlers que movem efeitos externos. Kafka não transforma POST no provider em exactly-once. Não usar outbox como event store completo: os payloads Java contêm essencialmente `orderId`, não snapshots imutáveis suficientes para reconstruir todo o histórico. Exigiria um contrato de eventos próprio e versionado.

#### Redis

**1–3.** Há uma lacuna real de limite compartilhado entre réplicas. Há consultas de catálogo e sessão, mas frequência e custo não foram medidos. Hoje limites locais atendem instância única; cache do token de entrega .NET já existe. No Java, cache local com expiração para token do provider é a primeira alternativa; para catálogo, projeção/paginação e índices vêm antes. **4.** Medir aceitação global dentro do orçamento em duas APIs, redução de chamadas de token/SQL, p95, hit ratio e custo de misses. **5–6.** PoC 1–3 dias; robusto 5–10, com TTL, atomicidade, indisponibilidade, segurança e métricas. Invalidação e dependência nova podem superar o ganho. **7–8.** Java em laboratório com duas réplicas: limite atômico, TTL, Redis indisponível e política fail-open/fail-closed por rota. **9–10.** Valor em concorrência distribuída; **experimentar para rate limiting, P2; adiar cache geral**. [J8, N6, N8, N9; T2]

Não usar Redis como autoridade de estoque, substituto de outbox ou única prova de operação idempotente. Não cachear a resposta completa do catálogo com estoque como se fosse atual. Cache de sessão exige definir atraso de revogação e comportamento em falha.

#### MongoDB

**1–3.** Nenhum domínio documental independente foi identificado. Pedido, pagamento, entrega e reserva têm relações e invariantes transacionais; PostgreSQL é adequado. Dados variáveis de provider podem continuar em JSONB; .NET já persiste payloads JSONB. **4.** Só haveria benefício mensurável com consulta documental concreta, shape variável e redução comprovada de joins/latência. Não há esse benchmark. **5–6.** PoC 2–4 dias; robusto 8–15+, com índices, validação, sincronização e recuperação. Segundo banco adicionaria backup e consistência entre stores. **7–8.** Nenhum projeto principal; se surgir catálogo com atributos realmente variáveis, comparar JSONB e Mongo com o mesmo dataset antes da decisão. **9–10.** Ganho baixo sem requisito; **descartar agora, P3**. [J6, N9; T3]

MongoDB não é tecnicamente incapaz de transações; a rejeição é por ausência de benefício e pelo custo da mudança, não por uma caricatura de NoSQL.

#### Kubernetes

**1–3.** Os manifests Java já mostram Deployments, recursos, probes, Jobs e hardening. A demonstração AWS usa um host; Compose atende seu objetivo. Nenhum requisito de operação de cluster foi demonstrado. **4.** Ganho local: comprovar rollout, recuperação de pod, readiness e duas APIs; não presumir ganho de throughput. **5–6.** Validar PoC existente 1–3 dias; laboratório robusto 5–8; operação gerenciada 15–30+ e manutenção contínua. EKS acrescentaria plano de controle, rede, secrets e upgrades. **7–8.** Java local com Minikube/Kind; matar pod, atualizar imagem, sequenciar migration/seed, validar acesso/probes e limite distribuído. **9–10.** Bom aprendizado de operação se houver evidência; **validar laboratório P2, adiar cluster pago P3**. [J11; T4]

O Job de migration no YAML não estabelece automaticamente uma dependência de inicialização dos Deployments. O roteiro deve verificar ordenação. Dependências locais e secrets de exemplo não são manifests prontos para produção. Não acrescentar Helm, Argo CD, service mesh ou HPA antes de demonstrar necessidade e sinais de escala.

#### Terraform

**1–3.** Já gerencia a AWS Java. Lacunas: comprovação de proteção do state, nomes compartilhados, IAM mais amplo que o descrito, recuperação da infraestrutura/dados. A alternativa simples é manter um só ambiente descartável com procedimento seguro de state; CloudFormation também seria possível, mas trocar a ferramenta não agrega. **4.** Recriação documentada, plan sem mudança após convergir, ambientes sem colisão, restore verificável e mínimo privilégio. **5–6.** Revisão/PoC 1–2 dias; robusto 4–8 para state/IAM/isolamento; backup/restore é esforço separado. Já é coerente, mas criar módulos genéricos para um stack seria excesso. **7–8.** Java: revisão estática agora; futuramente validate/test, plans revisados e laboratório descartável autorizado. **9–10.** Alto valor em IaC e segurança; **aperfeiçoar P0/P1, não duplicar no .NET agora**. [J10; T5]

O uso de SSM SecureString não remove automaticamente o segredo do state. Backend remoto criptografado com controle de acesso e locking é uma opção; state local protegido pode bastar em demonstração individual. Recursos ephemeral/write-only dependem de versões/providers suportados e não devem ser prometidos sem validação.

#### RabbitMQ

**1–3.** Poderia resolver roteamento AMQP e filas fora da AWS, mas os fluxos já usam SQS/DLQ. Broker em processo é a alternativa mais simples para execução sem AWS; SNS cobre fanout na AWS. **4.** Ganho só seria comprovado por um requisito de roteamento ou portabilidade e teste de recuperação sem perda após confirmação. **5–6.** PoC 2–4 dias; robusto 8–15, com publisher confirms, ack, retry/dead lettering e operação. Manter SQS e Rabbit no mesmo caminho seria duplicação. **7–8.** .NET em branch alternativa, se houver objetivo de aprender AMQP; trocar adapter preservando contrato, testar publish confirmado, crash de consumer e duplicatas. **9–10.** Conteúdo útil, mas menor retorno que corrigir NF1; **adiar P3**. [N3–N4; T6]

#### Elasticsearch/OpenSearch

**1–3.** Não foi encontrada busca por relevância, facetas ou tolerância a erro; catálogo apenas lista produtos. Logs já possuem LGTM no .NET. Alternativas: PostgreSQL full-text/trigram quando houver busca e Loki para logs. **4.** Só justificar por métricas de relevância, latência sob volume e freshness do índice. **5–6.** PoC 3–5 dias; robusto 10–20+, com sincronização, reindexação, mapping, retenção e segurança. Duplicação de dados e memória são custos reais. **7–8.** Nenhum agora; um futuro catálogo com busca poderia comparar contra PostgreSQL no Java. **9–10.** Ganho pequeno sem funcionalidade; **adiar P3**. [J6, N7, N9; T7]

#### OpenTelemetry

**1–3.** Java não comprova trace contínuo HTTP → outbox → SQS → consumer → provider. .NET já implementa Activity e propagação. Correlation ID facilita logs, mas não substitui causalidade e tempos entre spans. **4.** Medir proporção de operações correlacionadas sob sampling controlado, cobertura dos hops e tempo para localizar uma falha injetada. **5–6.** PoC 1–2 dias; robusto 4–7 com contexto, sampling, privacidade e integração de testes. Custo razoável, pois stack já existe. **7–8.** Priorizar Java; testar cliente sem trace header e com header válido, exportar spans e verificar parentesco/link através da fila. No .NET, validar collector além do teste existente. **9–10.** Alto valor para diagnóstico distribuído; **completar P1**. [J3, J8, N7; T8]

#### Prometheus e Grafana

**1–3.** Java possui dependências e LGTM opcional, mas faltam sinais próprios e prova da ingestão. .NET já tem dashboard, métricas e cinco regras de alerta. Logs/manuais são alternativas simples, insuficientes para tendência e alerta contínuo. **4.** Medir p95/p99, lag/idade da mensagem, backlog, DLQ, convergência, conflitos de estoque e falhas/compensações. **5–6.** PoC 1–2 dias; robusto 4–6 para instrumentação, buckets, dashboards, alertas e runbooks; custo de cardinalidade/retenção precisa ser controlado. **7–8.** Java primeiro; provocar provider lento, worker parado e poison message; observar alerta disparar/resolver. **9–10.** Alto valor em operação e SLO; **completar P1, preservar .NET**. [J7–J10, N7; T9]

Percentis exigem histogramas/exportação adequados. Não calcular média de p95 de réplicas. Métricas de eventos só devem ser apresentadas como commits quando refletirem commits; rollback pode distorcer contadores.

#### Testcontainers

**1–3.** .NET já usa PostgreSQL/LocalStack descartáveis. Java usa URLs/ports configuráveis e truncates sobre o banco indicado; Compose com projeto/volume exclusivo é alternativa menor, mas depende de disciplina externa. **4.** Dois runs concorrentes sem interferência, nenhuma escrita no banco de desenvolvimento, migrations do zero e duração/flakiness registradas. **5–6.** PoC 1–2 dias; robusto 3–5 para lifecycle, readiness e isolamento. Docker permanece dependência; a biblioteca não garante isolamento se fixtures forem mal desenhadas. **7–8.** Java: PostgreSQL e LocalStack exclusivos, ports dinâmicos e dados por suíte; testar execução concorrente e falha de inicialização. **9–10.** Alto valor, associado a um risco concreto; **implementar P1**, sem reescrever .NET. [J9, N11; T10]

#### k6

**1–3.** Os testes funcionais não estabelecem capacidade, saturação ou p99. Loops simples HTTP são alternativa, mas não controlam bem chegada e distribuição de latência. **4.** Baseline de throughput sustentado, p95/p99, erros, dropped iterations, pool DB, backlog e tempo até finalização. **5–6.** PoC 1–2 dias; campanha robusta 4–7 com datasets, cleanup seguro, métricas e comparação. Pouca complexidade no runtime; risco é produzir números irreproduzíveis. **7–8.** Ambos, Java primeiro pelas queries/scheduling; separar leitura, criação HTTP e processamento assíncrono. **9–10.** Alto valor por demonstrar diagnóstico com números reais; **implementar P1**. [J6–J7, N2, N9; T11]

Não apresentar desempenho do simulador como capacidade de um PSP real. Não incluir login a cada pedido salvo em cenário específico; a política de rate limit e conflitos esperados deve ser contabilizada separadamente de erro inesperado.

#### gRPC

**1–3.** Não há chamada interna que exija streaming/latência menor; provider externo usa REST e não mudará porque o cliente quer gRPC. OpenAPI/DTOs e testes de contrato atendem a comunicação existente. **4.** Um experimento poderia comparar tamanho de payload, CPU e latência Java/.NET para o mesmo contrato; nenhuma melhoria é presumida. **5–6.** PoC 2–3 dias; robusto 6–10 com compatibilidade protobuf, deadlines, segurança e distribuição. Outro protocolo sem necessidade aumenta manutenção. **7–8.** Laboratório independente entre Java e .NET, sem extrair novo microsserviço do domínio. **9–10.** Valor em interoperabilidade se souber discutir limites; **adiar P3, ou P2 se esse aprendizado for objetivo explícito**. [J5, N4; T12]

## 7. Decisões específicas e práticas adicionais

### Kafka vs. SQS

Manter SQS no núcleo. Nenhum fluxo inspecionado precisa de log retido para múltiplos consumidores independentes. A existência de eventos de domínio não implica event sourcing nem necessidade de Kafka. Consumers atuais competem na mesma fila; adicionar um consumidor de analytics ali não gera fanout e pode retirar mensagens do fluxo de fulfillment. Para fanout, dar uma cópia por consumidor via SNS/SQS ou outro mecanismo explicitamente desenhado. Kafka só vence quando offsets independentes e replay forem requisitos reais. Ordenação de Kafka é por partição, não global. [T1]

O laboratório Kafka recomendado é uma projeção descartável de timeline/estatística alimentada por eventos versionados, com replay sem efeito remoto. Precisa diferenciar reconstrução de estado histórico de consultar o estado atual por `orderId`.

### PostgreSQL antes de bancos adicionais

Investir em `EXPLAIN (ANALYZE, BUFFERS)` em base descartável, número de round trips, índices alinhados à paginação e filas de reconciliação, constraints e retenção. JSONB e full-text são alternativas internas antes de Mongo/OpenSearch. Não criar todo índice “parecido com query”: medir cardinalidade, custo de escrita e tamanho. [T3, T7]

### Práticas adicionais com melhor justificativa

| Prática | Evidência/necessidade | Decisão |
|---|---|---|
| Testes de crash e fault injection | NF1/NF2 e leases Java | P0/P1; usar barreiras/transportes controlados e processos isolados; Toxiproxy é opcional se falhas TCP exigirem |
| Contratos e versionamento de eventos/API | Java faz parsing de `orderId` por substring; .NET tem testes wire | P1 Java: envelope tipado, versão, validação e casos malformados; contract testing sem plataforma nova inicialmente |
| Cache local de token com single-flight | Java obtém token repetidamente; .NET já resolveu | P1 Java; demonstrar uma renovação por janela, invalidar em 401 e não logar credenciais |
| Política de retenção e replay | Inbox/outbox/dedup crescem; idempotência associa pedido | P1/P2: definir horizonte antes de apagar; preservar proteção contra mensagens antigas e evidência de compensação |
| Backup/restore e recovery drill | PostgreSQL AWS no root volume descartável | P1 se demo precisa preservar dados; declarar perda intencional se descartável; sem RDS obrigatório |
| Segurança do proxy e quotas | JF5; pending forwarded headers .NET | P1 antes de ampliar exposição; confiar apenas em proxies conhecidos |
| SLO e runbooks | Stack de métricas já existe | P1; definir sinais e ensaiar incidentes; não alegar SLA com poucos pedidos |
| Evolução segura de schema | Migrations explícitas já existem | P1 quando houver rollout com versões coexistindo; ensaio de compatibilidade, não migration automática em cada réplica |
| OIDC externo | Identidade própria tem custo, mas demonstra aprendizado | P2 se objetivo for integração com provedor de identidade; não adicionar Keycloak só para coleção de ferramentas |
| CDC/Debezium | Polling outbox existente, sem volume comprovado | P3 agora; considerar apenas se polling medido for limite ou captura do log for requisito |
| CQRS/event sourcing/frameworks de saga | Já há read handlers, eventos e compensações | P3 para novos frameworks; manter read models e compensação explícitos conforme necessidade |

Essas práticas valem profissionalmente pela explicação de invariantes, falhas e evidências; não é necessário apresentá-las como uma lista de ferramentas.

## 8. Comparação e divisão do investimento

**Concentrar infraestrutura e evolução operacional no Java; corrigir os riscos próprios do .NET; compartilhar a metodologia de validação.**

No Java: Testcontainers, trace contínuo, métricas/alertas, profiling SQL, cache local de token e refinamento Terraform. No .NET: transações dos consumidores e claims de webhook, depois teste de carga e consistência das métricas. A observabilidade .NET pode fornecer casos de aceitação para Java sem exigir as mesmas classes ou bibliotecas.

Manter diferenças explicáveis: Java com JPA/Flyway/Resilience4j e demonstração EC2; .NET com EF/xmin/Polly, composição de hosts e Testcontainers. Um deploy ECS .NET só entra depois se houver objetivo de demonstrar rollout de tasks, migration e operação que a EC2 não cobre. Não manter dois ambientes pagos por aparência de paridade.

Branches de laboratório para Kafka/Redis/Kubernetes devem ter contrato de experimento, dataset, hipótese, comando de reprodução e critério de abandono. Nesta etapa nenhuma branch experimental foi criada. No máximo um experimento adicional por vez para limitar manutenção.

## 9. GATE 4 — roadmap por custo-benefício

PoC significa prova da hipótese; não entrega robusta. As estimativas robustas incluem testes, revisão, documentação futura e evidência. Itens podem compartilhar trabalho; não somar todos os limites como um calendário exato.

| Ordem | Prioridade / proposta | Projeto | PoC → robusto | Complexidade | Dependências |
|---|---|---|---|---|---|
| R1 | P0 — corrigir limites transacionais e durabilidade no consumer | .NET | 1–2 → 5–8 dias | Alta | Docker isolado; testes modo SQS |
| R2 | P0 — contrato de idempotência e ownership | Java; revisão .NET | 1–2 → 3–6 dias | Média | Relógio controlável; banco isolado |
| R3 | P0 — claim/finalização concorrente de webhook | .NET | 1 → 3–5 dias | Média | Dois consumers; teste de falha |
| R4 | P0 — verificar proteção e recuperação do state | Java | 0,5–1 → 1–3 dias | Baixa–média | Acesso futuro autorizado à configuração privada |
| R5 | P1 — Testcontainers e execução isolada | Java | 1–2 → 3–5 dias | Média | Docker; R2/R3 usam o padrão de isolamento |
| R6 | P1 — OTel, métricas, Grafana e alertas verificáveis | Java | 2 → 5–8 dias | Média | R5; pipeline LGTM local |
| R7 | P1 — k6 + profiling SQL + otimizações comprovadas | Ambos, Java primeiro | 1–2 → 4–7 dias | Média | R1–R3; R6 ou sinais .NET existentes |
| R8 | P1 — orçamento de worker, leases e token local | Java; leases .NET | 1–2 → 4–7 dias | Média | R5/R7; provedor lento controlado |
| R9 | P1 — IaC existente: IAM, ambientes e recovery | Java | 1–2 → 4–8 dias | Média | R4; nenhum apply nesta fase |
| R10 | P2 — Redis para limite global de réplicas | Java laboratório | 1–3 → 5–10 dias | Média | Proxy confiável; política de falha; R7 |
| R11 | P2 — Kafka para projeção com replay | Java laboratório | 3–5 → 10–20+ dias | Alta | Evento versionado; R1–R8 concluídos |
| R12 | P2 — validar Kubernetes já existente | Java local | 1–3 → 5–8 dias | Média | R5/R8; migration/seed sequenciados |

### R1 — transações .NET

**Problema/solução:** NF1; separar commit de intenção, chamada externa e commit final. Manter chaves e reconciliação. **Riscos:** perder atomicidade local ao remover a transação externa sem redesenhar conclusão; regressão de compensação. **Aceitação:** estado e chave local visíveis por outra conexão antes do HTTP; nenhuma transação aberta abrangendo espera de provider; um pagamento/entrega remotos por operação após crash e retry. **Testes:** crash após intenção, após resposta remota e antes de dedup; transient/permanent rejection no modo SQS e sem broker; concorrência de reconciliação. **Evidência:** timeline de commits, registros do simulador, queries e resultado convergente. **Valor:** explicar consistência entre banco e serviço externo sem prometer exactly-once. [N3–N4]

### R2 — idempotência

**Problema/solução:** JF1/NF3; especificar prazo de replay, recuperação e proibição/reuso de chave; owner token para takeover. **Riscos:** incompatibilidade para clientes e exclusão prematura de prova de operação. **Aceitação:** payload diferente nunca recebe silenciosamente sucesso de outro pedido; crash antes do pedido não bloqueia 24 horas; dono antigo não finaliza/libera reserva do novo; pedido/estoque continuam únicos. **Testes:** Clock/TimeProvider avançado, dois clientes, takeover, commit sem resposta, TTL e mismatch. **Evidência:** tabela de estados com respostas HTTP e quantidades persistidas. **Valor:** uma história concreta de recuperação segura. [J2, N2, N6]

### R3 — webhooks .NET

**Problema/solução:** NF2; claim atômico com lease/owner, retries definidos e finalização segura. Usar PostgreSQL antes de lock Redis. **Riscos:** lease longa atrasar retry; lease curta admitir dois efeitos; terminalizar cedo. **Aceitação:** apenas um dono ativo processa; takeover recupera worker morto; dono vencido não altera conclusão; efeito local/outbox não duplicam. **Testes:** dois consumers do mesmo evento, crash em cinco pontos e última tentativa, processamento lento além da lease. **Evidência:** histórico de claims, uma transição/compensação, ack apenas depois de condição correta. **Valor:** distinguir inbox de deduplicação de exclusão mútua. [N5]

### R4 — state Terraform

**Problema/solução:** segredos entram no state; confirmar onde fica, quem lê e como recuperar, sem divulgar valores. **Riscos:** perda de state e exposição de snapshots. **Aceitação:** localização/ACL/criptografia/locking documentados conforme modelo escolhido; state e plan ausentes de versionamento/artifacts; recuperação ensaiada em cópia sanitizada ou ambiente descartável autorizado. **Testes:** inspeção de configuração e recuperação, sem apply atual. **Evidência:** checklist com metadados e logs sem valores secretos. **Valor:** segurança real de IaC. [J10, T5]

### R5 — isolamento Java

**Problema/solução:** IT truncam base externa; fixtures descartáveis, ports dinâmicos e lifecycle automático. **Riscos:** startup/flakiness e tempo de CI. **Aceitação:** nenhum teste toca o banco de desenvolvimento; duas execuções não disputam schema; migrations aplicadas em base vazia; teardown após falha. **Testes:** run concorrente, falha de startup e suíte completa. **Evidência:** logs sanitizados, ports únicos e relatórios atuais. **Valor:** confiabilidade do teste com banco real. [J9]

### R6 — observabilidade Java

**Problema/solução:** JF4; inject/extract do contexto real, spans consumidores, instrumentação provider e exportação métrica confirmada. **Riscos:** vazamento em atributos, cardinalidade, sampling ocultando falha. **Aceitação:** trace iniciado sem header alcança consumer/provider; métricas de lag/backlog/DLQ/negócio chegam ao collector; dashboard e alerta funcionam sem token admin humano frágil. **Testes:** exporter controlado, smoke do collector e três falhas injetadas. **Evidência:** spans exportados com relação correta, gráfico e alerta firing/resolved. **Valor:** diagnosticar uma cadeia distribuída. [J3, J7–J10]

### R7 — capacidade e SQL

**Problema/solução:** inexistência de baseline; cenário k6 reproduzível e profiling antes/depois. **Riscos:** medir gerador saturado, confundir HTTP rápido com fulfillment concluído, cache de dados pequeno. **Aceitação:** registrar versão, hardware, dataset, duração, arrival rate, percentis, erros e dropped iterations; medir queries/listagem; otimização não viola estoque/idempotência. **Testes:** catálogo, keyset profundo, compradores concorrentes, provider lento e fluxo assíncrono. **Evidência:** resultados brutos e plans, três repetições comparáveis, regressão funcional. **Valor:** decisões com números e domínio de SQL. [J6–J7, N2, N9]

### R8 — worker e token

**Problema/solução:** JF3 e token repetido; orçamento total, concurrency limitada, lease/visibilidade coerentes e cache local single-flight. **Riscos:** sobrecarregar pool/provider, cachear token vencido, starvation. **Aceitação:** nenhuma renovação repetida desnecessária; 401 invalida token; duas instâncias convergem após pausa maior que lease; backlog drena no perfil de carga definido, sem reserva negativa. **Testes:** provedor lento, process pause, token expira/recusa, limites de conexão. **Evidência:** chamadas de token por janela, ocupação de pool e tempo de drenagem. **Valor:** concorrência e backpressure fundamentados. [J3, J5, J7, N3, N6]

### R9 — IaC e recovery

**Problema/solução:** JF7; usar ARNs exatos, nomes por ambiente, artefato identificável e estratégia explícita de perda/restore. **Riscos:** rename de filas recria recursos; IAM restrito pode bloquear SDK; backups geram custo. **Aceitação:** ambientes não colidem, política não permite filas alheias, no-op plan após convergência; restore preserva invariantes se persistência for requisito. **Testes:** validate/checagens estáticas agora; plano/recriação/restore só em fase autorizada. **Evidência:** plan sanitizado, política e relatório RPO/RTO medidos. **Valor:** operação segura e reproduzível, sem alegar HA. [J10–J12]

### R10 — Redis

**Problema/solução:** limites por instância; limiter global atômico em laboratório com duas APIs. **Riscos:** Redis virar ponto de indisponibilidade, TTL incorreto e confiança no IP falso. **Aceitação:** soma das aceitações respeita contrato do algoritmo; recuperação após TTL; política de indisponibilidade explícita e testada. **Testes:** concorrência, duas réplicas, queda/restart Redis, carga de IPs/chaves. **Evidência:** comparação com limiter local e métricas. **Valor:** coordenação distribuída com escopo restrito. Se uma única API continuar suficiente, arquivar o experimento. [J8, N8]

### R11 — Kafka

**Problema/solução:** aprendizado de replay de projeção, sem requisito operacional atual. **Riscos:** confundir comando com fato histórico, dual-write, replay disparar efeito remoto. **Aceitação:** dois grupos independentes; apagar apenas projeção e reconstruí-la; ordenação por pedido; nenhum handler financeiro/logístico chamado pelo replay; schema evolution definida. **Testes:** crash/rebalance, duplicata, eventos antigos e reconstrução. **Evidência:** lag, checksum/conteúdo da projeção e registros que provam ausência de efeito externo. **Valor:** explicar quando Kafka supera fila de trabalho. Não promover ao main sem requisito novo. [J3, T1]

### R12 — Kubernetes

**Problema/solução:** validar manifests já existentes como laboratório; não novo cluster de produção. **Riscos:** Jobs não concluídos antes da API, probes falsas, secrets locais reutilizados. **Aceitação:** bootstrap repetível, rollout sem quebrar contrato no cenário escolhido, recuperação de pod, duas APIs com comportamento consistente. **Testes:** pod kill, imagem nova, migration, indisponibilidade DB/SQS e verificação de probes. **Evidência:** logs de rollout/recovery e limitações descritas como experiência local. **Valor:** fundamentos de orquestração; sem valor suficiente para EKS agora. [J11]

## 10. Arquitetura futura justificada

Preservar a topologia principal:

```text
Cliente → API autenticada → PostgreSQL
                              ├─ pedido/estoque/idempotência
                              ├─ intenção/estado de integração
                              └─ outbox → publisher → SQS/DLQ → worker
                                                                ↓
                                                  provider HTTP simulado
                                                                ↓
                        API webhook → inbox → processamento/reconciliação

API/worker → contexto OTel + métricas/logs → collector → Grafana/Tempo/Prometheus
```

Mudança arquitetural necessária no .NET: intenção persistida → HTTP fora de transação → resultado/dedup/outbox em commit curto. No Java, preservar esse princípio e uniformizar contrato de idempotência, contexto e budgets. Testes falam com infraestrutura descartável.

Na AWS Java, EC2/Compose continua adequado para a demonstração. State seguro, IAM e recuperação são evolução mais proporcional que cluster. Redis/Kafka ficam fora desse desenho até que seus critérios sejam satisfeitos. Não extrair payment/delivery como microsserviços; não introduzir transações distribuídas entre stores.

## 11. Plano de validação e métricas

| Hipótese/garantia | Experimento futuro seguro | Sinais/aceitação |
|---|---|---|
| Estoque não é vendido duas vezes | 20 compradores para última unidade em banco descartável | Uma reserva bem-sucedida; estoque zero; sem pedido extra |
| Commit sem resposta é recuperável | Matar processo antes/depois do commit; retry com mesma chave | Mesmo pedido/efeito; mismatch recusado; nenhum bloqueio prolongado indevido |
| Intenção sobrevive à chamada externa | Observar por segunda conexão durante POST pendente | Registro/chave já commitados; recuperação usa mesma identidade |
| Lease protege coordenação | Dois workers, pausa além da lease e takeover | Finalização de dono antigo rejeitada; efeito converge sem dupla compensação |
| Webhook pode ser duplicado/desordenado | Mesmo evento concorrente, eventos antigos e resposta provider divergente | Sem regressão terminal; tentativas/ack coerentes; eventos registrados |
| Fila com falha converge | Provider 503, SQS indisponível, poison message, reinício | Retry/backoff observado; DLQ acessível; reprocessamento seguro; backlog drena |
| Trace é contínuo | Cliente sem header + execução através de fila | Contexto exportado com parent/link correto; nenhuma PII/segredo |
| Otimização SQL ajuda | Mesmo dataset e perfil antes/depois | Queries por página, buffers, DB CPU, p95/p99; sem regressão de resultado |
| Rate limit global funciona | Duas réplicas + burst + Redis indisponível | Orçamento do algoritmo e política de falha respeitados |
| Infraestrutura recupera | Laboratório futuro autorizado e restore | RPO/RTO medidos, estado íntegro; sem usar dados reais |

Campanha de carga: preparar dataset sintético pequeno e maior, aquecer, executar leitura/criação/convergência separadamente e repetir três vezes. Usar arrival rate controlado para revelar saturação; registrar duração, VUs, dropped iterations e utilização do gerador. Medir também cold start e cache frio quando aplicável.

Não estabelecer “p95 < 200 ms” ou “10 mil pedidos/s” como meta arbitrária. Primeiro medir baseline; depois escolher uma carga de demonstração e SLO que o domínio e o ambiente suportem. Critérios de correção, como estoque não negativo e ausência de dupla compensação, não dependem dessa negociação.

Para a cadeia assíncrona, medir tempo do commit de pedido até estado final, idade do trabalho mais antigo, outbox lag, DLQ, retries, conflitos e compensações pendentes. HTTP 201 rápido não prova fulfillment rápido. Percentis só têm sentido com amostra e intervalo adequados; apresentar dados brutos além do resumo.

## 12. O que não incorporar agora

- **MongoDB:** não há domínio que justifique um segundo banco.
- **RabbitMQ adicional:** duplica a função de SQS sem requisito de roteamento/portabilidade.
- **OpenSearch/Elasticsearch:** não há busca avançada; logs já têm alternativa no stack.
- **Kafka no fluxo de cobrança/entrega:** não há requisito de replay/fanout que supere custo, e replay é perigoso para efeitos externos.
- **EKS/Kubernetes de produção:** nenhuma necessidade de operação de cluster foi demonstrada.
- **Redis para estoque/idempotência transacional:** separa da transação que hoje sustenta as garantias.
- **gRPC no provider simulado principal:** muda um contrato externo por um protocolo sem problema demonstrado.
- **Microsserviços extras, service mesh, event sourcing e CDC:** não há motivação concreta; aumentariam superfície de falha e manutenção.

Não recomendar essas incorporações agora não diminui o projeto. A capacidade de rejeitar uma tecnologia com base em invariantes e custo é parte do valor para entrevistas.

## 13. Três melhorias de maior retorno e parecer final

1. **Confiabilidade demonstrável:** corrigir R1–R3 e tornar testes de falha isolados/reproduzíveis com R5. Entregar uma história de crash, recuperação e ausência de efeito duplicado vale mais que três brokers no README.
2. **Observabilidade Java operante:** completar R6, ensaiar um incidente e mostrar como trace, lag e alertas explicam o estado do pedido. Usar o .NET como referência de critérios, preservando implementação idiomática.
3. **Desempenho com evidência:** executar R7 e aplicar apenas otimizações justificadas, como projeção da listagem Java, budget do worker e cache local de token. Mostrar baseline, diagnóstico e resultado medido.

Um engenheiro backend experiente manteria PostgreSQL e SQS, resolveria janelas de falha, garantiria testes seguros e operação observável, e só adicionaria tecnologia depois de provar a hipótese. Preservaria a EC2 como demonstração honesta, com limites de recuperação e custo explícitos. Usaria Kafka/Redis/Kubernetes em laboratórios pequenos quando o objetivo fosse aprender uma propriedade que o núcleo não precisa hoje.

**GATE 5: relatório entregue para revisão. Aguardar aprovação antes de implementar.** O escopo inicial recomendado para autorização futura é R1–R6; R7 vem na sequência. R4 depende de acesso à configuração privada e não autoriza apply. Nenhum código, infraestrutura ou documentação dos repositórios foi modificado; este relatório é um entregável separado.

## 14. Catálogo de evidências dos repositórios

Os links estão fixados aos commits auditados. Referências J/N no texto apontam para os grupos abaixo; nomes de classes/métodos identificam os trechos relevantes.

### Java

- **J1 — módulos e dependências:** [pom.xml](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/pom.xml), [runtime/pom.xml](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/pom.xml).
- **J2 — criação/idempotência:** [CommerceController](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/api/src/main/java/dev/fulfillmenthub/api/CommerceController.java), [OrderPlacementService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/orders/OrderPlacementService.java), [IdempotencyService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/idempotency/IdempotencyService.java).
- **J3 — outbox/SQS:** [OutboxProcessor](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/outbox/OutboxProcessor.java), [OutboxRow](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/outbox/OutboxRow.java), [SqsBroker](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/messaging/SqsBroker.java).
- **J4 — pagamento/reconciliação:** [PaymentWorkflowService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/payment/PaymentWorkflowService.java), [PaymentGatewayClient](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/payment/PaymentGatewayClient.java), [ReconciliationService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/reconciliation/ReconciliationService.java).
- **J5 — entrega/webhooks:** [DeliveryWorkflowService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/delivery/DeliveryWorkflowService.java), [WebhookProcessingService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/webhook/WebhookProcessingService.java), [WebhookQueue](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/messaging/WebhookQueue.java).
- **J6 — queries/schema:** [OrderService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/orders/OrderService.java), [OrderRow](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/orders/OrderRow.java), [migrations V1–V6](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/tree/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/resources/db/migration).
- **J7 — worker/resiliência:** [OutboxPoller](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/worker/src/main/java/dev/fulfillmenthub/worker/OutboxPoller.java), [ProviderResilience](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/providers/ProviderResilience.java), [worker application.yml](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/worker/src/main/resources/application.yml).
- **J8 — segurança/contexto:** [RequestFilter](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/api/src/main/java/dev/fulfillmenthub/api/RequestFilter.java), [SecurityConfiguration](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/api/src/main/java/dev/fulfillmenthub/api/SecurityConfiguration.java), [SessionService](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/main/java/dev/fulfillmenthub/runtime/identity/SessionService.java), [API application.yml](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/api/src/main/resources/application.yml).
- **J9 — testes/CI:** [AdversarialAuditIT](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/runtime/src/test/java/dev/fulfillmenthub/runtime/audit/AdversarialAuditIT.java), [integration-test.ps1](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/scripts/integration-test.ps1), [ci.yml](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/.github/workflows/ci.yml).
- **J10 — infraestrutura/configuração:** [main.tf](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/infra/aws-ec2/main.tf), [versions.tf](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/infra/aws-ec2/versions.tf), [Compose AWS](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/infra/aws-ec2/compose.aws.yaml), [Compose local](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/compose.yaml).
- **J11 — Kubernetes:** [base.yaml](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/k8s/base.yaml), [dependências/seed](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/tree/f82baa173a010507d5d6bcd332013d9d0dfb0cad/k8s).
- **J12 — deploy/containers:** [AWS_EC2_DEPLOYMENT.md](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/docs/AWS_EC2_DEPLOYMENT.md), [Dockerfile](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/Dockerfile), [bootstrap](https://github.com/Gabriel-PereiraL/fulfillment-hub-java/blob/f82baa173a010507d5d6bcd332013d9d0dfb0cad/infra/aws-ec2/user-data.sh.tftpl).

### .NET

- **N1 — estrutura/stack:** [src](https://github.com/Gabriel-PereiraL/fulfillment-hub/tree/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src), [Directory.Packages.props](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/Directory.Packages.props).
- **N2 — pedido:** [PlaceOrderHandler](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Application/Orders/PlaceOrderHandler.cs), [OrderConfiguration](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Persistence/Configurations/Orders/OrderConfiguration.cs).
- **N3 — outbox e transação:** [OutboxDispatcher](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Outbox/OutboxDispatcher.cs), [OutboxProcessor](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Outbox/OutboxProcessor.cs), [handlers](https://github.com/Gabriel-PereiraL/fulfillment-hub/tree/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Application/Outbox/Handlers).
- **N4 — providers/consumers:** [CreatePaymentForOrderHandler](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Application/Payments/CreatePaymentForOrderHandler.cs), [RequestDeliveryHandler](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Application/Deliveries/RequestDeliveryHandler.cs), [SqsConsumer](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Worker/Messaging/SqsConsumer.cs).
- **N5 — webhooks:** [WebhookEventProcessor](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Webhooks/WebhookEventProcessor.cs), [WebhookInbox](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Webhooks/WebhookInbox.cs).
- **N6 — idempotência/cache/resiliência:** [IdempotencyStore](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Idempotency/IdempotencyStore.cs), [IdempotencyFilter](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Api/Idempotency/IdempotencyFilter.cs), [DeliveryAccessTokenProvider](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Providers/Deliveries/DeliveryAccessTokenProvider.cs), [ProviderResilienceExtensions](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Providers/ProviderResilienceExtensions.cs).
- **N7 — observabilidade:** [TelemetryHostBuilderExtensions](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Telemetry/TelemetryHostBuilderExtensions.cs), [provisioning Grafana](https://github.com/Gabriel-PereiraL/fulfillment-hub/tree/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/observability/grafana), [TraceContinuityTests](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/tests/FulfillmentHub.IntegrationTests/Outbox/TraceContinuityTests.cs), [exercício histórico](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/docs/incidents/2026-09-21-slow-provider-drill.md).
- **N8 — API/segurança:** [Program](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Api/Program.cs), [ApiSecurityServiceCollectionExtensions](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Api/Identity/ApiSecurityServiceCollectionExtensions.cs), [JwtBearerOptionsSetup](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Api/Identity/JwtBearerOptionsSetup.cs).
- **N9 — leitura/persistência:** [OrderQueries](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Application/Orders/OrderQueries.cs), [ProductQueries](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Application/Catalog/ProductQueries.cs), [configurações EF](https://github.com/Gabriel-PereiraL/fulfillment-hub/tree/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/src/FulfillmentHub.Infrastructure/Persistence/Configurations).
- **N10 — infraestrutura/CI:** [roadmap](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/docs/ROADMAP.md), [ADR ECS/Terraform](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/docs/adr/ADR-011-aws-ecs-fargate-terraform.md), [ci.yml](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/.github/workflows/ci.yml).
- **N11 — testes:** [ApiFixture](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/tests/FulfillmentHub.IntegrationTests/Fixtures/ApiFixture.cs), [SqsMessagingTests](https://github.com/Gabriel-PereiraL/fulfillment-hub/blob/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/tests/FulfillmentHub.IntegrationTests/Messaging/SqsMessagingTests.cs), [suítes](https://github.com/Gabriel-PereiraL/fulfillment-hub/tree/2b86d0ddecd3c6d2fcb2f77bb0e1ddc693ad3f86/tests).

## 15. Documentação técnica consultada

- **T1:** [Kafka — introdução, grupos e ordenação](https://kafka.apache.org/10/documentation/), [AWS — fanout SNS/SQS](https://docs.aws.amazon.com/us_en/sns/latest/dg/sns-sqs-as-subscriber.html).
- **T2:** [Redis — INCR e atomicidade com expiração](https://redis.io/docs/latest/commands/incr/), [cache-aside](https://redis.io/docs/latest/develop/use-cases/cache-aside/).
- **T3:** [MongoDB — modelagem por embedding](https://www.mongodb.com/docs/manual/data-modeling/embedding/), [PostgreSQL — JSONB e índices](https://www.postgresql.org/docs/16/datatype-json.html).
- **T4:** [Minikube — ambiente local e distinção de produção](https://minikube.sigs.k8s.io/docs/start/).
- **T5:** [Terraform — proteção de dados sensíveis e state](https://docs.hashicorp.com/terraform/language/manage-sensitive-data).
- **T6:** [RabbitMQ — confiabilidade e confirmações](https://www.rabbitmq.com/docs/reliability).
- **T7:** [OpenSearch — Search API](https://docs.opensearch.org/latest/api-reference/search/).
- **T8:** [OpenTelemetry — propagação de contexto](https://opentelemetry.io/pl/docs/concepts/context-propagation/).
- **T9:** [Prometheus — histogramas e percentis](https://prometheus.io/docs/practices/histograms/).
- **T10:** [Testcontainers Java — JUnit](https://java.testcontainers.org/quickstart/junit_5_quickstart/).
- **T11:** [k6 — thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/), [métricas e dropped iterations](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/).
- **T12:** [gRPC — contratos e ciclo de chamada](https://grpc.io/docs/what-is-grpc/core-concepts/), [deadlines](https://grpc.io/docs/guides/deadlines/).
