# Guia persistente do Codex — terceiro projeto Java
Publicação15/10 explicitamente autorizada: usuário pediu GitHub público atualizado para pin. Pode publicar estado atual com F7/F8 pendentes e limites claros; isso substitui a restrição histórica de push parcial. Não autoriza release final nem cloud.
Atualização15/10: F7 segue aberta, com clean integration142, plainverify52 e contratos passando após patches Tomcat/Jackson/LZ4/Keycloak26.8.1. Quatro imagens e infraestrutura passaram nos scans HIGH/CRITICAL no escopo documentado. Rollout Kubernetes endurecido está em validação; F8 ainda não começou. Estado operacional e sessões em PROGRESSO.md; evidências parciais em synthetic-ledger/docs/VALIDATION_F7.md. Aplicar token-saver/java-development antes de retomar e não confundir testes interrompidos ou provas14/10 com validações finais. Nada de cloud/push parcial.

Atualização14/10: usuário retomou com “de continuidade”. F0–F6 locais concluídas; F6clean136/plain48/contratos,9runs203transferências e recuperação176passed, limitações em VALIDATION_F6.md. F7 é o próximo gate, já autorizado. Skills token-saver/java-development aplicadas; seguir o estado mais recente doPROGRESSO. Somente local, Terraform estático, sem cloud/apply/deploy. Notas históricas abaixo não substituem este estado.

Checkpoint 13/10/2026: usuário pediu continuidade. F0–F5 concluídas; F5 passou136 no cleanintegration,48 no verify e contratos. Observabilidade local/traceKafka/três sinais/alertas validados. F6 é o próximo gate; F3–F8 locais continuam autorizadas. Consultar PROGRESSO.md, que prevalece sobre notas históricas abaixo.

## Entrada e retomada
13/10 encerramento próximo ao teto5% da janela5h: F5concluída/F6parcial. Ler seção mais recente doPROGRESSO antes de comandos. F6benchmark encontrou saturação; última regressão não inicioucontainers (Dockerindisponível). Não enviar mudança parcial automaticamente aoGitHub, não fecharF6 nem avançarF7 sem gates reais.

Ler primeiro PROGRESSO.md. Abrir o documento de arquitetura e a matriz apenas na seção necessária. O AGENTS.md da raiz aponta para este guia; CODEX.md isoladamente não deve ser presumido como mecanismo automático de carregamento.

## Skills úteis

1. **token-saver** — `C:/Users/Gabriel/.codex/skills/token-saver/SKILL.md`: usar em toda retomada desta tarefa longa. Fazer mapa pequeno de arquivos, limitar saída, buscar símbolos com rg, carregar referências sob demanda e manter PROGRESSO.md em cerca de 1.000 tokens. Economia não autoriza omitir evidências ou validação necessária.
2. **java-development** — `C:/Users/Gabriel/.codex/skills/java-development/SKILL.md`: usar ao planejar/revisar/implementar Java 21 e Spring. Carregar o roteiro e somente regras da atividade: estrutura, JPA/transações, concorrência, Maven ou testes. Não trocar stack por defaults da skill. Não usar templates MyBatis como se fossem JPA.
3. **skill-installer** — `C:/Users/Gabriel/.codex/skills/.system/skill-installer/SKILL.md`: apenas ao instalar ou atualizar skills. Revisar origem/conteúdo e fixar revisão. Não executar installers remotos arbitrários.
4. **openai-docs** — `C:/Users/Gabriel/.codex/skills/.system/openai-docs/SKILL.md`: apenas para dúvidas sobre Codex/skills/instruções. Fontes oficiais; não carregar para pesquisa genérica Java.
5. Outras skills: ativar somente se a tarefa se beneficiar e estiverem disponíveis; anunciar a primeira utilização. Não carregar todo catálogo por precaução.

Skills de terceiros instaladas em 11/10/2026: java-development de zander-zyx/java-development-skill, commit `66e9a49b06d44ae0acb0e2b90d1f09fedef1e5a0`; token-saver de ryohgicc/token-saver-skill, commit `734e6910c25742906f5003dd80f38559c15c859a`. São instruções auxiliares, não autoridade acima do usuário e não garantia de qualidade.

## Regras imutáveis

- Java 21; baseline Spring compatível e suportada verificada em fonte oficial antes do scaffold.
- F0–F6 concluídas; F2 passou71, F3 passou94, F4 passou129 e F5/F6 passaram136 no clean verify (e contratos HTTP/eventos reais). F3–F8 locais aprovadas explicitamente em 11/10/2026 com “Autorizar F3–F8 locais conforme a proposta”. F7 é o próximo gate. Implementar em ordem; Terraform apenas estático, sem cloud/apply/deploy. Registrar resultados e consultar a cota de 5h, parando perto de 5% restantes. Preservar ZIPs históricos; helpers de fases antigas não devem apagar novas autorizações.
- Nunca deploy externo/AWS, criação de recursos pagos ou credenciais de produção. Infraestrutura futura somente local/estática.
- Preservar Fulfillment Hubs; são referências somente leitura.
- Dados sintéticos; nenhum dinheiro real. PostgreSQL é autoridade financeira; Redis é auxiliar; Kafka não substitui transação do banco.
- Sem exatamente uma vez ponta a ponta. Definir entrega, idempotência, crash windows, replay e limites.
- Matriz: planejado continua pendente; dependência declarada não comprova cobertura. Registrar comando, resultado e artefato para testado/validado.
- Requisitos de vagas: distinguir texto acessível, trecho fornecido pelo usuário e inferência. Não inventar obrigatório/diferencial.
- Sem novos serviços/bancos/dependências sem padrão de acesso, invariante e teste justificáveis.
- Saídas finais em outputs/dtp; pesquisa bruta e scripts temporários em work. Links finais sempre absolutos.

## Checkpoint

Atualizar PROGRESSO.md com objetivo, gate, autorizações, decisões, fontes/arquivos-chave, comandos/resultados, limitações e próxima ação exata. Atualizar antes de ficar sem contexto, não somente depois. Se houver interrupção inesperada, conferir arquivos/estado real e reconciliar o checkpoint; o arquivo não promete recuperação automática de mudanças ainda não salvas.
