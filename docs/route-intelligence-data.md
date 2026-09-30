# Dados e modelo de tempo por trecho

O modelo ainda será desenvolvido. Este documento define os dados, os cuidados com vazamento de informação e a forma de avaliar os resultados. A primeira versão usará dados sintéticos; a integração está descrita na [arquitetura](route-intelligence.md) e no [contrato](route-intelligence-contract.md).

## O que o modelo prevê

O problema é uma regressão supervisionada: estimar quantos minutos leva para percorrer um trecho. Cada linha do dataset representa uma travessia, com direção e contexto definidos. O target de treino é `actual_travel_time_minutes`, e a saída da previsão é `predicted_travel_time_minutes`.

```text
actual_travel_time_minutes = (exited_at - entered_at).total_seconds() / 60
```

O tempo total da entrega inclui espera no restaurante, coleta e atendimento ao cliente. Por isso, não podemos dividi-lo pela distância para obter o tempo de cada trecho. Já uma parada no trânsito faz parte da travessia e deve contar no target. Tempos altos reais não serão removidos apenas para melhorar as métricas.

## Dados a preservar

| Grupo | Campos e significado |
| --- | --- |
| Identidade | `traversal_id`, `delivery_id` ou `scenario_id`, `segment_id`, direção, sequência e `graph_version` |
| Previsão | `prediction_at`, `planned_departure_at`, `context_as_of`, valor previsto e `model_version` |
| Features | Snapshot de distância, tipo de via, velocidade de referência, tráfego, horário e dia |
| Proveniência | `data_origin`, versão do gerador/fonte, fuso, origem e instante de observação do tráfego, instante em que cada dado ficou disponível |
| Resultado | `entered_at`, `exited_at`, `recorded_at` e `label_available_at` |

`label_available_at` registra quando os dados da travessia ficaram completos no sistema. Um evento pode ter ocorrido antes do corte de treino e ter sido recebido só depois; nesse caso, ainda não estava disponível para aquele treinamento. Os eventos originais serão guardados para investigar correções, preservando o snapshot usado na previsão.

IDs de entrega, entregador e trecho ajudam a relacionar registros e separar os conjuntos, mas não entram nas features iniciais. O dataset exportado também dispensa dados pessoais. Travessias incompletas ou canceladas ficam sem rótulo e fora do treino; atribuir zero a elas ensinaria um resultado errado ao modelo.

Para usar dados reais, precisaremos registrar travessias por trecho. Isso pode vir de eventos associados ao grafo ou de posições GPS associadas às vias. Essa coleta ainda não existe; até lá, as observações serão identificadas como sintéticas.

## Features iniciais

| Feature | Fonte inicial | Regra |
| --- | --- | --- |
| `distance_km` | Atributo do trecho no grafo sintético | Conhecida antes do deslocamento, positiva e finita |
| `road_type` | Categoria definida no grafo | Vocabulário versionado: `residential`, `primary`, `highway` |
| `reference_speed_kmh` | Velocidade de referência declarada no cenário | Positiva; não calculada com o tempo realizado da própria travessia |
| `traffic_level` | Snapshot sintético por trecho | `low`, `medium` ou `high`; fonte e disponibilidade registradas |
| `hour` | Partida planejada no fuso do grafo | Inteiro de 0 a 23, mesma semântica em treino e inferência |
| `day_of_week` | Partida planejada no fuso do grafo | Segunda-feira igual a 0, domingo igual a 6 |

O nome `reference_speed_kmh` deixa claro que a velocidade já é conhecida antes da viagem. Uma média histórica pode entrar depois, desde que use apenas observações anteriores à previsão e tenha uma fonte documentada.

Na primeira versão, todos os trechos usam o horário e o contexto da partida. O horário real de entrada em um trecho posterior ainda não é conhecido quando a rota é calculada e não pode ser usado como feature. Para considerar a chegada estimada a cada trecho, será preciso atualizar o schema e a avaliação.

Chuva, incidentes, região, interseções e médias históricas dependem de fontes que ainda não temos. Valores ausentes terão tratamento definido no pipeline; o Java não preencherá esses dados por conta própria.

## Geração dos dados sintéticos

O gerador receberá seed, parâmetros e data inicial da simulação. Com a mesma configuração e ambiente, deverá produzir as mesmas linhas e checksum. Ele não dependerá do relógio atual.

Uma fórmula inicial para gerar o tempo observado é:

```text
tempo_base = 60 * distance_km / reference_speed_kmh
tempo_observado = tempo_base * fator_trafego * fator_horario * ruido_positivo
```

O tipo de via influencia a velocidade de referência. Distâncias maiores, trânsito pesado e horários de pico tendem a aumentar o tempo. O ruído acrescenta variação aleatória positiva, sem depender de dados futuros. A distribuição dos valores, as correlações e os limites para motocicletas serão documentados junto aos parâmetros.

O gerador cria o target, mas o predictor recebe apenas uma lista definida de features. O tempo observado, os horários de chegada e as variáveis auxiliares da geração ficam fora dessa lista.

Um arquivo de metadados acompanhará o dataset com seed, versão, quantidade de exemplos, período simulado, categorias, schema e checksum. O Git guardará o gerador e pequenos exemplos de teste; datasets completos e artefatos gerados ficarão fora por padrão.

## Divisão dos dados e prevenção de leakage

1. Definir grupos por viagem/cenário e datas de corte antes de ajustar modelos.
2. Separar períodos de treino, validação e teste, mantendo uma viagem/cenário inteiro em um conjunto. Grupos que atravessarem um corte serão removidos dessa avaliação ou alocados sem violar a ordem temporal; a política será registrada.
3. No treino, usar somente rótulos e features disponíveis até seu corte. Uma observação antiga recebida posteriormente não poderá entrar retroativamente.
4. Ajustar transformações, categorias e escalas apenas no treino, dentro de um pipeline.
5. Escolher hiperparâmetros e modelo usando validação. Reservar o teste para a avaliação final.

Um mesmo trecho pode aparecer em períodos diferentes sem causar vazamento: prever o tempo futuro em vias conhecidas faz parte do problema. Reservar alguns trechos para outra avaliação permitirá medir também o desempenho em vias não vistas no treino.

| Risco | Controle |
| --- | --- |
| `actual_travel_time`, `arrival_time`, `completed_at` ou tempo final como entrada | Lista permitida de features e teste de exclusão do target |
| Velocidade derivada do próprio rótulo | Usar referência disponível previamente |
| Tráfego observado ou recebido depois da previsão | Snapshot com horários de ocorrência e disponibilidade |
| Média histórica incluindo o exemplo atual ou futuro | Janela estritamente anterior e teste do corte |
| Mesma viagem/cenário nos dois lados da divisão | Teste garantindo que os grupos não se repetem entre conjuntos |
| Normalização/encoding no dataset completo | `Pipeline` ajustado somente no treino |
| Duplicatas ou repetição quase idêntica dos cenários sintéticos | Identificação de grupos e auditoria de sobreposição |
| Escolher o modelo pelo teste final | Validação para seleção; teste reservado |

Essas escolhas seguem os cuidados de [prevenção de leakage](https://scikit-learn.org/stable/common_pitfalls.html) e [validação por grupos e tempo](https://scikit-learn.org/stable/modules/cross_validation.html) documentados pelo scikit-learn.

## Modelos e avaliação

Vamos comparar:

- `DummyRegressor`, com estratégia registrada, como mediana para referência de MAE.
- `LinearRegression`, com tratamento explícito de categorias e variáveis numéricas.
- `RandomForestRegressor`, com seed e complexidade limitadas.
- Referência determinística `60 * distance_km / reference_speed_kmh`, para avaliar se ML acrescenta valor à estimativa física inicial.

Pré-processamento e estimador ficarão no mesmo pipeline, incluindo o tratamento de categorias desconhecidas e valores faltantes. Todos os candidatos usarão as mesmas partições. Gradient Boosting pode ser avaliado depois, se houver motivo para ampliar a comparação.

O relatório terá MAE e RMSE em minutos, R², erros por tipo de via, tráfego e distância, além da latência de inferência em lote. R² negativo também será registrado. A escolha levará em conta as métricas, a simplicidade, a facilidade de explicar o modelo e seu tempo de execução.

Previsões inválidas contam na avaliação. Transformar um tempo negativo em positivo ou zero só esconderia o problema. Se isso ocorrer nas entradas aceitas, precisaremos rever o modelo, a transformação do target ou os limites de entrada antes de usá-lo na API.

Os testes usarão dados fixos e tolerâncias justificadas para as métricas. A relação entre distância e tempo será verificada por tendências em grupos de exemplos, já que o ruído pode alterar casos individuais.

Também vamos comparar a rota escolhida com a melhor rota pelos tempos observados nos cenários de teste. Esses tempos ficam disponíveis apenas para a avaliação, nunca para a escolha da rota em produção. Assim podemos detectar decisões ruins que um bom MAE global esconderia.

As métricas sintéticas medem o comportamento dentro das hipóteses do gerador. O relatório deve deixar claro que elas ainda não comprovam economia de tempo em entregas reais.

## Treinamento, artefatos e inferência

Fluxo planejado:

```text
dataset -> divisão dos conjuntos -> pipeline de features -> treino
        -> validação -> seleção -> teste final -> artefato
        -> carregamento pela API -> inferência -> Dijkstra
```

`python training/train.py`, executado dentro do futuro serviço, produzirá `artifacts/segment_travel_time_model.joblib` e metadados com versões do modelo, schema, dataset, dependências, seed e métricas. `evaluate.py` avaliará o artefato sem reajustar o modelo. A execução exata e seus argumentos serão documentados quando esses arquivos existirem.

O FastAPI carregará o pipeline uma vez por processo e não treinará no startup ou em uma requisição. O mesmo pipeline fará as transformações em treino e inferência. Artefatos serão de origem controlada e carregados em ambiente compatível: joblib usa mecanismos de persistência que não devem receber arquivos não confiáveis. [Persistência de modelos](https://scikit-learn.org/stable/model_persistence.html).

O primeiro versionamento será por arquivos de metadados e artefatos imutáveis; MLflow, registry, monitoramento de drift e retreinamento automático ficarão para depois do caso funcional.

## Validação da etapa

- Dataset reproduzível, com metadados e identificação de dados sintéticos.
- Testes de schema, features, isolamento de grupos, cortes temporais e ausência de colunas proibidas.
- Relatório comparando os candidatos e a referência determinística, com métricas e limitações.
- Artefato serializado reproduzindo as previsões do pipeline antes da gravação, dentro de tolerância.
- Predictor cobrindo artefato ausente/incompatível, entrada fora do schema e previsão inválida.
- Cenários de roteamento independentes dos testes estatísticos do modelo.
