# Avaliação dos CSVs de observações

`training.evaluate_observations` importa os CSVs de [travessias do Delivery](delivery-segment-observations.md) e compara cada previsão armazenada com a duração observada. O comando funciona offline, sem banco, serviços HTTP ou artefato de modelo. As métricas usam a previsão do plano original; não há nova inferência ou treinamento.

## Executar com o exemplo versionado

Na raiz do repositório, com Python e `uv` preparados:

```powershell
New-Item -ItemType Directory -Force services/route-intelligence-service/artifacts | Out-Null
uv run --project services/route-intelligence-service --frozen python -m training.evaluate_observations `
    --input services/route-intelligence-service/tests/fixtures/delivery-observations-v1.csv `
    --available-at-cutoff '2026-10-04T00:00:00Z' `
    --output services/route-intelligence-service/artifacts/observation-report-demo.json
```

O fixture contém dois trechos simulados, exportados pela API Java do ambiente isolado de smoke. Não contém endereços nem identificadores de pessoas. Seus tempos curtos resultam da simulação de chamadas HTTP; não representam deslocamentos. As métricas desse exemplo não permitem concluir nada sobre a precisão em entregas reais.

O relatório é gravado como JSON e impresso no terminal. Se a saída já existir, o comando falha sem sobrescrevê-la; escolha outro nome para uma nova execução. Erros de dados ou argumentos retornam código 2, sem traceback.

## Exportar outras entregas

Com o Gateway e uma entrega com observações disponíveis:

```powershell
$baseUrl = 'http://localhost:8080'
$deliveryId = 'ID-DA-ENTREGA'
$cutoff = [DateTimeOffset]::UtcNow.ToString('o')
$encodedCutoff = [uri]::EscapeDataString($cutoff)
New-Item -ItemType Directory -Force services/route-intelligence-service/data/observations | Out-Null
New-Item -ItemType Directory -Force services/route-intelligence-service/artifacts | Out-Null
$csv = "services/route-intelligence-service/data/observations/$deliveryId.csv"
Invoke-WebRequest "$baseUrl/api/deliveries/$deliveryId/segments/export?availableAtCutoff=$encodedCutoff" -OutFile $csv
uv run --project services/route-intelligence-service --frozen python -m training.evaluate_observations `
    --input $csv --available-at-cutoff $cutoff `
    --output services/route-intelligence-service/artifacts/observation-report.json
```

Para combinar arquivos, informe vários caminhos depois de `--input`. Exports repetidos da mesma travessia são deduplicados quando todos os valores normalizados são iguais. Um mesmo ID com outro conteúdo, ou IDs diferentes na mesma sequência da entrega, causam erro. CSVs locais em `data/observations` e relatórios em `artifacts` ficam fora do Git.

## Validação e corte temporal

O leitor exige as 34 colunas de `delivery-segment-observation-v1`, na ordem do contrato Java. Aceita UTF-8 com ou sem BOM e finais de linha LF/CRLF. Cabeçalho sem dados é válido; arquivo sem cabeçalho não é.

São conferidos:

- UUIDs, sequência, versões, origem `simulated`, previsão `synthetic` e vocabulário das features.
- Custos e duração positivos e finitos; duração correspondente à diferença entre saída e entrada.
- Timestamps com fuso e ordem de disponibilidade de tráfego, features, contexto, previsão e rótulo.
- `recorded_at = label_available_at`, conforme o contrato v1, e registro de entrada entre entrada e disponibilidade do rótulo.
- Horário e dia das features relativos à partida planejada, que pode diferir do horário efetivo.
- Mesmo plano e versões dentro de uma entrega, cronologia sem sobreposição e ligação entre trechos consecutivos presentes no export.

Linhas incompletas, malformadas ou conflitantes causam erro; não são descartadas silenciosamente. Todas as linhas são validadas antes do corte, inclusive as que seriam excluídas. Os limites são 100 arquivos, 50 MiB por arquivo e 100 mil linhas no total, contando duplicatas.

`--available-at-cutoff` é obrigatório e inclusivo. Somente observações cujo `label_available_at` seja menor ou igual ao corte entram nas métricas. A ordem temporal validada garante que suas features e previsões também estavam disponíveis até esse instante. Uma travessia antiga cuja saída foi aceita depois do corte fica excluída. O comando offline usa o corte informado, sem consultar o relógio atual.

## Relatório

`delivery-observation-report-v1` registra:

| Campo | Significado |
| --- | --- |
| `observation_set_id` | SHA-256 das observações únicas normalizadas e ordenadas, antes do corte |
| `sources` | Nome, SHA-256 dos bytes, tamanho e quantidade de linhas de cada arquivo |
| `input_rows`, `duplicate_rows`, `unique_rows` | Contagem original, repetições idênticas e observações únicas |
| `included_rows`, `excluded_after_cutoff` | Observações usadas e excluídas por disponibilidade |
| `included_deliveries` | Entregas representadas no corte, sem presumir rotas completas |
| `groups` | Métricas por `model_version` e `graph_version` |
| `model_refitted`, `predictions_recomputed` | Sempre `false`; descrevem o uso das previsões armazenadas |

Cada grupo contém MAE e RMSE em minutos, R² e quantidade de linhas. Há recortes por tipo de via, tráfego e faixa de distância, reutilizando as funções de métricas existentes. R² fica nulo para uma única observação ou tempos observados constantes. Sem observações elegíveis, `groups` fica vazio.

A ordem dos arquivos ou linhas não altera a identidade do conjunto normalizado nem as métricas. Os checksums das fontes identificam os bytes recebidos; renomear ou reformatar um CSV altera essa proveniência, mesmo quando os dados normalizados são iguais.

## Limites e próximos passos

O export pode representar uma rota parcial. O relatório não infere métricas de entrega completa ou de escolha da melhor rota. Modelos diferentes podem ter sido usados em viagens distintas; seus grupos não constituem uma comparação controlada.

O comando não cria partições de treino/validação/teste, não mistura eventos ao dataset sintético anterior e não substitui `training.evaluate`, que avalia o artefato no teste reservado. Um comando separado de [preparação das observações](segment-observation-dataset.md) define partições temporais por entrega e um manifesto próprio. Retreinamento ainda exige integrar esse contrato à seleção de modelos e à avaliação de trechos, com dados suficientes. Coleta real de GPS/trânsito e interface web continuam no roadmap.

## Validação

Em 05/10/2026, a suíte Python completa passou com 384 testes, incluindo 53 testes novos de importação, disponibilidade, duplicação, métricas, fusos, limites e CLI. Ruff e formatação passaram nos 68 arquivos Python. O build gerou sdist e wheel, com os dois módulos novos presentes no pacote. O CSV capturado da API Java foi importado e avaliado com duas observações simuladas e nenhuma previsão inválida. Os links locais da documentação e o diff foram conferidos.

As suítes Java não foram repetidas nesta etapa, que altera somente ferramentas offline Python, fixture, documentação e exclusões do Git. O ambiente isolado usado para consultar o export é encerrado preservando os volumes; os bancos de desenvolvimento permanecem em execução.
