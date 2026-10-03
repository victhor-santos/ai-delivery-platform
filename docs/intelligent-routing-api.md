# API de rotas previstas

`feature/intelligent-routing-api` conecta o predictor existente ao Dijkstra e expõe `POST /api/routes/fastest`. O serviço calcula a rota de menor soma dos tempos previstos, usando um único lote para todas as arestas. Treinamento e geração de dados continuam offline. Java, bancos, Gateway e Compose não foram alterados nesta etapa.

## Executar no Windows

Use o ambiente preparado no [guia Python](route-intelligence-foundation.md) e o bundle Windows gerado conforme o [guia do modelo](route-segment-model.md). Execute da raiz, no primeiro terminal:

```powershell
$env:ROUTE_INTELLIGENCE_MODEL_PATH = (Resolve-Path '.\services\route-intelligence-service\artifacts\segment-model-v1-windows').Path
uv run --project .\services\route-intelligence-service --locked python -m app
```

Não há seleção automática de artefato. O bundle precisa corresponder à plataforma e às versões das dependências. Dados e modelos locais continuam ignorados pelo Git. Sem o diretório configurado, o servidor inicia, mas retorna `503 DOWN` no health e `503 MODEL_UNAVAILABLE` na consulta válida de rota.

No segundo terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
$body = @{
    origin = @{ lat = -23.5505; lon = -46.6333 }
    destination = @{ lat = -23.5610; lon = -46.6560 }
    departure_at = '2026-09-29T19:00:00-03:00'
} | ConvertTo-Json -Depth 3
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/api/routes/fastest -ContentType 'application/json' -Body $body
```

OpenAPI está em `/openapi.json`, e a documentação interativa em `/docs`. Encerre com `Ctrl+C`; depois, se desejar limpar a configuração do terminal, execute `Remove-Item Env:ROUTE_INTELLIGENCE_MODEL_PATH`.

## Entrada e resultado

O [contrato HTTP](route-intelligence-contract.md) define os campos e erros. Coordenadas precisam ser números finitos, dentro dos limites geográficos. Campos extras e coordenadas como strings ou booleanos são rejeitados. A partida exige uma string RFC 3339 com offset, até 64 caracteres; timestamps numéricos e datas sem fuso são inválidos. A entrada é normalizada em UTC e as features temporais são derivadas em `America/Sao_Paulo`.

Cada ponto é associado ao nó mais próximo, usando distância de haversine com raio terrestre de 6.371.000 metros e tolerância de 1 metro. Empates usam o identificador do nó. A resposta contém coordenadas dos nós; não há geocodificação nem acesso automático de endereços ao grafo. A distância corresponde às arestas sintéticas, sem representar ruas reais.

Os custos vêm das seis features já usadas no treinamento: distância, tipo de via, velocidade de referência, tráfego, hora e dia da semana. O cliente não fornece tráfego, modelo, features ou pesos. Todas as arestas são previstas em uma chamada ao predictor, depois Dijkstra escolhe o caminho dirigido. Os totais são somados sem arredondamento intermediário. Origem e destino no mesmo nó retornam uma coordenada, nenhum trecho e totais zero.

A resposta inclui `model_version`, `graph_version`, `predicted_at`, `context_as_of` e `data_origin: synthetic`, além das coordenadas e custos por trecho. Não expõe arquivos internos, hiperparâmetros ou dados de treinamento.

## Tráfego e prontidão

O cenário empacotado `app/routing/data/synthetic-traffic-v1.json` configura tráfego `high` em A-D, D-E e E-C, e `low` nos outros sete trechos. O grafo e o snapshot são imutáveis. A configuração é conhecida no carregamento: `observed_at` e `available_at` são o instante do startup, não horários de medições reais. `context_as_of` é esse instante; `predicted_at` é o horário da requisição. Datas de partida passadas e futuras são aceitas somente como contexto temporal de demonstração.

As variáveis opcionais `ROUTE_INTELLIGENCE_GRAPH_PATH` e `ROUTE_INTELLIGENCE_TRAFFIC_PATH` selecionam arquivos JSON. Sem elas, são usadas as fixtures empacotadas. Caminhos relativos dependem do diretório do processo; prefira caminhos absolutos. O cenário exige exatamente os identificadores das arestas e a versão do grafo, sem campos extras; o arquivo é limitado a 128 KiB. Alterações exigem reiniciar o processo.

O startup carrega grafo, bundle e tráfego uma vez por processo. Confere versão e checksum do grafo, schema, plataforma e dependências do modelo e executa uma previsão inicial de todas as arestas. Recursos compatíveis e custos positivos e finitos permitem `200 UP`; falha mantém `503 DOWN`. O carregamento ocorre fora do event loop e a consulta de rota usa um handler síncrono para executar inferência e Dijkstra no pool de threads.

Não há treinamento, download, recarga ou fallback automático. Previsão inválida retorna `503`; não é substituída por custo físico. Se o relógio da requisição anteceder a disponibilidade do snapshot, a consulta retorna `TRAFFIC_UNAVAILABLE`.

| HTTP | Código |
| --- | --- |
| 422 | `INVALID_REQUEST`, `OUTSIDE_GRAPH_COVERAGE` |
| 404 | `ROUTE_NOT_FOUND` |
| 503 | `MODEL_UNAVAILABLE`, `GRAPH_UNAVAILABLE`, `TRAFFIC_UNAVAILABLE`, `INVALID_PREDICTION` |
| 500 | `INTERNAL_ERROR` |

Esses erros usam `application/problem+json`, com mensagens controladas e sem caminhos de arquivos, stack traces ou reprodução da entrada inválida. Health mantém seu corpo próprio `{"status":"UP"}` ou `{"status":"DOWN"}`.

## Validação desta etapa

Verificado no Windows AMD64 com Python 3.12.14, uv 0.12.11 e dependências do lock:

- 330 testes passaram, com warnings tratados como erros, incluindo contrato, cobertura, desconexão, snapshots, carregamento único, recursos ausentes, previsões inválidas e requisições concorrentes.
- Ruff, formatação e consistência do lock passaram; wheel e distribuição de fontes foram gerados.
- O wheel foi instalado em ambiente separado com dependências de produção e executado fora do repositório, incluindo fixtures empacotadas, modelo Windows, HTTP real, health e falhas controladas.
- O servidor real retornou A → B → C, 2,9 km e aproximadamente 16,06845 minutos para a requisição acima, usando `segment-model-v1-47ad884548f0f255`. Dez requisições locais tiveram mediana de aproximadamente 23,4 ms na validação da instalação editável; isso é uma medição de demonstração, sem SLA.

O cenário de tráfego muda a rota em relação ao comando com custos físicos, que retorna A → D → E → C. Esses custos físicos ignoram o tráfego configurado; não são uma comparação de precisão com o tempo previsto. Os testes também verificam que tráfego baixo favorece o caminho mais longo em quilômetros.

Os arquivos gerados não entram nos pacotes nem no Git. As suítes Maven não foram repetidas porque esta etapa altera somente Python e documentação.

## Próxima etapa

Após integrar esta branch, seguir com `feature/delivery-route-integration`: porta Java `RouteOptimizer`, cliente HTTP, validação da resposta e persistência do plano por entrega. Os testes devem cobrir timeout, indisponibilidade, resposta inválida e resultado obsoleto por concorrência, preservando o estado e o plano anterior. A aplicação Java ainda não chama Python; Docker e frontend pertencem a etapas posteriores do [roadmap](roadmap.md).
