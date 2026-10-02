# Roadmap

O catálogo foi integrado pelo PR #6, a localização de coleta pelo PR #8, o ciclo mínimo de pedidos pelo PR #9, o domínio de entregas pelo PR #10 e a persistência de entregas pelo PR #11. A etapa de entregas foi dividida em persistência e API para manter os PRs revisáveis. Depois, o fluxo receberá previsão de tempo e roteamento em Python.

A base necessária para ML é uma entrega com origem, destino e ciclo de vida definido. Pagamentos, cardápios completos e autenticação podem evoluir separadamente.

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

Se persistência e API ficarem grandes demais para revisar juntas, dividir a etapa em dois PRs. O mesmo vale para a criação das imagens Docker dos serviços.

Os dados sintéticos permitem treinar o primeiro modelo antes de existir coleta real. A última etapa acrescenta o registro das travessias para comparar o tempo previsto com o observado.

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

Os commits de implementação podem separar domínio, persistência e API, sempre acompanhados dos testes correspondentes. Por exemplo: `feat: model restaurant pickup location` e `feat: persist restaurant pickup locations`.

Antes de abrir o PR, conferir os critérios da etapa. Mudanças de documentação pedem revisão de links e exemplos. Mudanças de código pedem testes e build; persistência exige Testcontainers, e integrações precisam de verificação entre os serviços. Registrar no PR o que passou e o que não foi executado.

## Evoluções posteriores

- Fonte real de mapas e tráfego: definir licença, cobertura, atualização e associação de posições aos trechos antes de integrar OpenStreetMap, OSRM, GraphHopper ou APIs externas.
- VRP: múltiplas entregas e ordem de visitas, depois da rota de uma entrega funcionar.
- Atribuição de entregadores: critérios de capacidade, carga e custo, sem confundir esse problema com previsão por trecho.
- Mensageria: eventos duráveis, idempotência e outbox apenas quando o fluxo exigir; não colocar um broker entre aplicação e banco.
- MLOps: tracking, versionamento mais completo, registry, métricas de inferência, drift, retreinamento e CI/CD após o primeiro modelo funcional.
- Cloud: avaliar AWS ECS/Fargate, RDS, S3 e CloudWatch depois da demonstração local reproduzível.

Essas etapas serão detalhadas depois que a primeira integração de rotas estiver funcionando.
