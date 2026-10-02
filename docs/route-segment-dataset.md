# Dataset sintético de tempo por trecho

O gerador offline transforma o [grafo sintético](road-graph.md) em observações contrafactuais de tempo por trecho. Ele ainda não treina modelos nem faz previsões reais. Cada cenário representa um instante de decisão, com contexto por trecho e uma travessia independente em cada aresta; as dez observações não são uma viagem sequencial de um entregador.

## Gerar os arquivos

Prepare o ambiente pelo [guia Python](route-intelligence-foundation.md). Da raiz, sem Java, servidor ou Docker:

```powershell
uv sync --project .\services\route-intelligence-service --locked
uv run --project .\services\route-intelligence-service --locked python -m training.generate_dataset --output .\services\route-intelligence-service\data\synthetic\segment-dataset-v1
```

O diretório de saída precisa ser novo. Se a validação já gerou `segment-dataset-v1` localmente, escolha outro nome para repetir a execução; o comando nunca sobrescreve um dataset existente. Os arquivos completos ficam ignorados pelo Git e fora do wheel e da distribuição de fontes. Três exemplos pequenos são versionados em `tests/fixtures/segment-samples-v1.json`.

| Arquivo | Conteúdo |
| --- | --- |
| `samples.csv` | Todas as observações geradas, incluindo eventuais exclusões |
| `train.csv` | Cenários elegíveis para ajuste do modelo |
| `validation.csv` | Cenários elegíveis para seleção do modelo |
| `test.csv` | Cenários reservados para avaliação final |
| `excluded.csv` | Cenários excluídos por tempo, disponibilidade ou fronteira; mantém o cabeçalho mesmo vazio |
| `manifest.json` | Identidade, configurações, schemas, proveniência, partições, auditoria, estatísticas e checksums |

O manifesto é escrito por último. Uma falha de escrita pode deixar um diretório parcial; ele não deve ser consumido sem manifesto válido e conferência dos checksums. O treinamento futuro verificará integridade e compatibilidade antes de carregar os dados.

Para uma amostra menor ou outro início:

```powershell
uv run --project .\services\route-intelligence-service --locked python -m training.generate_dataset --output .\services\route-intelligence-service\data\synthetic\small-seed42 --seed 42 --start-at '2026-08-03T00:00:00-03:00' --train-days 1 --validation-days 1 --test-days 1 --interval-minutes 360
```

Esse exemplo gera 120 observações, 40 em cada partição. `--graph caminho.json` substitui o fixture empacotado por outro grafo compatível. Configuração inválida, grafo inválido, partições vazias e saída existente retornam código 2 com mensagem no stderr.

## Configuração e limites

| Opção | Padrão | Regra |
| --- | --- | --- |
| `--seed` | 42 | Inteiro entre 0 e 4294967295 |
| `--start-at` | `2026-08-03T00:00:00-03:00` | Primeiro instante de decisão, com offset obrigatório |
| `--train-days` | 28 | De 1 a 365 dias |
| `--validation-days` | 7 | De 1 a 365 dias |
| `--test-days` | 7 | De 1 a 365 dias; soma dos três períodos até 365 dias |
| `--interval-minutes` | 60 | Entre 15 e 1440, dividindo 1440 exatamente |
| `--noise-min`, `--noise-max` | 0,85 e 1,15 | Números finitos em (0, 2], com mínimo menor ou igual ao máximo |
| `--recording-delay-seconds` | 30 | Entre 0 e 86400, depois da saída do trecho |
| `--label-delay-seconds` | 30 | Entre 0 e 86400, depois do registro da travessia |

O gerador aceita de 1 a 100000 observações, com pelo menos um trecho no grafo. A exportação exige cenários elegíveis nas três partições. Dias são durações de 24 horas em UTC; os atributos locais vêm do fuso do grafo. Intervalos maiores podem reduzir cobertura de horários ou de categorias.

## Schema e disponibilidade

`app/ml/features.py` define `segment-features-v1` e a lista permitida de seis entradas:

```text
distance_km, road_type, reference_speed_kmh, traffic_level, hour, day_of_week
```

Distância e velocidade são positivas e finitas. Tipo de via aceita `residential`, `primary` ou `highway`; tráfego aceita `low`, `medium` ou `high`. Horário vai de 0 a 23, e dia da semana vai de 0 a 6, com segunda-feira igual a zero. Strings numéricas, booleanos e campos extras são rejeitados no schema de features.

`extract_features` seleciona explicitamente essas seis colunas antes de validar. IDs, target, resultado e variáveis auxiliares do gerador não entram nas features. Horário e dia são derivados da partida planejada no fuso `America/Sao_Paulo`, usando diretamente a base do pacote `tzdata` travado, inclusive no Windows. A biblioteca de fuso do sistema não altera essa escolha.

`training/schema.py` define `segment-sample-v1`, com 28 colunas: as seis features, versões/proveniência, IDs do cenário e travessia, segmento e direção, versão do grafo, timestamps e `actual_travel_time_minutes`. Os schemas JSON completos e a ordem das colunas estão no manifesto. CSV é texto; os tipos declarados deverão ser respeitados ao ler os dados.

Todos os timestamps têm fuso e são normalizados para UTC. `prediction_at` é o instante de decisão simulado, não uma previsão já produzida por ML. O esquema valida:

- `traffic_observed_at ≤ traffic_available_at ≤ context_as_of ≤ prediction_at`.
- `traffic_available_at ≤ features_available_at ≤ prediction_at`.
- `prediction_at ≤ planned_departure_at ≤ entered_at < exited_at ≤ recorded_at ≤ label_available_at`.
- Horário e dia coerentes com a partida planejada; target coerente com a duração da travessia.

No cenário atual, o tráfego é observado 60 segundos antes da decisão e disponibilizado 30 segundos antes. A partida é planejada para 60 segundos depois da decisão. Cada travessia independente começa nessa partida, e o target é calculado após quantizar a duração em microssegundos:

```text
actual_travel_time_minutes = (exited_at - entered_at).total_seconds() / 60
```

Assim, o tempo observado e sua disponibilidade são preservados separadamente. A existência do rótulo no arquivo offline não significa que ele estivesse disponível no instante da decisão.

## Hipóteses da simulação

```text
tempo_base = 60 * distance_km / reference_speed_kmh
duracao_simulada = tempo_base * fator_trafego * fator_horario_dia * ruido
```

O tráfego é sorteado uniformemente entre as três categorias, independentemente por trecho. Os fatores são 1,0 para `low`, 1,5 para `medium` e 2,2 para `high`. O perfil de horário/dia aplica, nesta ordem:

1. Horas de 0 a 5: fator 0,8.
2. Dias úteis nas horas 7, 8, 9, 17, 18 e 19: fator 1,35.
3. Fim de semana fora da madrugada: fator 0,95.
4. Demais casos: fator 1,0.

O ruído é uniforme e positivo no intervalo configurado, com sorteio independente por travessia. Um `Random` local recebe a seed; o estado global não é alterado. Os segmentos são ordenados por identificador antes de sortear, e o checksum do grafo usa seu schema normalizado com nós e trechos ordenados. Mudar apenas a ordem do JSON não altera o dataset.

Velocidades vêm do grafo, e o tipo de via influencia essa referência; não é calculada uma velocidade a partir do próprio target. Os fatores acima são hipóteses de demonstração, não estatísticas de entregas reais. Métricas futuras só poderão ser interpretadas dentro desse domínio sintético.

## Partições temporais por cenário

Os períodos são consecutivos, com início inclusivo e fim exclusivo, usando `prediction_at`. Cada `scenario_id` pertence inteiro a uma partição. Um grupo que atravessa a fronteira ou fica fora do período é excluído inteiro. Mesmo quando todas as decisões estão no mesmo período, o grupo é excluído se algum `label_available_at` for posterior ao fim da partição. Um rótulo disponível exatamente no corte é aceito.

Isso permite ajustar o modelo com dados disponíveis até `train_end`, selecionar usando validação disponível até `validation_end` e avaliar decisões posteriores no teste. Corte pela partida futura poderia colocar uma decisão anterior ao corte no conjunto seguinte; por isso, ela não é a base da divisão.

O manifesto registra cenários excluídos e motivos: `outside_period`, `crosses_time_boundary` ou `label_unavailable_at_cutoff`. IDs de travessia duplicados e versões de grafo misturadas são rejeitados. A auditoria conta assinaturas idênticas de features mais target presentes em várias partições. Esse contador ajuda a revisar repetição; não remove linhas automaticamente nem comprova sozinho ausência ou presença de vazamento. Repetir features em vias conhecidas é esperado. Ruído constante pode produzir repetição exata, que o manifesto informa.

## Manifesto e reprodutibilidade

O manifesto registra versões do gerador, schemas e grafo; checksum do grafo normalizado; seed, início, períodos, fatores e ruído; versões do Python, serviço, Pydantic e tzdata; counts, cortes, exclusões, estatísticas e checksums SHA-256 dos cinco CSVs. `dataset_id` deriva do grafo, configuração e metadados dos arquivos. Não há timestamp do relógio atual ou caminho de saída na identidade.

Os arquivos usam UTF-8 e quebra de linha LF. Mesmos grafo, configuração e ambiente travado produzem os mesmos CSVs e manifesto. Versões de runtime e dependências constam para investigar mudanças; não se promete identidade entre ambientes arbitrariamente diferentes. Ao alterar a lógica de geração ou os schemas, suas versões precisam ser atualizadas.

## Validação executada

Verificado em 2026-10-02, com Python 3.12.14, uv 0.12.11 e tzdata 2026.4:

| Partição padrão | Cenários | Observações | Intervalo de decisões, UTC |
| --- | --- | --- | --- |
| Treino | 672 | 6720 | `[2026-08-03T03:00Z, 2026-08-31T03:00Z)` |
| Validação | 168 | 1680 | `[2026-08-31T03:00Z, 2026-09-07T03:00Z)` |
| Teste | 168 | 1680 | `[2026-09-07T03:00Z, 2026-09-14T03:00Z)` |
| Excluídos | 0 | 0 | Nenhuma exclusão na configuração padrão |

As 10080 observações cobrem os 24 horários, os sete dias e as três categorias de tráfego e via. Tempos observados ficaram entre aproximadamente 0,8181 e 27,3038 minutos, com média de 5,7637 minutos. A auditoria padrão informou zero assinaturas idênticas de features e target entre partições.

- 205 testes passaram: suites anteriores, features permitidas, fuso, timestamps, targets, seeds, configuração, tendências, cortes, disponibilidade tardia, cenários inteiros, duplicatas, exportação e comandos.
- Duas gerações padrão em diretórios diferentes produziram os seis arquivos idênticos byte a byte. SHA-256 de `samples.csv`: `5b1937a4356f477ed1a7f0df48bc638893af87cfb6997be00a099482cad2cf4b`.
- Ruff, formatação, lock, build e revisão do diff passaram. O wheel foi instalado separadamente e gerou arquivos fora do repositório, preservando saída existente, roteamento e contrato de health.
- A distribuição de fontes foi corrigida para não incluir os CSVs gerados. O build contém código, recursos e exemplos pequenos de teste, conforme a seleção do projeto.

```powershell
uv run --project .\services\route-intelligence-service --locked python -m pytest .\services\route-intelligence-service\tests
uv run --project .\services\route-intelligence-service --locked python -m ruff check .\services\route-intelligence-service
uv run --project .\services\route-intelligence-service --locked python -m ruff format --check .\services\route-intelligence-service
uv lock --project .\services\route-intelligence-service --check
uv build --project .\services\route-intelligence-service
```

Esta etapa não altera Java, bancos, Gateway ou Compose; as suítes Maven não foram repetidas. A próxima feature treinará Dummy, regressão linear e Random Forest, comparará com a referência física e avaliará o modelo escolhido no teste reservado, conforme o [plano de dados e avaliação](route-intelligence-data.md).
