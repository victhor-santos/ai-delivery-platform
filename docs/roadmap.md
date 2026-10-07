# Roadmap

O catálogo foi integrado pelo PR #6, a localização de coleta pelo PR #8, o ciclo mínimo de pedidos pelo PR #9, o domínio de entregas pelo PR #10, a persistência pelo PR #11, a API de entregas pelo PR #12, a integração entre pedidos e entregas pelo PR #13, a base Python pelo PR #14, o grafo com Dijkstra pelo PR #15 e o dataset pelo PR #16. O modelo foi integrado pelo PR #17 e acrescenta [comparação, seleção, artefato, predictor e avaliação offline](route-segment-model.md). A API de rotas foi integrada pelo PR #18 e oferece [consulta HTTP com prontidão e erros controlados](intelligent-routing-api.md). A integração com Delivery entrou pelo PR #19, com [cliente Java, persistência do plano e controle de concorrência](delivery-route-integration.md). O PR #20 acrescenta a [demonstração completa em containers](route-intelligence-compose.md), o PR #21 registra [observações simuladas por trecho e exportação histórica](delivery-segment-observations.md), o PR #22 acrescenta a [avaliação offline desses exports](segment-observation-evaluation.md), o PR #23 prepara [partições temporais por entrega com manifesto e carregamento validado](segment-observation-dataset.md), e o PR #24 integra [treinamento e avaliação offline de modelos observacionais](segment-observation-training.md). O PR #25 integra [itens de cardápio, preços BRL e disponibilidade](restaurant-menu.md). O PR #26 integra [itens, quantidades e preços preservados nos pedidos](order-items.md). O PR #27 integra [perfis e endereços de usuários](user-profiles.md), com PostgreSQL próprio. A branch atual, `feature/authentication`, acrescenta [cadastro com senha, login e identidade JWT](authentication.md); seu push e merge permanecem pendentes.

A base de entregas e ML já funciona na demonstração local. A próxima fase completa o fluxo comercial e a interface da V1, preservando a arquitetura e a identificação explícita de dados simulados.

## Workflow Git

Cada etapa terá sua própria branch e PR. Durante o desenvolvimento, os commits podem ser divididos em mudanças menores. O merge na `main` será feito por squash.

1. Conferir status, histórico e remotos. Resolver ou preservar alterações locais antes de trocar de branch.
2. Na `main`, executar `git pull --ff-only` e criar a nova feature branch.
3. Desenvolver somente na branch de trabalho, com commits descritivos e testes junto ao comportamento que verificam.
4. Revisar o diff, executar as validações relevantes e fazer push somente da branch de trabalho.
5. Abrir PR para `main`, com objetivo, mudanças, validação e limitações.
6. Usar Squash and Merge, atualizar a `main` local e remover a branch antiga após verificar a integração e preservar eventuais alterações pendentes.
7. Criar outra branch para a próxima entrega; não reutilizar uma branch já integrada.

A `main` não recebe desenvolvimento direto nem force push.

## Sequência de implementação

| Ordem e branch | Objetivo e mudanças | Testes e validação | Definição de pronto |
| --- | --- | --- | --- |
| 1. `feature/route-intelligence-design` | Registrar arquitetura, domínio mínimo, contrato, dados e este roadmap | Revisar coerência com o código atual, links e exemplos JSON | Documentação revisada; nenhum código Python ou mudança funcional |
| 2. `feature/restaurant-location` | Localização de coleta no catálogo: domínio, DTOs, operação explícita para informar/atualizar localização e nova migration | Coordenadas válidas/inválidas, persistência e preservação de restaurantes existentes | Restaurante pode fornecer origem; ausência de localização é explícita, sem inventar coordenadas |
| 3. `feature/order-domain` | Primeiro fluxo mínimo de pedido, com referência ao restaurante, destino, estados, JPA, Flyway e API pequena | Invariantes, PostgreSQL com Testcontainers e contrato HTTP | Pedido criado, confirmado e consultado; sem simulação de pagamentos ou catálogo de itens |
| 4. `feature/delivery-domain` | Entrega, snapshots de localização, atribuição, estados e timestamps em Java puro | Transições válidas/inválidas, cancelamento, coordenadas e relógio controlado | Domínio representa o fluxo mínimo sem depender de HTTP/JPA |
| 5a. `feature/delivery-persistence` | PostgreSQL próprio, adaptadores JPA, migrations e persistência mínima de entregadores | Testcontainers, reconstrução do domínio, unicidade por pedido, disputa de atribuição e versão | Ciclo persistido e garantias de concorrência testados, sem nova API |
| 5b. `feature/delivery-lifecycle` | Casos de uso, DTOs, API de transições e cadastro mínimo de entregador para atribuição manual | HTTP com PostgreSQL, erros controlados, conflitos e Gateway | Ciclo da entrega executável por API, com invariantes persistidas |
| 6. `feature/order-delivery-integration` | Criação de entrega a partir de pedido confirmado, snapshots e contrato HTTP idempotente | Restaurante sem localização/inativo, repetição, conflito de dados e indisponibilidade | Nenhuma entrega duplicada nem confirmação falsa de criação; nova tentativa recupera o fluxo |
| 7. `feature/route-intelligence-foundation` | FastAPI, configuração, dependências iniciais, pytest e `/health` | Inicialização e HTTP de saúde | Serviço Python executável, sem treinamento, grafo ou inferência |
| 8. `feature/road-graph` | JSON sintético versionado, validação do grafo e Dijkstra com tempos fixos de referência | Direção, origem igual ao destino, ausência de caminho, empates, somas e caminho mais longo em km que vence em tempo | Roteamento determinístico sem ML; limites de tamanho e desempenho do fixture documentados |
| 9. `feature/route-segment-dataset` | Gerador com seed, manifesto, schema de features e partições por tempo/grupo | Reprodutibilidade, valores plausíveis, colunas permitidas e isolamento dos conjuntos | Dataset regenerável e explicitamente sintético, sem leakage conhecido |
| 10. `feature/route-segment-model` | Pipelines de Dummy, regressão linear e Random Forest; referência física; avaliação e artefato | Features, serialização, previsões inválidas e avaliação no teste reservado | Escolha do modelo justificada por métricas, simplicidade e desempenho; relatório com limitações |
| 11. `feature/intelligent-routing-api` | Predictor em lote, integração com Dijkstra, `POST /api/routes/fastest` e health de prontidão | Contrato, cobertura geográfica, modelo ausente e cenários de tráfego por trecho | API retorna a rota de menor soma dos tempos previstos, com versões e falhas controladas |
| 12. `feature/delivery-route-integration` | Porta `RouteOptimizer`, cliente HTTP e persistência do plano por entrega | Contrato Java, timeouts, resposta inválida, serviço indisponível e resultado obsoleto | Delivery registra a rota sem conhecer ML; falha remota não corrompe estado ou plano anterior |
| 13. `feature/route-intelligence-compose` | Dockerfiles e Compose para os serviços necessários à demonstração | Rede por hostnames, health checks, artefato, indisponibilidade Python e recuperação | Fluxo reproduzível com containers, sem treino no startup e sem apagar volumes |
| 14. `feature/delivery-segment-observations` | Registrar travessias por trecho e exportar features históricas com rótulos posteriores | Duplicatas, eventos fora de ordem, timestamps, rótulos incompletos e cortes temporais | Previsão e resultado associáveis por trecho; eventos simulados identificados, coleta real não presumida |
| 15. `feature/segment-observation-evaluation` | Importar exports, validar snapshots e comparar previsões armazenadas com tempos observados | Contrato Java, duplicatas/conflitos, disponibilidade, métricas, identidade e CLI | Relatório reproduzível por modelo/grafo, proveniência e corte explícitos; sem inferir rotas completas ou retreinar |
| 16. `feature/segment-observation-dataset` | Preparar partições temporais por entrega com manifesto próprio | Disponibilidade, fronteiras, isolamento, proveniência, integridade, vazias e CLI | CSVs simulados preservados e carregamento reconstrói a política; sem misturar com o dataset sintético ou treinar |
| 17. `feature/segment-observation-training` | Treinar modelos offline sobre as partições preparadas e avaliar no teste reservado | Proveniência, grafo, isolamento do teste, serialização, limites numéricos e CLI | Bundle simulado próprio, rejeitado pelo runtime atual; comparação de tempos por trecho sem inferir a melhor rota |
| 18. `feature/restaurant-menu` | Cardápio por restaurante, preço BRL e disponibilidade, com domínio, JPA, V3 e API | Centavos sem arredondamento, ownership, consultas, paginação, preservação dos restaurantes e Gateway | Cardápio administrável e persistido; pedidos ainda sem itens e totais |
| 19. `feature/order-items` | Composição comercial, consulta HTTP ao catálogo, snapshots e total BRL, com V3 e contrato de pedidos | Precisão monetária, seleção inválida, falhas sem gravação parcial, rollback, legados e preservação nas transições | Novos pedidos exigem itens; cardápio alterado não modifica composição salva; fluxo de entrega preservado |
| 20. `feature/user-profiles` | Perfil com e-mail único e endereços próprios, JPA, Flyway V1 e API pelo Gateway | Formato/normalização, duplicidade concorrente, ownership, paginação, constraints e reinício | Perfil e endereços persistidos; UUID/e-mail preservados nas atualizações; login e autorização em etapas próprias |
| 21. `feature/authentication` | Cadastro atômico com senha BCrypt, login e Bearer JWT de 15 minutos, com V2 e chave local obrigatória | Senhas multibyte, claims/algoritmo/expiração, erros HTTP, concorrência, rollback, legados e reinício | Registro/login e identidade autenticada pelo Gateway; recursos anteriores ainda públicos até a autorização |
| 22. `feature/resource-authorization` | Perfis/endereços restritos ao dono, validação do JWT no Order, cliente gravado no pedido (V4) e acesso ao pedido somente por ele | Tokens ausentes/inválidos, recurso alheio como `404`, identidade ignorada no corpo, legados sem dono, Gateway e reinício | Recursos do cliente protegidos; catálogo, entregas e papéis operacionais ainda públicos |
| 23. `feature/simulated-payments` | Tentativas com aprovação/recusa fixadas pelo método, `Idempotency-Key`, PostgreSQL próprio (V1), JWT e API pelo Gateway | Centavos, chave repetida/reutilizada, aprovação única sob concorrência, recusa repetível, `401`/`404` e reinício | Pagamento simulado registrado e idempotente; pedido ainda não conferido nem condicionado ao pagamento |

Se persistência e API ficarem grandes demais para revisar juntas, dividir a etapa em dois PRs. O mesmo vale para a criação das imagens Docker dos serviços.

Os dados sintéticos permitem treinar o primeiro modelo antes de existir coleta real. As observações do Delivery permitem auditar previsões, preparar partições e treinar outro modelo offline, preservando a origem simulada. Sua publicação na API requer uma etapa própria de contrato e validação.

## Plano de entregas para a V1

A V1 deverá permitir usuário → cardápio → pedido com itens e valores preservados → pagamento explicitamente simulado → entrega → acompanhamento da rota pela interface web. O plano inicial desta fase estimava 12–15 PRs e detalhou as 14 entregas abaixo. Cardápio, itens de pedidos, perfis de usuários, autenticação e autorização dos recursos já foram integrados; pagamentos simulados também. A integração contínua foi antecipada e está na feature atual; após seu merge, restam 7 entregas planejadas. O tamanho e os critérios de revisão podem alterar as divisões; a quantidade não é uma meta de histórico.

| Branch proposta | Entrega e critério de aceite |
| --- | --- |
| `feature/restaurant-menu` (PR #25 integrado) | Cadastro, consulta e atualização do cardápio com preço/disponibilidade, migrations e testes |
| `feature/order-items` (PR #26 integrado) | Itens e quantidades, consulta ao catálogo, snapshots monetários e totais; mudança posterior de preço não altera pedido existente |
| `feature/user-profiles` (PR #27 integrado) | Cadastro e perfil de usuário, endereços e persistência própria, com validação |
| `feature/authentication` (PR #28 integrado) | Cadastro com senha, login, senhas protegidas e credenciais de acesso, com testes de sucesso/recusa |
| `feature/resource-authorization` (PR #29 integrado) | Permissões e vínculo entre usuário e recursos; impedir acesso ou alteração de pedido alheio |
| `feature/simulated-payments` (PR #31 integrado) | Tentativas de pagamento simuladas com aprovação/recusa, persistência e idempotência |
| `feature/order-payment-integration` | Regras de compra e recuperação de falhas entre pedido/pagamento/entrega, sem duplicar cobrança ou entrega |
| `feature/frontend-foundation` | Interface web conectada ao Gateway, navegação, login e tratamento de erros; framework definido nessa etapa |
| `feature/frontend-checkout` | Restaurantes, cardápio, quantidades, resumo de valores, criação do pedido e pagamento simulado |
| `feature/frontend-deliveries` | Acompanhamento dos estados e visualização do grafo/rota sintética; ações operacionais autorizadas |
| `feature/ci-validation` (atual) | Builds e testes Java/Python/frontend reproduzíveis em CI, incluindo integrações com PostgreSQL; antecipada antes do frontend, que acrescentará seus jobs |
| `feature/request-observability` | Correlação de requisições, logs úteis e métricas do fluxo/roteamento, sem expor credenciais |
| `feature/model-promotion-validation` | Coleta simulada cobrindo trechos, horários e tráfego; validar contrato, métricas e compatibilidade antes de permitir promoção do modelo |
| `feature/v1-release-validation` | Testes E2E do fluxo completo, falhas e retomada, documentação de execução e limites, checklist de release |

A meta de planejamento é 17/10/2026, com revisão após as primeiras 2–3 entregas desta fase. O fluxo integrado é prioridade; estimativas de tempo e número de PRs devem acompanhar o que foi efetivamente validado.

Pagamentos não movimentarão dinheiro real. Ruas, trânsito e travessias continuam fictícios; maior cobertura da simulação não comprova qualidade no mundo real. A promoção do modelo observacional depende dos resultados e de uma etapa explícita de compatibilidade; o bundle offline atual continua rejeitado pela API. GPS, mapas reais, VRP e cloud permanecem fora desta V1.

## Títulos de PR e mensagens de squash

Para cada branch, usar o mesmo assunto no título do PR e na mensagem final do squash:

| Branch | Assunto sugerido |
| --- | --- |
| `feature/route-intelligence-design` | `docs: define route intelligence architecture and roadmap` |
| `feature/restaurant-location` | `feat: add restaurant pickup locations` |
| `feature/order-domain` | `feat: add minimal order lifecycle` |
| `feature/delivery-domain` | `feat: model delivery lifecycle rules` |
| `feature/delivery-persistence` | `feat: persist deliveries and couriers with PostgreSQL` |
| `feature/delivery-lifecycle` | `feat: expose delivery lifecycle API` |
| `feature/order-delivery-integration` | `feat: create deliveries from confirmed orders` |
| `feature/route-intelligence-foundation` | `feat: scaffold route intelligence service` |
| `feature/road-graph` | `feat: add synthetic road graph routing` |
| `feature/route-segment-dataset` | `feat: generate reproducible segment travel data` |
| `feature/route-segment-model` | `feat: train and evaluate segment travel time models` |
| `feature/intelligent-routing-api` | `feat: expose fastest predicted route API` |
| `feature/delivery-route-integration` | `feat: integrate delivery route optimization` |
| `feature/route-intelligence-compose` | `chore: containerize the route intelligence demo` |
| `feature/delivery-segment-observations` | `feat: record segment travel observations` |
| `feature/segment-observation-evaluation` | `feat: evaluate exported segment travel observations` |
| `feature/segment-observation-dataset` | `feat: prepare temporal datasets from delivery observations` |
| `feature/segment-observation-training` | `feat: train and evaluate offline models from delivery observations` |
| `feature/restaurant-menu` | `feat: add restaurant menu items and pricing` |
| `feature/order-items` | `feat: add priced order items and preserve menu snapshots` |
| `feature/user-profiles` | `feat: add user profiles and saved addresses` |
| `feature/authentication` | `feat: add password registration and JWT authentication` |
| `feature/resource-authorization` | `feat: restrict profiles, addresses and orders to their owner` |
| `feature/simulated-payments` | `feat: add idempotent simulated payment attempts` |
| `feature/ci-validation` | `ci: validate Java and Python builds on GitHub Actions` |

Os commits de implementação podem separar domínio, persistência e API, sempre acompanhados dos testes correspondentes. Por exemplo: `feat: model restaurant pickup location` e `feat: persist restaurant pickup locations`.

Antes de abrir o PR, conferir os critérios da etapa. Mudanças de documentação pedem revisão de links e exemplos. Mudanças de código pedem testes e build; persistência exige Testcontainers, e integrações precisam de verificação entre os serviços. Registrar no PR o que passou e o que não foi executado.

## Evoluções posteriores à V1

- Fonte real de mapas e tráfego: definir licença, cobertura, atualização e associação de posições aos trechos antes de integrar OpenStreetMap, OSRM, GraphHopper ou APIs externas.
- VRP: múltiplas entregas e ordem de visitas, depois da rota de uma entrega funcionar.
- Atribuição de entregadores: critérios de capacidade, carga e custo, sem confundir esse problema com previsão por trecho.
- Mensageria: eventos duráveis, idempotência e outbox apenas quando o fluxo exigir; não colocar um broker entre aplicação e banco.
- MLOps ampliado: tracking de experimentos, registry, drift e automação de retreinamento após os critérios de validação/promoção e a CI da V1.
- Cloud: avaliar AWS ECS/Fargate, RDS, S3 e CloudWatch depois da demonstração local reproduzível.

Essas etapas serão detalhadas após a validação da V1; não são requisitos para encerrar a demonstração comercial planejada.
