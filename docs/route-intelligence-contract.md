# Contrato de rotas

Contrato previsto para a integração entre Delivery e Route Intelligence. Os endpoints ainda não existem. A divisão de responsabilidades está na [arquitetura](route-intelligence.md).

## Porta Java

A aplicação Java acessará o cálculo de rotas pela interface `application/RouteOptimizer`:

```java
OptimizedRoute optimizeRoute(GeoPoint origin, GeoPoint destination, RouteContext context);
```

`RouteContext` leva a partida planejada. `OptimizedRoute` devolve o caminho, os custos e as versões usadas. Esses tipos pertencem à aplicação Java e não dependem de HTTP, JPA ou bibliotecas Python. A interface distingue três falhas: entrada não suportada, ausência de caminho e serviço indisponível.

`infrastructure/FastApiRouteOptimizerClient` implementa a interface e converte os tipos Java para os DTOs HTTP. A URL é configurável: `http://localhost:8000` na máquina e `http://route-intelligence-service:8000` na rede Docker.

Antes de salvar o plano, o cliente confere os campos obrigatórios, a ordem dos trechos, os custos e os totais. Uma resposta inválida é tratada como falha de integração.

## POST /api/routes/fastest

O corpo usa `application/json` e aceita apenas os campos abaixo. Tráfego, pesos das arestas e modelo são definidos pelo serviço.

```json
{
  "origin": {"lat": -23.5505, "lon": -46.6333},
  "destination": {"lat": -23.5610, "lon": -46.6560},
  "departure_at": "2026-09-29T19:00:00-03:00"
}
```

Regras da entrada:

- Latitude e longitude serão números finitos nos limites geográficos válidos.
- `departure_at` será um instante RFC 3339 com offset obrigatório; data sem fuso será inválida.
- Horário e dia da semana serão derivados no fuso do grafo, inicialmente `America/Sao_Paulo`, com segunda-feira igual a zero. Não haverá campos independentes `hour` e `day_of_week` na requisição.
- A primeira versão associará cada ponto ao nó mais próximo dentro de uma tolerância de 1 metro, apenas para acomodar precisão numérica. Empates serão resolvidos por identificador de nó. A resposta usará as coordenadas dos nós.
- Pontos fora dessa tolerância serão rejeitados como fora da cobertura. Não haverá geocodificação, conexão automática com ruas ou cálculo de acesso entre um endereço arbitrário e o grafo. A distância retornada corresponderá somente às arestas do cenário.
- O contexto de tráfego será obtido pelo serviço a partir do cenário sintético configurado, por trecho. Partidas futuras não representarão uma previsão real de trânsito: a demonstração usará as hipóteses documentadas do gerador.

### Resposta de sucesso: 200

Exemplo com dados sintéticos:

```json
{
  "route": [
    {"lat": -23.5505, "lon": -46.6333},
    {"lat": -23.5540, "lon": -46.6400},
    {"lat": -23.5610, "lon": -46.6560}
  ],
  "segments": [
    {
      "segment_id": "A-B",
      "distance_km": 0.9,
      "predicted_travel_time_minutes": 3.2
    },
    {
      "segment_id": "B-C",
      "distance_km": 2.0,
      "predicted_travel_time_minutes": 4.5
    }
  ],
  "distance_km": 2.9,
  "predicted_travel_time_minutes": 7.7,
  "predicted_at": "2026-09-29T22:00:00Z",
  "context_as_of": "2026-09-29T21:59:00Z",
  "model_version": "synthetic-segment-v1",
  "graph_version": "synthetic-city-v1",
  "data_origin": "synthetic"
}
```

Os trechos seguem a ordem do percurso. Uma rota com N trechos tem N+1 coordenadas, e os totais são a soma dos valores por trecho. Os testes devem usar uma tolerância numérica para essas somas, por causa da representação de ponto flutuante.

Cada trecho terá distância e tempo estritamente positivos. Origem e destino associados ao mesmo nó retornarão uma única coordenada, lista de trechos vazia e totais zero. Na primeira versão, empates de custo terão desempate estável por identificadores, sem depender da ordem de carregamento do JSON.

`model_version` e `graph_version` identificam versões imutáveis. Modelo, schema de features e grafo precisam permanecer compatíveis durante a requisição. O campo `segments` permite registrar o plano e associar as travessias observadas aos trechos. Hiperparâmetros, arquivos internos e dados de treinamento ficam fora da resposta.

`context_as_of` indica o instante do snapshot usado na previsão e deve ser menor ou igual a `predicted_at`. O serviço guarda também a fonte e os horários de cada observação de tráfego. Medições recebidas depois da previsão não podem entrar nesse snapshot.

### Respostas de erro

Erros usarão `application/problem+json`, com campos `type`, `title`, `status`, `detail` e `code`. Exemplo:

```json
{
  "type": "about:blank",
  "title": "Route service unavailable",
  "status": 503,
  "detail": "Route calculation is temporarily unavailable.",
  "code": "MODEL_UNAVAILABLE"
}
```

| Situação | HTTP Python | Código | Tratamento na aplicação Java |
| --- | --- | --- | --- |
| JSON malformado, campos inválidos ou extras | 422 | `INVALID_REQUEST` | Falha de validação do contrato |
| Origem/destino fora da cobertura sintética | 422 | `OUTSIDE_GRAPH_COVERAGE` | Localização não atendida, sem criar plano |
| Nós válidos, mas nenhum caminho dirigido | 404 | `ROUTE_NOT_FOUND` | Ausência de rota, distinta de entrega inexistente |
| Modelo ausente, ilegível ou incompatível | 503 | `MODEL_UNAVAILABLE` | Indisponibilidade controlada |
| Grafo ausente ou inválido | 503 | `GRAPH_UNAVAILABLE` | Indisponibilidade controlada |
| Previsão não finita ou não positiva | 503 | `INVALID_PREDICTION` | Indisponibilidade controlada; não corrigir silenciosamente o custo |
| Falha inesperada | 500 | `INTERNAL_ERROR` | Falha de integração, sem repassar detalhes internos |

Falha de conexão, timeout e resposta inválida também viram indisponibilidade no adaptador Java. As respostas de erro não incluem stack trace, dados de conexão ou caminho do artefato.

## GET /health

Na primeira etapa, verifica apenas se a aplicação está funcionando. Quando a inferência estiver implementada, retorna `200` com `{"status":"UP"}` se modelo, schema de features e grafo estiverem carregados e compatíveis. Caso contrário, retorna `503` com `{"status":"DOWN"}`. A API deve continuar respondendo ao health mesmo sem o modelo; ela não executa treinamento para se recuperar.

## Resiliência e consistência

Vamos começar com timeout de conexão de 1 segundo e de resposta de 3 segundos, configuráveis e ajustados após medir o grafo de demonstração. Não haverá retentativa automática nessa versão. A branch `feature/road-graph` definirá os limites de nós e arestas junto com os testes de desempenho.

Se o serviço estiver indisponível, o Delivery retorna `503` na operação de planejamento. O cliente pode tentar novamente. A rota anterior fica armazenada com seu instante e versão, mas não é apresentada como uma nova previsão. Também não haverá troca automática por uma rota calculada apenas pela distância.

O caso de uso lê a entrega, encerra a leitura, chama Python e salva a resposta em uma transação curta. Antes de salvar, confere se o estado ou a versão da entrega mudou. O contrato público de Delivery definirá os status das demais falhas de negócio, sem copiar automaticamente os status internos do Python.

## Testes

- FastAPI: entrada válida, inválida, fora da cobertura, ausência de caminho e modelo indisponível, com status e schemas acima.
- Predictor: mesma transformação no treinamento e inferência, artefato incompatível e previsões inválidas.
- Roteamento: origem igual ao destino, direção das arestas, desconexão, empates, custo total e rota mais longa em km que vence em tempo.
- Java: servidor HTTP de teste para contrato, timeout, falha de conexão, `422`, `404`, `503`, `500` e resposta malformada.
- Caso de uso Java: nenhuma alteração indevida de status ou plano após falha; rejeição de resultado obsoleto por concorrência.
- Integração final: Java e Python reais na rede Docker, incluindo parada do container Python e recuperação por nova chamada.

Os testes de Dijkstra usam custos fixos. O modelo tem testes e avaliação próprios, para que uma mudança nas previsões não seja confundida com um erro do algoritmo.
