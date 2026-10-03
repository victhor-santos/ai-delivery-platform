# Route Intelligence

Este documento descreve a evolução planejada do projeto. O catálogo já possui restaurantes e localização de coleta. Pedidos têm cadastro, consulta, confirmação e cancelamento. Delivery possui [regras de domínio testadas](delivery-domain.md), [persistência PostgreSQL](delivery-persistence.md) e [API HTTP do ciclo de entregas](delivery-lifecycle.md). A [integração entre pedidos e entregas](order-delivery-integration.md), a [base Python](route-intelligence-foundation.md), o [roteamento com tempos de referência](road-graph.md), o [dataset sintético](route-segment-dataset.md) e o [modelo com avaliação offline](route-segment-model.md) estão implementados. A [consulta HTTP de rotas previstas](intelligent-routing-api.md) também está implementada. A [integração com Delivery](delivery-route-integration.md) também está implementada.

## Objetivo

Escolher a rota com menor tempo previsto para uma entrega. Uma rota de 11 km em 19 minutos deve ser preferida a uma de 8 km em 31 minutos. A distância entra no cálculo e aparece na resposta, mas a escolha é feita pelo tempo.

Java/Spring Boot continua cuidando dos pedidos, das entregas e das transações. Python fica com a previsão de tempo e o cálculo do caminho. Vamos começar com uma entrega por vez para cada entregador, atribuição manual e deslocamento por motocicleta. O grafo sintético terá apenas vias permitidas para esse veículo.

Os primeiros dados de ruas e trânsito serão sintéticos. Mapas reais, múltiplas entregas, atribuição automática, MLOps e cloud ficam para etapas posteriores, descritas no [roadmap](roadmap.md). LLMs, RAG e agents não fazem parte dessa solução.

## Responsabilidades

| Componente | Responsabilidade |
| --- | --- |
| Catalog Service | Restaurante e localização de coleta |
| Order Service | Pedido, referência ao restaurante e destino |
| Delivery Service | Atribuição do entregador, coleta, transporte, estados e plano de rota |
| Route Intelligence | Previsão de tempo e cálculo do caminho |
| ML Predictor | Estimar quantos minutos cada trecho deve levar |
| Routing Engine | Encontrar o caminho com a menor soma desses tempos |

O modelo aprende a estimar o tempo dos trechos. Dijkstra usa essas estimativas para escolher o caminho; ele é um algoritmo de grafos, não Machine Learning. As regras da entrega continuam no Java.

```mermaid
flowchart TD
    Client[Cliente] --> Gateway[API Gateway]
    Gateway --> Order[Order Service - Java]
    Gateway --> Delivery[Delivery Service - Java]
    Order -->|HTTP: solicitar entrega| Delivery
    Delivery --> Port[RouteOptimizer - porta da aplicacao]
    Port --> Adapter[FastApiRouteOptimizerClient]
    Adapter -->|HTTP| API[Route Intelligence - FastAPI]
    API --> Graph[Grafo e contexto dos trechos]
    Graph --> Predictor[ML Predictor - minutos por trecho]
    Predictor --> Routing[Dijkstra - soma dos custos]
    Routing --> Result[Rota com menor tempo previsto]
```

O cliente acessa o sistema pelo Gateway. O Delivery chama o serviço Python internamente, sem expor uma nova rota pública no Gateway. Order e Delivery já se comunicam por HTTP, com criação idempotente de entregas. Mensageria pode entrar quando precisarmos processar eventos de forma independente e garantir sua entrega.

## O que falta no backend

O catálogo já permite informar coordenadas no cadastro ou em uma atualização, sem serviço externo de geocodificação. Restaurantes sem localização continuam válidos no catálogo, mas precisarão desse dado antes de serem usados em uma entrega.

O pedido já possui UUID, `restaurantId`, destino, timestamps e os estados `CREATED`, `CONFIRMED` e `CANCELLED`. A confirmação é manual e não indica pagamento aprovado; produtos e cobrança ainda não foram implementados.

Somente um pedido confirmado pode solicitar entrega. A integração verifica restaurante ativo e localização na primeira solicitação, persiste os snapshots e cria/recupera a entrega por HTTP. Cancelar o pedido depois da intenção de entrega exige coordenação entre serviços; essa operação é rejeitada nesta versão. O [contrato de integração](order-delivery-integration.md) descreve falhas, novas tentativas e limites.

### Delivery

| Elemento | Dados propostos |
| --- | --- |
| `Delivery` | `id`, `orderId`, `origin`, `destination`, `courierId` opcional até a atribuição, `status` e versão para concorrência |
| `DeliveryLocation` | Descrição do local e `GeoPoint`, copiados para a entrega; não presume geocodificação |
| `GeoPoint` | Latitude finita entre -90 e 90 e longitude finita entre -180 e 180, em WGS84 |
| `Courier` | Identidade local mínima e indicador ativo; atribuição manual |
| `DeliveryRoutePlan` | Referência à entrega com snapshots imutáveis, partida planejada, horário local, resposta prevista e versão usada no commit |
| `SegmentTraversal` | Passagem observada por um trecho; será adicionada na etapa de coleta de observações |

Cada serviço acessa apenas o próprio banco, sem chaves estrangeiras entre bancos. A entrega guarda uma cópia da origem e do destino. Assim, mudar o endereço de um restaurante ou pedido não altera uma entrega já criada.

Estados da entrega:

```text
CREATED -> ASSIGNED -> PICKED_UP -> IN_TRANSIT -> DELIVERED
CREATED -> CANCELLED
ASSIGNED -> CANCELLED
```

Regras iniciais:

- `orderId` é único no banco. Repetir a criação com os mesmos dados retorna a entrega existente; dados diferentes geram conflito.
- O entregador precisa estar ativo e sem outra entrega em andamento. O deslocamento só começa depois da coleta.
- Entregas concluídas ou canceladas não mudam mais de estado. O cancelamento só é permitido antes da coleta.
- Uma versão no registro detecta atualizações concorrentes e evita que uma requisição sobrescreva a outra.
- Os timestamps usam `Instant` em UTC: `createdAt`, `updatedAt`, `assignedAt`, `pickedUpAt`, `departedAt`, `arrivedAt`, `deliveredAt` e `cancelledAt`. Nos testes, o relógio é controlado.
- A chegada é registrada durante `IN_TRANSIT`. A conclusão exige chegada registrada e horários coerentes. Separar esses eventos permite distinguir tempo de trânsito de tempo de atendimento.

A organização segue o catálogo: domínio e aplicação em Java puro, DTOs em `api` e persistência e clientes HTTP em `infrastructure`. O serviço Python não acessa esses bancos nem altera estados da entrega.

## Grafo e escolha da rota

O primeiro grafo é pequeno, sintético e armazenado em JSON. Cada nó possui `node_id`, `lat` e `lon`. Cada aresta representa um trecho dirigido, com `segment_id`, `from_node`, `to_node`, `distance_km`, `road_type` e `reference_speed_kmh`. O [guia do grafo](road-graph.md) registra o cenário, os limites e a validação.

Distância e velocidade precisam ser finitas e positivas. Uma via de mão dupla terá duas arestas. Nesta versão, haverá no máximo uma aresta por par ordenado de nós.

As distâncias do fixture são coerentes com as coordenadas do cenário, sem representar ruas reais. O arquivo possui `graph_version`, `timezone: America/Sao_Paulo`, `vehicle_profile: motorcycle` e `data_origin: synthetic`. Alterar a geometria ou os atributos estáticos exige uma nova versão.

O tráfego vem de um cenário sintético configurado por trecho, carregado uma vez com fonte e instante de disponibilidade. Cada requisição calcula seu próprio `predicted_travel_time_minutes`, mantendo o grafo e o snapshot compartilhados inalterados.

Dijkstra já usa lista de adjacência e `heapq` da biblioteca padrão, com custos de tempo separados do grafo. A demonstração usa `60 * distance_km / reference_speed_kmh`; esse valor é uma referência física, não uma previsão de ML. Isso atende ao grafo pequeno e aos custos positivos sem precisar de NetworkX. A* pode ser avaliado se o desempenho justificar a mudança; ele também exigiria uma heurística admissível de tempo. Usar distância diretamente não atenderia à unidade do custo. [Referência de Dijkstra](https://networkx.org/documentation/stable/reference/algorithms/generated/networkx.algorithms.shortest_paths.weighted.dijkstra_path.html).

Fluxo implementado na consulta HTTP:

1. Validar entrada e associar as coordenadas aos nós do cenário.
2. Obter os atributos estáticos e o snapshot de contexto por trecho.
3. Prever em lote os custos de todas as arestas do grafo pequeno, sem uma chamada HTTP por aresta.
4. Rejeitar previsões não finitas ou não positivas com erro controlado.
5. Executar Dijkstra, reconstruir o caminho e somar tempos e distâncias sem arredondamentos intermediários.

Filtrar primeiro pelas menores distâncias poderia descartar a rota mais rápida. Por isso, a previsão cobre todas as arestas do grafo pequeno. Os cenários de teste devem variar o tráfego entre trechos: multiplicar todos os custos pelo mesmo fator não muda a escolha do caminho.

A primeira versão usa o contexto da partida planejada durante todo o cálculo. Ela não prevê mudanças no trânsito ao longo da viagem. Considerar o horário de chegada a cada trecho exigirá rever tanto o modelo quanto o algoritmo.

## Serviço Python

O serviço já possui `app/main.py`, execução por `python -m app`, configuração, dependências, testes e health check. `pyproject.toml` declara dependências e ferramentas; `uv.lock` fixa as versões resolvidas, incluindo as transitivas, sem manter uma segunda lista de dependências em `requirements.txt`. A estrutura abaixo será completada aos poucos, conforme as funcionalidades entrarem:

```text
services/route-intelligence-service/
    app/
        main.py
        __main__.py
        config.py
        api/health.py
        api/routes.py
        api/problems.py
        schemas/routes.py
        services/runtime.py
        services/route_planner.py
        routing/graph.py
        routing/coordinates.py
        routing/coverage.py
        routing/provenance.py
        routing/traffic.py
        routing/dijkstra.py
        routing/demo.py
        routing/__main__.py
        routing/data/synthetic-city-v1.json
        routing/data/synthetic-traffic-v1.json
        ml/features.py
        ml/pipelines.py
        ml/artifacts.py
        ml/predictor.py
    training/
        generate_dataset.py
        schema.py
        synthetic.py
        splits.py
        dataset.py
        load_dataset.py
        serialization.py
        experiment.py
        metrics.py
        route_evaluation.py
        train.py
        evaluate.py
    data/synthetic/
    artifacts/
    tests/
    pyproject.toml
    uv.lock
    .python-version
```

A base usa Python 3.12+, FastAPI, Pydantic, Pydantic Settings e Uvicorn, com pytest e Ruff em desenvolvimento. A versão de referência do Python é 3.12, registrada em `.python-version`; as dependências estão travadas em `uv.lock`. O gerador usa a biblioteca padrão, Pydantic e `tzdata` fixado para derivar horário e dia no fuso do grafo. Os modelos usam NumPy, scikit-learn e joblib, sem pandas. PyTorch e TensorFlow não são necessários para os modelos previstos.

## Docker e execução

Hoje o Compose executa `catalog-db`, `order-db` e `delivery-db`, com volumes separados. A demonstração completa incluirá o Gateway e os serviços envolvidos na entrega, preservando os volumes existentes.

O endereço do serviço Python será configurável: `http://route-intelligence-service:8000` na rede Docker e `http://localhost:8000` ao executar na máquina. As URLs JDBC dentro dos containers também usarão o hostname do banco.

O serviço Python terá Dockerfile, dependências fixadas e health check. O treinamento será executado à parte, e a API receberá o artefato e seus metadados pela imagem ou por uma montagem somente de leitura. Credenciais reais e `.env` ficam fora do Git. Nenhuma dessas etapas exige apagar volumes.

## Comunicação e concorrência

A consulta de rota começa com uma chamada HTTP e resposta direta, com timeout. JPA mantém suas transações normais, sem `@Async` nesse fluxo. Treinamento roda offline.

Inferência e roteamento consomem CPU. Declarar uma função como `async def` não paraleliza esse trabalho; esse processamento deve ficar fora do event loop. Processos adicionais ou filas só entram se as medições indicarem necessidade. [Concorrência no FastAPI](https://fastapi.tiangolo.com/async/).

O Delivery chama Python fora da transação de banco. Ao salvar a resposta, abre uma transação curta e confere a versão e o estado da entrega. Se o cálculo falhar, a rota anterior e o estado da entrega são preservados.

Os testes e critérios de conclusão de cada etapa estão no [roadmap](roadmap.md). Os detalhes da integração e do modelo estão no [contrato HTTP](route-intelligence-contract.md) e no [plano de dados](route-intelligence-data.md).
