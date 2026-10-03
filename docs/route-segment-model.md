# Modelo de tempo por trecho

O treinamento offline compara os quatro candidatos previstos no [plano de dados](route-intelligence-data.md), seleciona pela validação e salva um pipeline. A avaliação independente carrega esse artefato e usa o teste reservado. `app/ml/predictor.py` já prevê em lote, mas a API continua expondo apenas `/health`; nenhum modelo é treinado ou carregado no startup nesta etapa.

## Executar

Prepare o [ambiente Python](route-intelligence-foundation.md) e gere o [dataset](route-segment-dataset.md). Da raiz:

```powershell
uv sync --project .\services\route-intelligence-service --locked
uv run --project .\services\route-intelligence-service --locked python -m training.train --dataset .\services\route-intelligence-service\data\synthetic\segment-dataset-v1 --output .\services\route-intelligence-service\artifacts\segment-model-v1-windows --seed 42
uv run --project .\services\route-intelligence-service --locked python -m training.evaluate --dataset .\services\route-intelligence-service\data\synthetic\segment-dataset-v1 --artifact .\services\route-intelligence-service\artifacts\segment-model-v1-windows --output .\services\route-intelligence-service\artifacts\segment-model-v1-windows\test-report.json
```

O diretório do artefato e o arquivo do relatório precisam ser novos. Se a validação já os produziu, escolha outro nome; saídas existentes são preservadas. O treinamento aceita `--seed` inteiro de 0 a 4294967295. A avaliação aceita `--graph caminho.json`, `--origin` e `--destination`; os padrões são o fixture empacotado e A → C. O grafo precisa coincidir com o checksum original do dataset. Origem igual ao destino, nós ausentes, caminho inexistente e cenários sem todas as arestas são rejeitados.

Erros de configuração, integridade e compatibilidade retornam código 2 e mensagem no stderr. A exportação pode deixar uma saída parcial após falha de escrita; use somente bundles completos que passaram pelo carregador. `metadata.json` é escrito depois da comparação das previsões antes/depois da serialização.

## Leitura e features

`training/load_dataset.py` confere versão, lista exata de arquivos e colunas, identidade, tamanhos e SHA-256 dos cinco CSVs. Cada linha é validada como `SegmentSample`. A divisão original é recalculada a partir de `samples.csv`, usando decisão, grupos e disponibilidade de rótulos; partições alteradas são rejeitadas mesmo com checksums recalculados. Essa leitura de integridade não usa o teste para ajustar ou selecionar o modelo.

A matriz NumPy usa somente, nesta ordem:

```text
distance_km, road_type, reference_speed_kmh, traffic_level, hour, day_of_week
```

Não entram IDs, timestamps de resultado, target nem parâmetros do gerador. Valores ausentes, categorias fora do vocabulário e números inválidos são rejeitados. Não há imputação nesta versão. `StandardScaler` e `OneHotEncoder` são ajustados somente no treino, dentro de `Pipeline`/`ColumnTransformer`. Categorias válidas ausentes do treino são ignoradas pelo encoder; a entrada pública continua respeitando o vocabulário versionado. Veja [ColumnTransformer](https://scikit-learn.org/stable/modules/generated/sklearn.compose.ColumnTransformer.html) e [OneHotEncoder](https://scikit-learn.org/stable/modules/generated/sklearn.preprocessing.OneHotEncoder.html).

## Candidatos e seleção

| Candidato | Configuração inicial |
| --- | --- |
| Referência física | `60 * distance_km / reference_speed_kmh`; não aprende fatores do gerador |
| Dummy | Mediana dos targets de treino |
| Regressão linear | Features numéricas escaladas e categorias codificadas |
| Random Forest | 100 árvores, profundidade máxima 12, folha mínima 3, seed configurável e `n_jobs=1` |

Não há busca de hiperparâmetros nem reajuste em treino + validação. A função de treinamento recebe apenas treino e validação. O teste é avaliado depois de salvar o escolhido.

O critério é MAE na validação, exigindo previsões finitas e positivas no treino e na validação. Entre candidatos a até 0,01 minuto do melhor MAE, a ordem de simplicidade é referência física, Dummy, linear e floresta. Essa margem de 0,6 segundo é fixa antes da execução. Tempos de ajuste e inferência são registrados para revisão, sem influenciar automaticamente a escolha.

MAE e RMSE são registrados em minutos, além de R² e erros por via, tráfego e distância (`< 1 km`, `[1, 3) km`, `≥ 3 km`). R² negativo é preservado; quando não é definido, fica `null`. Previsões negativas/zero são contadas e suas métricas usam os valores originais. Se houver previsão não finita, as métricas agregadas ficam `null`, sem descartar silenciosamente essas linhas. Não há clipping de previsões.

## Bundle e predictor

| Arquivo | Conteúdo |
| --- | --- |
| `segment_travel_time_model.joblib` | Pipeline escolhido e ajustado somente no treino |
| `metadata.json` | Versões de modelo/schema, dataset e manifesto, grafo, seed, origem sintética, ambiente e checksums |
| `validation-report.json` | Comparação dos candidatos, grupos, latência e regra de seleção |
| `test-report.json` | Avaliação posterior do escolhido, referência física e rotas no teste |

`model_version` é `segment-model-v1-` seguido dos primeiros 16 caracteres do SHA-256 do pipeline. O hash completo fica nos metadados. Versões exatas de Python, sistema, arquitetura, serviço e dependências são conferidas antes da desserialização. Metadados, relatório de validação e pipeline precisam concordar; alterações nos bytes são rejeitadas. Use apenas artefatos produzidos por uma fonte controlada: joblib pode executar código ao carregar; checksum verifica integridade, não confiança. Veja [persistência no scikit-learn](https://scikit-learn.org/stable/model_persistence.html).

O predictor aceita entre 1 e 1000 trechos, valida novamente as seis features e exige exatamente um tempo positivo e finito para cada entrada. Campos extras, inclusive o target, são rejeitados. O carregamento pode ser feito uma vez e o objeto reutilizado; essa conexão com o ciclo de vida do FastAPI fica para a próxima feature.

Execuções independentes do comando de treinamento, com dataset, seed e ambiente iguais, produziram os mesmos bytes do pipeline e previsões. A conferência de bytes usa processos novos; a identidade de bytes não é garantida para processos já utilizados. Relatórios incluem medições de tempo que variam entre execuções; por isso, não se promete identidade de todos os metadados/relatórios, nem de artefatos entre plataformas distintas. Dados e artefatos completos ficam fora do Git, do wheel e da distribuição de fontes.

## Avaliação de rotas

Cada cenário de teste contém o grafo completo, com contextos por aresta na mesma partida planejada. Dijkstra escolhe o caminho com os custos previstos; os tempos observados calculam apenas o custo realizado desse caminho e a melhor rota possível naquele cenário.

O relatório informa excesso médio e percentil 95 de tempo, excesso percentual e fração de escolhas com tempo observado ótimo. Tempos equivalentes usam tolerância numérica `rel_tol=1e-9`, `abs_tol=1e-9`, evitando atribuir excesso a diferenças de arredondamento. Cenários com previsões inválidas são contados separadamente e não viram resultados de excesso zero.

## Resultados e validação

Execução de 2026-10-02 no dataset padrão: 6720 linhas de treino, 1680 de validação e 1680 de teste. Python 3.12.14, uv 0.12.11, NumPy 2.5.3, scikit-learn 1.9.1, SciPy 1.18.1 e joblib 1.6.0, em Linux x86_64.

| Candidato | MAE de validação, min | RMSE, min | R² | Previsões inválidas |
| --- | --- | --- | --- | --- |
| Referência física | 2,2679 | 3,5123 | 0,3327 | 0 |
| Dummy mediana | 2,8826 | 4,5038 | -0,0972 | 0 |
| Regressão linear | 1,1714 | 1,7359 | 0,8370 | 41 |
| Random Forest | 0,4381 | 0,6357 | 0,9781 | 0 |

A floresta foi escolhida por apresentar o menor MAE válido, com diferença superior à margem de simplicidade. A linear produziu tempos não positivos e ficou inelegível; seus valores foram preservados no relatório. Não se mudou o modelo com base no teste.

No teste reservado, a floresta teve MAE **0,4711 min**, RMSE **0,6942 min** e R² **0,9765**, sem previsões inválidas. A referência física teve MAE **2,3649 min** e RMSE **3,7315 min**. As execuções de lote com 256 entradas tiveram medianas entre aproximadamente 8,6 e 10,8 ms para a floresta, com aquecimento e cinco repetições por execução; essa latência depende da máquina e da carga.

Nas 168 decisões A → C de teste, a floresta escolheu caminhos com tempo observado ótimo em 167 casos (99,40%), e a referência em 165 (98,21%). O excesso médio foi aproximadamente 0,00044 min e 0,01494 min, respectivamente. O grafo pequeno já favorece a referência em quase todos os cenários; essa diferença limitada não comprova ganho em entregas reais.

O artefato validado tem SHA-256 `5e298b8ffbad39e84e92055d8e5c7ec672e24e3d47f76af35895fcf992274cc9`, versão `segment-model-v1-5e298b8ffbad39e8` e aproximadamente 3,9 MB. As métricas medem somente o domínio sintético e as vias conhecidas deste gerador; não há avaliação em ruas não vistas, mapas reais ou GPS.

Após liberar o carregamento das bibliotecas no ambiente Windows, o mesmo dataset, seed 42 e versões travadas foram usados para gerar `artifacts/segment-model-v1-windows`, sem substituir o bundle Linux em `artifacts/segment-model-v1`. A Random Forest também foi escolhida na validação. No teste nativo, teve MAE **0,4711 min**, RMSE **0,6942 min**, R² **0,9765** e zero previsões inválidas; escolheu caminhos com tempo observado ótimo em 167 dos 168 cenários. A mediana medida para 256 entradas foi aproximadamente 11,4 ms.

O bundle Windows AMD64 tem versão `segment-model-v1-47ad884548f0f255`, SHA-256 `47ad884548f0f25554b8bf38787b0e278e44a2aa65999a48cf7952daf510212c` e aproximadamente 3,9 MB. Uma segunda execução independente do comando de treinamento produziu o mesmo checksum. O predictor carregou o bundle nativamente e suas previsões coincidiram com o pipeline salvo. O hash diferente do bundle Linux não representa incompatibilidade nos dados; a compatibilidade do artefato continua sendo conferida pela plataforma e dependências registradas.

Validações concluídas:

- 264 testes passaram em Linux, com warnings tratados como erros, incluindo as suítes anteriores, integridade, pipelines, seleção, métricas, serialização, predictor e avaliação de rotas.
- 264 testes também passaram no Windows, incluindo ML, após a liberação do carregamento das bibliotecas. A primeira validação nativa tinha passado em 218 testes sem ML e encontrado o bloqueio descrito abaixo.
- Ruff, formatação, lock e build passaram. Wheel e distribuição de fontes contêm código/fixture e excluem CSVs e modelos gerados.
- O wheel foi instalado em ambiente separado, apenas com dependências de produção, e executou predictor, treinamento e avaliação fora do repositório.
- Dois treinamentos completos produziram os mesmos bytes do pipeline e métricas de teste. O servidor instalado respondeu por HTTP real ao health e OpenAPI, preservando apenas `/health` como operação disponível.
- Os 84 links locais da documentação foram conferidos.

As suítes Java não foram repetidas, pois Java, bancos, Gateway e Compose não mudaram.

Comandos de validação, da raiz em um ambiente compatível:

```powershell
uv run --project .\services\route-intelligence-service --locked python -m pytest .\services\route-intelligence-service\tests
uv run --project .\services\route-intelligence-service --locked python -m ruff check .\services\route-intelligence-service
uv run --project .\services\route-intelligence-service --locked python -m ruff format --check .\services\route-intelligence-service
uv lock --project .\services\route-intelligence-service --check
uv build --project .\services\route-intelligence-service
```

## Compatibilidade do ambiente Windows

Na primeira execução nesta máquina, o Controle de Aplicativos bloqueou módulos nativos do scikit-learn (`_cyutility` e `_datasets_pair`) ao importar ML. Executar por `python -m` evita os launchers de console, mas não resolve um bloqueio de DLLs. A validação inicial e o treinamento foram feitos em container Linux temporário, usando o mesmo lock e Python de referência.

A verificação posterior confirmou importações, testes completos, treinamento, avaliação e predictor no Windows. Para execução nativa, use `artifacts/segment-model-v1-windows`; o bundle original em `artifacts/segment-model-v1` permanece identificado como Linux. O carregador continua recusando um bundle de plataforma ou dependências incompatíveis. FastAPI `/health`, grafo e gerador continuam independentes do carregamento dos modelos. Dockerfile e Compose da aplicação ainda pertencem a uma etapa própria.

A próxima feature conectará o predictor ao Dijkstra em `POST /api/routes/fastest`, carregará o bundle uma vez por processo e acrescentará prontidão de grafo/modelo. Integração com Delivery e interface web vêm depois.
