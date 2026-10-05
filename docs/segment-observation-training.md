# Treinamento offline das observações do Delivery

`training.train_observations` consome o [dataset temporal de entregas](segment-observation-dataset.md), compara os candidatos existentes e salva o vencedor da validação em um bundle próprio. `training.evaluate_observation_model` carrega esse bundle e calcula métricas dos trechos no teste reservado, sem ajuste do modelo. As durações continuam explicitamente simuladas.

## Dados necessários

Prepare exports válidos distribuídos pelos três períodos, com separação por entrega e disponibilidade dos rótulos conforme o manifesto. Treino, validação e teste devem ter observações; a preparação permite conjuntos vazios para auditoria, mas o treinamento os rejeita antes do fit e da escrita do bundle.

O fixture Java versionado contém somente uma entrega com dois trechos. Ele demonstra importação, avaliação histórica e preparação; não permite esse treinamento porque não preenche os três períodos. Não copie linhas entre partições para contornar a restrição. O fluxo completo é exercitado nos testes com 72 entregas simuladas independentes, 24 em cada período; esses exemplos verificam o código, sem comprovar qualidade de previsão real.

O grafo deve ser fornecido explicitamente. O treinamento confere a versão, a existência e os atributos estáticos de cada segmento observado, incluindo registros excluídos: direção, distância, via e velocidade de referência. Segmentos não percorridos podem continuar no grafo. O checksum registrado identifica o arquivo lógico do grafo fornecido, mas o CSV não comprova a identidade do mapa histórico ou tempos das arestas não observadas.

## Executar

Na raiz, depois de preparar um dataset com dados nos três períodos:

```powershell
$service = 'services/route-intelligence-service'
$dataset = "$service/data/observations/prepared-deliveries"
$graph = "$service/app/routing/data/synthetic-city-v1.json"
$bundle = "$service/artifacts/observation-model-demo"
$report = "$service/artifacts/observation-model-test.json"

uv run --project $service --frozen python -m training.train_observations `
    --dataset $dataset --graph $graph --seed 42 --output $bundle

uv run --project $service --frozen python -m training.evaluate_observation_model `
    --dataset $dataset --graph $graph --artifact $bundle --output $report
```

Escolha nomes novos para bundle e relatório. Diretório de artefato ou arquivo de saída existentes são preservados; erros de dados, grafo ou argumentos retornam código 2 sem traceback. Uma escrita interrompida pode deixar diretório incompleto, que não deve ser consumido. Dataset e artefatos locais ficam fora do Git.

Para verificar o fluxo sem coletar dados adicionais:

```powershell
uv run --project services/route-intelligence-service --frozen pytest `
    services/route-intelligence-service/tests/test_observation_training.py `
    services/route-intelligence-service/tests/test_observation_model_evaluation.py
```

## Treino e seleção

`train_candidates` recebe somente treino e validação. Reutiliza referência física, Dummy mediano, regressão linear e Random Forest, com o mesmo pré-processamento e seed do pipeline sintético. As entradas continuam restritas às seis `FEATURE_COLUMNS`; IDs, tempos observados, timestamps de chegada e previsões históricas não entram na matriz.

A seleção usa MAE na validação, rejeita candidatos com previsões inválidas em treino ou validação e prefere simplicidade na margem predefinida de 0,01 minuto. O vencedor não é reajustado na validação. O teste fica reservado ao comando posterior e não influencia escolha, hiperparâmetros ou pré-processamento.

`validation-report.json` registra candidatos, parâmetros, métricas, tempos, seleção, identidade e checksum do dataset, origem, schemas, grafo, política temporal e contagens de entregas de treino/validação. Tempos medidos variam entre execuções. Como no pipeline anterior, não se promete identidade de bytes entre plataformas ou processos já utilizados.

## Contrato do bundle

Os três arquivos são `segment_travel_time_model.joblib`, `metadata.json` e `validation-report.json`. A serialização, conferência dos bytes e verificação de previsões após restauração são compartilhadas com o treinamento sintético.

| Campo | Valor ou significado |
| --- | --- |
| `artifact_schema_version` | `delivery-observation-model-artifact-v1` |
| `model_version` | `segment-observation-model-v1-` seguido de 16 caracteres do hash do pipeline |
| `data_origin` | `simulated` |
| `prediction_data_origin` | `synthetic`, origem das previsões históricas dos exports |
| `sample_schema_version` | `delivery-segment-observation-v1` |
| `dataset_manifest_version` | `delivery-observation-dataset-v1` |
| `dataset_id`, `dataset_manifest_sha256` | Identidade dos dados preparados e checksum do manifesto completo |
| `graph_version`, `graph_sha256` | Grafo explicitamente fornecido e conferido |
| `fit_partition`, `selection_partition` | `train`, `validation` |
| `environment`, hashes e tamanho | Compatibilidade de runtime/dependências e integridade |

`load_observation_model` aceita somente esse contrato para avaliação offline. O carregador `load_model` e o predictor usados na API continuam exigindo `segment-model-artifact-v1` com origem `synthetic`; rejeitam bundles observacionais antes da desserialização. Configurar o novo diretório na API não publica o modelo: sua prontidão falhará. O Compose não é alterado e o bundle atual não é substituído.

Ambos os contratos usam o mesmo leitor interno para verificar ambiente, hashes, tamanho, pipeline, contagem de features e correspondência do relatório com metadata. Origem, versão de relatório, seleção, seed, parâmetros e identidade são conferidos. Bundles observacionais também conferem schemas, origem da previsão histórica e grafo no relatório. Joblib deve carregar somente artefatos locais confiáveis; hashes comprovam integridade, não confiança.

## Avaliação reservada

`delivery-observation-test-report-v1` exige o mesmo dataset, manifesto e grafo do treinamento. A avaliação lê apenas os targets do teste e não chama fit. Compara nas mesmas observações:

- O modelo selecionado e restaurado do bundle.
- A referência física de distância/velocidade.
- As previsões históricas originais, com resultado agregado e por `model_version` histórico.

Cada comparação informa MAE, RMSE, R², quantidade e previsões inválidas, além de recortes por via, tráfego e distância. R² fica nulo para uma única linha ou targets constantes. O relatório registra `selection_used_test=false`, `model_refitted=false` e `historical_predictions_recomputed=false`; nenhuma previsão inválida é corrigida para parecer válida.

Não há avaliação da melhor rota ou custo de entrega completa: faltam tempos contrafactuais das arestas não percorridas, e um export pode ser parcial. Os grupos históricos podem representar viagens diferentes, e as observações dependem das rotas escolhidas pelo sistema. Mesmo com três conjuntos não vazios, cobertura e quantidade exigem avaliação antes de qualquer uso operacional.

## Próximos passos

Coletar observações simuladas suficientes e representativas, inspecionar cobertura e avaliar estabilidade ao longo do tempo. Promoção de artefatos observacionais requer uma etapa própria de contrato/runtime e decisão baseada em validação; os comandos desta etapa não promovem modelos. Coleta real, mapas/tráfego reais e interface web continuam no roadmap.

## Validação

Em 05/10/2026, a suíte Python completa passou com 542 testes, incluindo 96 casos novos nesta feature. Ruff e formatação passaram nos 77 arquivos Python. O build gerou sdist e wheel com os três módulos novos de serialização, treinamento e avaliação. Links locais e diffs foram conferidos.

As CLIs de treino e avaliação foram executadas em processos separados, usando o dataset válido de 72 entregas simuladas dos testes. A regressão linear foi selecionada na validação; o relatório avaliou 24 observações reservadas e registrou zero previsões inválidas. O carregador online rejeitou o bundle resultante. Esses resultados verificam o fluxo implementado e não medem precisão em entregas reais.

Os testes comprovaram que mudar apenas rótulos do teste, regenerando seu dataset e manifesto válidos, não altera o candidato ou os bytes do pipeline selecionado. Também verificaram avaliação sem fit, origem/schema/grafo adulterados, equivalência após serialização, incompatibilidade de runtime, partições vazias e proteção de saídas existentes. A revisão independente identificou overflow em features extremas; falhas numéricas no fit e na avaliação agora retornam erro controlado, com testes de regressão e sem descarte ou correção dos valores.

As suítes Java não foram repetidas porque esta feature altera somente o pipeline Python e documentação. Contratos HTTP, Compose, bancos e artefatos usados na demonstração permanecem preservados.
