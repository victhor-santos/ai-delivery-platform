# Grafo sintético e roteamento por tempo

Route Intelligence já calcula caminhos dirigidos com Dijkstra. O grafo é imutável, e cada cálculo recebe seu próprio mapa de tempos por trecho. O comando de demonstração usa velocidades fixas de referência. As etapas seguintes acrescentaram [modelo e consulta HTTP](intelligent-routing-api.md) e [integração com Delivery](delivery-route-integration.md). Trânsito real e GPS continuam fora do cenário.

## Cenário de demonstração

O arquivo `services/route-intelligence-service/app/routing/data/synthetic-city-v1.json` contém 7 nós e 10 trechos dirigidos. Ele acompanha o pacote Python, incluindo o wheel. As coordenadas situam o exemplo na região de São Paulo, mas os caminhos e as velocidades são fictícios.

| Nó | Papel no cenário |
| --- | --- |
| A | Localização de coleta da Cantina Central, restaurante fictício |
| B | Via residencial da alternativa mais curta |
| C | Destino da entrega fictícia |
| D e E | Alternativa por vias primária e expressa |
| F | Ramificação com ida e volta, sem vantagem para chegar a C |
| G | Nó isolado, usado para demonstrar ausência de caminho |

A representa `lat: -23.5505, lon: -46.6333`, e C representa `lat: -23.5610, lon: -46.6560`. O fixture não cadastra restaurante ou pedido nos bancos. Quando a integração de rotas estiver pronta, esses pontos poderão ser usados como coleta e destino pelas APIs existentes.

Os custos de referência são calculados em minutos por `60 * distance_km / reference_speed_kmh`:

| Alternativa de A para C | Distância | Tempo de referência |
| --- | --- | --- |
| A → B → C | 2,9 km | 11,6 min |
| A → D → E → C | 5,0 km | aproximadamente 6,2333 min |

Dijkstra escolhe A → D → E → C pela menor soma dos tempos. Não arredonda valores intermediários nem filtra caminhos pela distância. Na volta, C → B → A usa trechos próprios; um trecho de ida não cria automaticamente outro de volta.

Um teste do fixture compara cada distância com a distância aproximada em linha reta entre as coordenadas e exige um desvio inferior a 30%. É uma verificação de coerência deste cenário, não uma validação de mapas reais ou uma regra aplicada a qualquer grafo carregado.

## Executar pelo terminal

Prepare o ambiente conforme o [guia do serviço Python](route-intelligence-foundation.md). Da raiz, sem iniciar servidor, Java ou Docker:

```powershell
uv run --project .\services\route-intelligence-service --locked python -m app.routing --origin A --destination C
# Volta: trechos e custos podem ser diferentes
uv run --project .\services\route-intelligence-service --locked python -m app.routing --origin C --destination A
# Mesmo nó: uma coordenada, nenhum trecho e totais zero
uv run --project .\services\route-intelligence-service --locked python -m app.routing --origin A --destination A
# Falha controlada, código de saída 2: não há caminho até G
uv run --project .\services\route-intelligence-service --locked python -m app.routing --origin A --destination G
```

A primeira chamada devolve JSON com `node_ids: ["A", "D", "E", "C"]`, `distance_km: 5.0` e `travel_time_minutes: 6.233333333333333`, além de coordenadas, trechos e metadados. `cost_basis: reference_speed` identifica a referência física, e `data_origin: synthetic` identifica a origem fictícia. O resultado não contém `model_version` nem é apresentado como previsão de trânsito.

`--origin` e `--destination` recebem IDs de nós, não endereços ou coordenadas arbitrárias. Sem argumentos, usam A e C. `--graph caminho.json` permite carregar outro arquivo compatível; sem essa opção, o comando usa o recurso empacotado `synthetic-city-v1`. JSON inválido, nó desconhecido, custos inválidos e ausência de caminho são reportados no stderr com código de saída 2, sem stack trace.

## Schema, limites e versionamento

| Elemento | Regra |
| --- | --- |
| Arquivo | JSON, no máximo 1 MiB; a leitura é limitada antes da validação |
| Metadados | `graph_version`, `timezone: America/Sao_Paulo`, `vehicle_profile: motorcycle`, `data_origin: synthetic` |
| Identificadores | De 1 a 64 caracteres ASCII; começam com letra ou número e aceitam depois letras, números, ponto, hífen e underscore |
| Nós | De 1 a 200; `node_id` único, `lat` e `lon` numéricos e finitos, nos limites geográficos inclusivos |
| Trechos | De 0 a 1000; `segment_id` único, `from_node` e `to_node` existentes |
| Distância e velocidade | Números finitos e estritamente positivos; strings numéricas e booleanos são rejeitados |
| Tipo de via | `residential`, `primary` ou `highway` |
| Conexões | No máximo um trecho por par ordenado de nós; direção inversa exige outro trecho |
| Campos extras | Rejeitados em metadados, nós e trechos |

Nós isolados, ciclos e laços dirigidos positivos são permitidos. Listas tornam-se tuplas; nós, trechos e metadados são congelados, e os índices expõem somente leitura. Uma alteração nos atributos do cenário exige um novo arquivo e `graph_version`; a carga não consulta um registro externo de versões.

## Algoritmo e integração

`app/routing/graph.py` valida e carrega o grafo. `app/routing/dijkstra.py` recebe o grafo, os IDs de origem/destino e um mapa `segment_id → minutos`. A API fornece esse mapa a partir da previsão em lote; Dijkstra não depende de ML, HTTP ou banco. O mapa precisa cobrir exatamente todos os trechos, mesmo os que não forem visitados. Valores não numéricos, zero, negativos, NaN e infinitos são rejeitados antes da busca.

O algoritmo usa lista de adjacência e heap de pares `(tempo acumulado, node_id)`. Em igualdade exata de tempo, o heap prioriza o menor identificador de nó. Os trechos são percorridos em ordem de `segment_id`, e uma rota com custo igual não substitui o predecessor encontrado primeiro. Isso mantém o resultado estável quando a ordem do JSON muda; não promete escolher a sequência inteira lexicograficamente mínima. Tempos diferentes, mesmo próximos, não são tratados como empate.

O resultado contém versão do grafo, nós e trechos ordenados, distância total e tempo total. Origem igual ao destino retorna um nó e totais zero. Nó desconhecido é distinto de ausência de caminho dirigido. Somas que excedem a representação finita de ponto flutuante geram falha controlada. O cálculo copia os custos e não grava tempos no grafo compartilhado.

A busca e a preparação das adjacências usam memória O(V + E), com custo de tempo O((V + E) log V) para os grafos simples suportados. O [contrato HTTP](route-intelligence-contract.md) define associação de coordenadas aos nós, contexto, previsões, status HTTP e prontidão. A API carrega os recursos no startup e retorna `200 UP` somente quando grafo, modelo e tráfego estiverem prontos; a CLI de referência continua independente desse carregamento.

## Testes, build e medição

```powershell
uv run --project .\services\route-intelligence-service --locked python -m pytest .\services\route-intelligence-service\tests
uv run --project .\services\route-intelligence-service --locked python -m ruff check .\services\route-intelligence-service
uv run --project .\services\route-intelligence-service --locked python -m ruff format --check .\services\route-intelligence-service
uv build --project .\services\route-intelligence-service
# Exibir as medições dos testes de desempenho
uv run --project .\services\route-intelligence-service --locked python -m pytest .\services\route-intelligence-service\tests\test_routing_performance.py -s
```

Registro da etapa do grafo, verificado em 2026-10-02 com Python 3.12.14 e uv 0.12.11:

- 116 testes passaram, incluindo os 14 da base FastAPI, validação do grafo, direção, desconexão, ciclos, soma, empates, overflow, imutabilidade e comandos de demonstração.
- Os custos foram comparados com enumeração independente de caminhos simples em 20 grafos pequenos gerados com seed fixa, para todos os pares de nós.
- Ruff, formatação, build e revisão do diff passaram. O wheel foi instalado em ambiente separado, e o comando funcionou fora do repositório com o JSON empacotado, inclusive na falha de ausência de caminho.

| Teste local, sem leitura de arquivo, ML ou HTTP | Medição observada | Orçamento do teste |
| --- | --- | --- |
| 1000 cálculos A → C no fixture de 7 nós e 10 trechos | 0,0163 s para o lote | menos de 3 s |
| 100 cálculos num grafo de teste com 200 nós e 1000 trechos | 0,0691 s para o lote | menos de 3 s |

O grafo de limite é gerado somente para exercitar tamanho e algoritmo, sem representar geografia. O orçamento é uma guarda ampla contra regressões, não um SLA. As medições são locais; inferência, carregamento, processo, HTTP e concorrência precisam ser medidos quando existirem. Os timeouts futuros não são garantidos por estes resultados.

Java, migrations, Gateway, Compose e dependências Python não mudaram nesta feature; as suítes Maven não foram repetidas. A próxima etapa é gerar o dataset sintético reproduzível, com schema de features, seed, manifesto e partições que evitem vazamento de informação.
