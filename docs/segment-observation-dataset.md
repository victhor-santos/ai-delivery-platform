# Dataset temporal das observações do Delivery

`training.prepare_observations` prepara os [CSVs de travessias simuladas](delivery-segment-observations.md) em partições temporais por entrega. O resultado tem contrato próprio: `delivery-observation-dataset-v1`, preservando a origem `simulated`, as previsões históricas `synthetic` e as 34 colunas do export Java. O comando funciona offline.

## Executar

Na raiz do repositório, com Python e `uv` preparados:

```powershell
uv run --project services/route-intelligence-service --frozen python -m training.prepare_observations `
    --input services/route-intelligence-service/tests/fixtures/delivery-observations-v1.csv `
    --start-at '2026-10-03T00:00:00Z' `
    --train-end '2026-10-04T00:00:00Z' `
    --validation-end '2026-10-05T00:00:00Z' `
    --test-end '2026-10-06T00:00:00Z' `
    --output services/route-intelligence-service/data/observations/prepared-demo
```

O exemplo usa o fixture capturado da API Java. Seus dois trechos pertencem à mesma entrega: ficam no treino, e validação/teste ficam vazios. O manifesto informa essa insuficiência; não replica linhas para preencher partições. Os tempos do fixture resultam da simulação de chamadas HTTP e não representam deslocamentos reais.

Para usar outros exports, informe vários caminhos depois de `--input` e escolha fronteiras compatíveis com seus períodos de previsão. As quatro fronteiras são obrigatórias, devem ter fuso explícito e ser estritamente crescentes. O comando não usa o relógio atual ou períodos padrão.

Um diretório de saída existente causa erro e é preservado; escolha outro nome. Falhas retornam código 2 sem traceback. Saídas em `data/observations` já são ignoradas pelo Git. Se uma falha de escrita ou limite deixar um diretório incompleto, ele não terá manifesto válido e não poderá ser carregado.

## Política temporal

`SplitPlan`, compartilhado com o gerador sintético, define os intervalos:

| Partição | Instante de previsão |
| --- | --- |
| Treino | `start_at <= prediction_at < train_end` |
| Validação | `train_end <= prediction_at < validation_end` |
| Teste | `validation_end <= prediction_at < test_end` |

Cada `delivery_id` fica inteiro em uma partição. O fim do intervalo é exclusivo para a previsão e inclusivo para a disponibilidade do rótulo. Se qualquer `label_available_at` da entrega ultrapassar o fim de sua partição, todas as suas linhas ficam em `excluded.csv`, com motivo `label_unavailable_at_cutoff`. Previsões fora do período recebem `outside_period`. Rótulos antigos recebidos depois do corte não entram retroativamente no treino.

O carregador original valida todas as linhas antes da divisão: UUIDs, disponibilidade, duração observada, snapshots, sequência e consistência do plano. Duplicatas idênticas são deduplicadas; conflitos causam erro, mesmo fora do período. A preparação exige uma única versão de grafo e confere que cada `segment_id` mantém direção, distância, tipo de via e velocidade de referência entre entregas. Diferentes modelos históricos são permitidos e contados por partição.

Partições vazias são permitidas para auditar a coleta atual. Um dataset sem nenhuma observação é rejeitado. `all_partitions_nonempty` apenas informa a presença de linhas, sem garantir quantidade suficiente, cobertura ou qualidade estatística para treinamento.

## Arquivos e proveniência

| Arquivo | Conteúdo |
| --- | --- |
| `samples.csv` | Todas as observações únicas, inclusive as excluídas |
| `train.csv`, `validation.csv`, `test.csv` | Observações elegíveis de cada período |
| `excluded.csv` | Entregas excluídas pela política temporal |
| `manifest.json` | Identidade, schemas, política, proveniência, contagens e hashes |

Os CSVs mantêm os snapshots originais com timestamps normalizados em UTC e ordem determinística por entrega/sequência. O manifesto registra nomes e SHA-256 das fontes, duplicatas removidas, versão do grafo, modelos históricos, fronteiras e motivos de exclusão por entrega. O CSV não fornece checksum do grafo; a preparação não inventa esse dado nem reconstrói mapas.

`observation_set_id` identifica as observações únicas normalizadas, antes dos cortes. `dataset_id` identifica esse conjunto, a versão do contrato, grafo, schema, política temporal e metadados dos cinco CSVs canônicos. Ordem de leitura, duplicatas idênticas e nomes das fontes não alteram essa identidade. A proveniência das fontes fica no manifesto completo, cujo checksum é exposto pelo carregador; hashes locais verificam integridade, não autenticidade da coleta.

O schema declara as mesmas seis entradas de `SegmentFeatures`: distância, tipo de via, velocidade de referência, tráfego, horário e dia da semana. `actual_travel_time_minutes` é o target. Previsão histórica, IDs e timestamps permanecem para auditoria e separação; não fazem parte da lista de features.

Os limites de importação continuam em 100 arquivos, 100 mil linhas somadas antes da deduplicação e 50 MiB por arquivo. Cada CSV preparado também fica limitado a 50 MiB; múltiplas fontes podem ultrapassar esse tamanho agregado e exigir menos entregas por export. O manifesto tem limite de 16 MiB.

## Carregamento validado

`training.observation_dataset.load_observation_dataset(Path(...))` retorna `LoadedObservationDataset`, com `manifest`, `manifest_sha256`, `dataset_id` e `split` contendo treino, validação, teste e exclusões. Antes de retornar:

1. Valida o contrato e a identidade do manifesto, exigindo exatamente os cinco nomes de CSV esperados.
2. Importa cada CSV separadamente e confere tamanho, SHA-256, contagem e ausência de duplicatas. Assim, as partições não consomem novamente o limite total de linhas das fontes.
3. Reconstrói a divisão a partir de `samples.csv` e compara as partições, schemas, política, proveniência e resumos. Mover registros entre partições causa erro mesmo depois de recalcular seus hashes declarados.

O gerador e carregador sintéticos continuam usando `segment-sample-v1`. `training.train` não aceita esse novo manifesto. A [etapa de treino observacional](segment-observation-training.md) acrescenta comandos próprios que consomem o dataset preparado e preservam a proveniência do artefato. A preparação em si não treina modelos; partições vazias continuam válidas para auditoria, mas impedem o novo treinamento.

Exports podem conter apenas parte de uma rota e não informam os tempos das arestas não percorridas. Por isso, não permitem a avaliação contrafactual da melhor rota usada nos cenários sintéticos. Coleta real, trânsito real e interface web continuam como evoluções posteriores.

## Validação

Em 05/10/2026, a suíte Python completa passou com 446 testes, incluindo 62 casos novos de partições, disponibilidade, fronteiras, proveniência, integridade, limites e CLI. Os testes sintéticos também passaram após o compartilhamento dos métodos de `SplitPlan` e do container genérico de partições. Ruff e formatação passaram nos 71 arquivos Python. O build gerou sdist e wheel, com os dois módulos novos presentes no pacote; links locais e diff foram conferidos.

A CLI foi executada sobre o CSV capturado da API Java e o resultado foi carregado pelo novo loader: duas observações no treino, nenhuma entrega compartilhada entre partições e validação/teste vazios explicitamente auditados. A revisão independente confirmou esse carregamento e a rejeição de um resumo adulterado. As suítes Java não foram repetidas, pois esta etapa altera somente ferramentas offline Python e documentação; serviços, Compose e bancos não sofreram mudanças.
