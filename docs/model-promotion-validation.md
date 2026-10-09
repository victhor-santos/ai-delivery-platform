# Validação da promoção de modelos

Esta etapa define como um modelo treinado com [observações de entregas](segment-observation-training.md) pode substituir o modelo sintético servido pelo Route Intelligence. A promoção exige uma coleta simulada que cubra o grafo inteiro e uma decisão registrada, que só aprova quando contrato, compatibilidade, cobertura e métricas passam. Tudo continua simulado: aprovar um modelo não indica qualidade em ruas ou trânsito reais.

## Fluxo

| Passo | Comando | Resultado |
| --- | --- | --- |
| Coleta simulada | `training.simulate_observations` | Exports `delivery-segment-observation-v1` com as previsões do modelo de produção |
| Preparação | `training.prepare_observations` | Partições por entrega e tempo, com manifesto |
| Treino | `training.train_observations` | Bundle observacional selecionado na validação |
| Avaliação reservada | `training.evaluate_observation_model` | Relatório do teste, sem novo ajuste |
| Promoção | `training.validate_promotion` | Diretório novo com `promotion-report.json` e, se aprovado, o bundle |

## Coleta simulada

O Delivery só registra travessias das rotas que o sistema escolhe, então os exports reais da demonstração não cobrem o grafo nem os horários. `training.simulate_observations` gera entregas que percorrem caminhos aleatórios de 1 a 4 trechos conectados, com partidas espalhadas por todos os horários de cada dia e tráfego sorteado por trecho. Os tempos seguem o mesmo processo sintético do dataset original (fatores de tráfego, pico, madrugada, fim de semana e ruído), medido no horário real de entrada em cada trecho. As features continuam descrevendo a partida planejada, como no Delivery.

As previsões gravadas em cada linha vêm do modelo de produção informado, com sua `model_version`. Assim, a comparação no gate usa o modelo que está em produção, e não uma mistura de versões históricas.

O padrão é 42 dias (28 de treino, 7 de validação e 7 de teste) com 96 entregas por dia. Isso gera cerca de 10 mil observações, com o mesmo resultado para a mesma seed. `--high-traffic-shift` multiplica os tempos com tráfego pesado e simula uma mudança que o modelo de produção não conhece. Sem esse parâmetro, os dados seguem o mesmo processo usado para treinar o modelo atual.

A coleta exige um modelo de produção sintético. Exports gerados enquanto um modelo promovido estiver no ar trazem `prediction_data_origin=simulated`, que o pipeline offline ainda não aceita (veja os limites).

## Gate

`training.validate_promotion` não confia em relatórios salvos. Ele carrega os dois bundles, avalia o candidato no teste reservado e calcula as previsões do modelo de produção nas mesmas linhas. Cada gate entra no relatório com resultado e detalhe:

| Gate | Aprova quando |
| --- | --- |
| `graph_compatibility` | Candidato, produção e grafo informado têm a mesma versão e o mesmo checksum |
| `feature_contract` | Os dois modelos usam `segment-features-v1`, com as mesmas colunas e ordem |
| `runtime_predictions` | O candidato prevê tempo positivo e finito para todas as combinações de trecho, tráfego, hora e dia da semana que a API pode pedir |
| `test_rows` | O teste tem pelo menos 500 observações |
| `segment_coverage` | Cada trecho do grafo tem pelo menos 20 observações no teste |
| `traffic_coverage` | Cada nível de tráfego tem pelo menos 50 observações no teste |
| `time_coverage` | O teste cobre as 24 horas e os 7 dias da semana |
| `valid_predictions` | O candidato não produz previsões inválidas no teste |
| `improves_production` | O MAE do candidato é pelo menos 5% menor que o da produção |
| `beats_physical_reference` | O MAE do candidato é menor que o da referência distância/velocidade |
| `no_slice_regression` | Em cada tipo de via e nível de tráfego com pelo menos 20 linhas, o candidato não é mais de 5% pior que a produção |

Ambiente de execução, hashes e proveniência continuam conferidos pelo carregador dos bundles. A decisão é `approved` somente se todos os gates passarem. O diretório de saída precisa ser novo: ele sempre recebe `promotion-report.json`, mas só uma decisão aprovada copia os três arquivos do bundle. O comando termina com código 1 em caso de recusa e com 2 em erros de entrada, preservando saídas existentes.

## Servir o modelo promovido

O carregador da API aceita o contrato `delivery-observation-model-artifact-v1` somente com `promotion-report.json` aprovado no mesmo diretório. O relatório precisa corresponder ao bundle: checksum do `metadata.json`, `model_version`, checksums do pipeline e do relatório de validação, versão e checksum do grafo. Um bundle sem promoção, com decisão recusada ou com qualquer divergência deixa o health em `503 DOWN`, como já acontece com bundles incompatíveis. O bundle sintético continua carregando sem relatório.

Com o modelo promovido, `POST /api/routes/fastest` responde `data_origin: simulated`. Delivery aceita `synthetic` e `simulated` nos planos de rota e nos snapshots das travessias, e a interface web explica as duas origens. A promoção não altera o Compose: para servir o modelo, aponte `ROUTE_INTELLIGENCE_MODEL_DIR` para o diretório aprovado e recrie o container do Python.

## Executar

No Linux, com a imagem da API construída pelo [script de preparação](route-intelligence-compose.md) e o bundle de produção em `artifacts/segment-model-v1`:

```bash
service=services/route-intelligence-service
work=$service/artifacts/promotion-demo
mkdir -p "$work"
run() {
  docker run --rm --user "$(id -u):$(id -g)" \
    --mount "type=bind,source=$PWD/$service/artifacts/segment-model-v1,target=/production,readonly" \
    --mount "type=bind,source=$PWD/$service/app/routing/data/synthetic-city-v1.json,target=/graph.json,readonly" \
    --mount "type=bind,source=$PWD/$work,target=/work" \
    --entrypoint python delivery-order-system/route-intelligence-service:local -m "$@"
}

run training.simulate_observations --graph /graph.json --production-model /production \
  --output /work/observations.csv --high-traffic-shift 1.6
run training.prepare_observations --input /work/observations.csv \
  --start-at 2026-08-03T03:00:00Z --train-end 2026-08-31T03:00:00Z \
  --validation-end 2026-09-07T03:00:00Z --test-end 2026-09-14T03:00:00Z --output /work/dataset
run training.train_observations --dataset /work/dataset --graph /graph.json --output /work/candidate
run training.evaluate_observation_model --dataset /work/dataset --graph /graph.json \
  --artifact /work/candidate --output /work/test-report.json
run training.validate_promotion --candidate /work/candidate --production-model /production \
  --dataset /work/dataset --graph /graph.json --output /work/promotion

ROUTE_INTELLIGENCE_MODEL_DIR=./$work/promotion docker compose --profile demo up -d --wait route-intelligence-service
```

A coleta imprime os limites das partições (`split_plan`) usados na preparação. Para voltar ao modelo sintético, recrie o container sem a variável. Dados e artefatos ficam fora do Git.

## Limites

- Os gates usam limites fixos, definidos para o grafo de demonstração. Eles medem tempos por trecho no teste reservado, não rotas completas nem o custo de uma entrega inteira.
- A coleta explora o grafo de propósito. O Delivery real continua registrando só as rotas escolhidas.
- Retreinar a partir de exports gerados com um modelo promovido exige aceitar `prediction_data_origin=simulated` no pipeline offline. Essa automação de retreino fica no roadmap de MLOps.
- Não há registry, histórico de promoções nem rollback automático. Voltar ao modelo anterior é apontar o Compose para o bundle anterior.

## Validação

Em 08/10/2026, no Ubuntu:

- `ruff check`, `ruff format --check` e `pytest` passaram na suíte Python completa (em container `uv` com Python 3.12), incluindo os testes novos de coleta, gate, promoção e recusa de relatórios adulterados;
- `mvnw verify` passou no Delivery Service; `npm run lint`, `npm test` (12 arquivos, 71 testes) e `npm run build` passaram no frontend;
- na imagem da API, com o bundle de produção `segment-model-v1-5e298b8ffbad39e8`, a coleta padrão gerou 10.004 observações de 4.032 entregas, e a preparação reservou 1.677 observações para o teste;
- com `--high-traffic-shift 1.6`, o candidato (Random Forest) foi aprovado: MAE de 0,70 min contra 2,00 min da produção, sem regressão nos recortes. Sem a mudança, foi recusado em `improves_production`: 0,533 min contra 0,515 min, e o comando terminou com código 1;
- no runtime, o diretório recusado e o bundle sem promoção ficaram indisponíveis, e o diretório aprovado ficou pronto com origem `simulated`;
- com `ROUTE_INTELLIGENCE_MODEL_DIR` apontando para o diretório aprovado, `scripts/smoke-route-demo.ps1` passou no fluxo completo usando `segment-observation-model-v1-d6778a73771d59ed`. Depois de recriar o Python com o bundle sintético, o smoke passou de novo.
