# Observações de travessias por trecho

Delivery registra eventos simulados de entrada e saída nos trechos de um plano de rota. Cada observação guarda uma cópia das features e da previsão original para comparar o tempo previsto com o observado. O serviço não consulta Python novamente ao registrar eventos ou exportar observações.

## Contrato HTTP

Os endpoints ficam sob `/api/deliveries/{deliveryId}/segments`, com acesso pelo Gateway.

| Método e sufixo | Resultado |
| --- | --- |
| `PUT /{sequence}/entry` | Registra entrada; repetição idêntica devolve o registro existente |
| `PUT /{sequence}/exit` | Completa a travessia; repetição idêntica preserva duração e disponibilidade |
| `GET` | Lista observações por sequência, incluindo incompletas |
| `GET /export?availableAtCutoff=...` | CSV UTF-8 com observações completas disponíveis até o corte inclusivo |

Entrada e saída recebem o mesmo formato:

```json
{
  "routePlanId": "8a4c2b25-3157-49bf-9ca4-3d03bd8e38c4",
  "occurredAt": "2026-10-03T12:01:00Z",
  "dataOrigin": "simulated"
}
```

Use o ID efetivo do plano salvo, não o UUID ilustrativo. A sequência começa em zero e deve identificar um trecho desse plano. Datas exigem segundos e offset, são normalizadas para UTC e truncadas para microssegundos. Eventos futuros são rejeitados. Somente `dataOrigin=simulated` é aceito; não há coleta GPS ou medição de trânsito real.

Os PUTs retornam `200`, tanto na primeira gravação quanto na repetição. A resposta inclui `id`, `deliveryId`, `routePlanId`, `sequence`, `dataOrigin`, `enteredAt`, `entryRecordedAt`, `exitedAt`, `labelAvailableAt`, `actualTravelTimeMinutes` e `prediction`. Este último contém `segment`, `plannedDepartureAt`, `predictionAt`, `contextAsOf`, `modelVersion`, `graphVersion` e `dataOrigin=synthetic`. O segmento inclui distância, tempo previsto e `predictionContext`, com as features, direção, fuso e proveniência do tráfego.

Erros usam `application/problem+json`: `400` para entrada inválida, `404` para entrega/plano inexistente e `409` para evento incompatível com o histórico, estado, sequência ou plano. Uma entrega existente sem observações retorna `[]`; exportação sem resultados retorna somente o cabeçalho CSV.

## Regras e compatibilidade

- A entrega deve ter partido. Eventos atrasados podem ser registrados após sua conclusão, desde que tenham ocorrido entre partida e chegada.
- A primeira entrada usa sequência zero; cada nova entrada exige a saída do trecho anterior e não pode antecedê-la. A saída deve ser posterior à própria entrada.
- Uma entrada deve ocorrer após a criação do plano e sua previsão. O snapshot contém as features da partida **planejada**, sem substituí-las pelo horário real da travessia.
- Repetir plano, origem e instante de um evento mantém seu ID, timestamps, label e versão da entrega. Um instante diferente para o mesmo evento retorna conflito; não há correção retroativa nesta versão.
- A chegada não pode anteceder eventos de travessia já registrados. O ciclo da entrega continua funcionando sem observações; registrar todos os trechos não é requisito para concluir uma entrega.
- Planos antigos, sem `predictionContext`, continuam consultáveis. Não podem gerar observações: antes da partida, calcule um novo plano usando o Python atualizado. Não inventamos features históricas para entregas que já partiram.
- Uma rota sem trechos, com origem e destino no mesmo nó, não produz observações.

## Persistência e disponibilidade

A migration `V3__create_segment_observations.sql` cria `delivery_segment_observations`, com unicidade de `(delivery_id, sequence)`, FK para entrega, constraints temporais e snapshot JSONB. O ID do plano identifica a previsão copiada; não há dependência de uma consulta futura ao plano para reconstruir o snapshot.

O adaptador adquire `SELECT ... FOR UPDATE` na entrega antes de ler observações e amostrar o relógio. `entryRecordedAt` e `labelAvailableAt` representam o horário de aceitação do evento sob esse bloqueio, na transação que o persiste; não são timestamps exatos do commit do PostgreSQL. Assim, espera pelo bloqueio não antecipa a disponibilidade registrada. Leituras só retornam gravações confirmadas.

`SegmentObservationPolicy` concentra as regras de sequência, proveniência e intervalo da viagem, sem Spring ou SQL. O repositório mantém bloqueio, serialização e escrita atômica. Cada nova entrada/saída incrementa a versão otimista da entrega para impedir uma transição baseada em estado anterior, sem alterar os timestamps do ciclo. Falha em qualquer escrita reverte a observação e o incremento juntos.

Sem saída, duração e disponibilidade do rótulo ficam nulas. A duração é `(exited_at - entered_at)` em minutos. A disponibilidade é atribuída no recebimento da saída, mesmo se a travessia ocorreu antes. A exportação exige `label_available_at`, `prediction_at` e `features_available_at` menores ou iguais ao corte, que não pode estar no futuro.

## CSV e treinamento

O schema exportado é `delivery-segment-observation-v1`, com 34 colunas: identidade da travessia/plano, proveniência, versões, seis features, timestamps, previsão e duração observada. Campos são delimitados por vírgula e valores por aspas. A ordem segue a sequência dos trechos. `recorded_at` é um alias de `label_available_at` nesta versão; a entrada possui `entry_recorded_at` próprio. Endereços e IDs de entregadores não são exportados.

O CSV serve para auditoria e comparação. **Não é entrada direta de `training.train`**: o carregador atual espera o dataset `segment-sample-v1`, produzido pelo gerador sintético, com manifesto, checksums e partições por cenário. Uma integração futura deverá converter explicitamente as observações, preservar a origem `simulated` e separar entregas e períodos sem vazamento. Esta etapa não retreina o modelo nem mistura automaticamente esses dados ao dataset anterior.

## Executar e verificar

Na raiz, com o bundle Linux e o Compose preparados conforme o [guia](route-intelligence-compose.md):

```powershell
docker compose --profile demo up -d --build --wait --wait-timeout 240
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -CheckRecovery -CheckPersistence
```

O smoke cria restaurante, pedido, entrega e plano; valida recuperação do Python; atribui entregador; registra travessias simuladas usando timestamps devolvidos pelo servidor; confere idempotência, snapshots e CSV; conclui a entrega e verifica persistência após recriar os containers. Essas durações curtas exercitam o contrato, não representam desempenho real de entregas. Os registros permanecem no banco e os volumes são preservados.

Para baixar o CSV de uma entrega já registrada:

```powershell
$deliveryId = 'ID-DA-ENTREGA'
$baseUrl = 'http://localhost:8080'
$cutoff = [uri]::EscapeDataString([DateTimeOffset]::UtcNow.ToString('o'))
Invoke-WebRequest "$baseUrl/api/deliveries/$deliveryId/segments/export?availableAtCutoff=$cutoff" -OutFile observations.csv
```

Testes locais:

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml verify
uv run --project services/route-intelligence-service --frozen pytest services/route-intelligence-service/tests
```

Os testes de integração usam PostgreSQL descartável via Testcontainers, incluindo duplicatas concorrentes, saídas conflitantes, espera por bloqueio, rollback, constraints, eventos atrasados, cortes inclusivos, snapshots antigos e erros HTTP. Os testes Python conferem que os snapshots correspondem às features usadas pelo predictor e permanecem imutáveis após outro planejamento.

## Validação registrada

Em 03/10/2026, com Java 21, Python 3.12, Docker e PostgreSQL 17:

- `mvnw verify` do Delivery: 289 testes aprovados, sem falhas ou testes ignorados, e JAR executável gerado.
- Python: 331 testes aprovados; Ruff e verificação de formatação aprovados nos 65 arquivos.
- Imagens de Delivery e Python reconstruídas. Foi corrigida a reutilização de um wheel antigo no cache do `uv`: a instalação final do projeto usa `--no-cache`, mantendo a camada anterior de dependências em cache.
- Smoke completo pelo Gateway no projeto Compose isolado `delivery-observations-validation`, com dez containers saudáveis e as opções `-CheckRecovery -CheckPersistence`.
- Conferidos indisponibilidade/recuperação do Python, eventos simulados idempotentes, snapshots, CSV, conclusão da entrega e preservação dos registros após recriar containers.
- Sintaxe PowerShell, links locais da documentação e diff revisados. O smoke trata corretamente listas JSON no PowerShell 5.

O ambiente isolado usa portas 18080/18000 e 15432/15434/15435, sem alterar o `.env` nem os volumes dos três bancos de desenvolvimento. Os containers de validação são encerrados ao final, preservando seus volumes. Os demais serviços foram exercitados no smoke; suas suítes Maven não foram repetidas nesta feature.
