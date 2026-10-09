# Planejamento de rotas por entrega

`feature/delivery-route-integration` conecta Delivery à [API Python de rotas previstas](intelligent-routing-api.md). Java usa as localizações imutáveis da entrega, consulta Python e guarda o último plano no seu PostgreSQL. Python não acessa o banco nem altera o ciclo da entrega. A rota existente `/api/deliveries/**` do Gateway também atende estes endpoints.

## Contrato público

| Requisição | Resultado |
| --- | --- |
| `POST /api/deliveries/{id}/route` | `200`, novo plano salvo, substituindo o anterior quando existir |
| `GET /api/deliveries/{id}/route` | `200`, último plano salvo, sem chamar Python |

O planejamento recebe apenas a partida:

```json
{"departureAt":"2026-09-29T19:00:00-03:00"}
```

`departureAt` deve ser uma string RFC 3339 com segundos e offset, até 64 caracteres e no máximo nove casas decimais. Datas inexistentes, ano fora de 1–9999, ausência de fuso, números e booleanos são rejeitados. O instante é normalizado em UTC e microssegundos. Datas passadas ou futuras são aceitas como contexto de demonstração; não representam trânsito real. Campos extras são ignorados, como nos demais comandos de Delivery; não podem substituir origem, destino, tráfego ou modelo.

É permitido planejar em `CREATED`, `ASSIGNED` e `PICKED_UP`. Depois da partida, conclusão ou cancelamento, a operação retorna `409`. Planejar não atribui entregador, não inicia trânsito e não altera os timestamps do ciclo. A consulta do plano permanece disponível nos estados posteriores, como registro da previsão já realizada.

A resposta usa nomes Java em camelCase:

```json
{
  "id": "3ed1d134-2036-4d1e-a91b-286ee32344f4",
  "deliveryId": "ef1d0c84-6137-41fb-b49d-9c5f3ee53381",
  "departureAt": "2026-09-29T22:00:00Z",
  "plannedAt": "2026-10-03T06:00:00Z",
  "route": [
    {"latitude": -23.5505, "longitude": -46.6333},
    {"latitude": -23.5540, "longitude": -46.6400},
    {"latitude": -23.5610, "longitude": -46.6560}
  ],
  "segments": [
    {"segmentId": "A-B", "distanceKm": 0.9, "predictedTravelTimeMinutes": 4.936828730402905},
    {"segmentId": "B-C", "distanceKm": 2.0, "predictedTravelTimeMinutes": 11.131625751806984}
  ],
  "distanceKm": 2.9,
  "predictedTravelTimeMinutes": 16.068454482209887,
  "predictedAt": "2026-10-03T05:59:59.900000Z",
  "contextAsOf": "2026-10-03T05:50:00Z",
  "modelVersion": "segment-model-v1-47ad884548f0f255",
  "graphVersion": "synthetic-city-v1",
  "dataOrigin": "synthetic"
}
```

Os UUIDs e horários acima são ilustrativos. `plannedAt` vem do relógio Java; `predictedAt` e `contextAsOf` vêm do serviço Python. Horários de previsão são preservados no JSON armazenado. A versão da entrega é interna e não é exposta. A resposta existente de entrega também permanece igual.

Um novo `POST` cria outro identificador de plano e substitui o registro anterior. Não há idempotência nem retentativa automática neste comando. Após timeout entre cliente e Delivery, consulte o `GET` antes de repetir; a operação pode ter sido concluída no servidor. Há somente um plano armazenado por entrega, sem histórico de todos os replanejamentos.

## Falhas e preservação do plano

| Situação | HTTP Delivery | Código adicional |
| --- | --- | --- |
| JSON, UUID ou partida inválidos | 400 | — |
| Entrega inexistente | 404 | — |
| Entrega sem plano salvo | 404 | `ROUTE_PLAN_NOT_FOUND` |
| Estado que não permite planejamento | 409 | — |
| Entrega alterada durante o cálculo | 409 | `STALE_ROUTE_PLAN` |
| Localização fora da cobertura | 422 | `OUTSIDE_ROUTE_COVERAGE` |
| Nenhum caminho dirigido entre os pontos | 422 | `ROUTE_NOT_FOUND` |
| Falha de conexão, timeout ou resposta Python inválida/indisponível | 503 | `ROUTE_SERVICE_UNAVAILABLE` |
| Falha inesperada local | 500 | — |

Erros usam `application/problem+json`, sem repassar corpo remoto, caminhos, SQL ou stack traces. Um `404 ROUTE_NOT_FOUND` de Python vira `422` em Delivery: a entrega existe, mas suas localizações não têm caminho. `422 INVALID_REQUEST` gerado por Python para o corpo produzido pelo adaptador é falha de integração e vira `503`.

Qualquer falha antes do commit preserva o plano anterior e o estado da entrega. Um plano salvo anteriormente continua identificável por seus horários e versões; o `GET` não o apresenta como uma nova inferência. Delivery pode iniciar e manter seu Actuator em `UP` enquanto Python estiver indisponível, desde que seu próprio banco esteja saudável.

## Camadas e concorrência

`application/RouteOptimizer` recebe `GeoPoint` e `RouteContext` e devolve `OptimizedRoute`. Os tipos são Java puro. `FastApiRouteOptimizerClient`, em `infrastructure`, traduz para o contrato snake_case de Python. O adaptador confere tipos JSON, campos obrigatórios, limites de tamanho das listas, custos positivos e finitos, identificadores únicos, soma dos custos, versões e origem dos dados. Confere também as pontas do percurso com tolerância de 1 metro e `contextAsOf <= predictedAt`.

Java preserva a ordem das coordenadas e dos trechos e exige N+1 pontos para N segmentos. Não possui uma segunda cópia do grafo para reconstruir a geometria de cada identificador de trecho. O caso de zero segmentos exige uma coordenada e totais exatamente zero.

`DeliveryRouteService` lê a entrega e sua versão por `DeliveryRouteRepository`, encerra a leitura e chama Python sem transação aberta. Depois, o adaptador de persistência abre uma transação curta: incrementa a versão somente se a versão lida ainda for a atual e o estado permitir planejamento, e grava o plano na mesma transação. Os timestamps do ciclo permanecem intactos.

Uma transição ou outro plano que tenha vencido durante a chamada torna o resultado obsoleto. O commit retorna `409`, sem sobrescrever o vencedor. A versão compartilhada também faz uma transição JPA baseada em leitura anterior ao plano falhar por optimistic locking. Se a escrita do plano falhar depois do incremento, ambos são revertidos.

Flyway aplica `V2__create_delivery_route_plans.sql`, sem alterar V1. `delivery_route_plans` tem chave por entrega, UUID único do plano, partida, horário local de planejamento, versão da entrega usada no commit e resposta validada em JSONB. A referência aponta para uma entrega do próprio banco. Localizações originais continuam nos snapshots imutáveis da entrega. Não é preciso apagar banco ou volumes para aplicar a migration.

## Configuração e demonstração em PowerShell

Delivery lê estas variáveis do ambiente ou do `.env` da raiz:

| Variável | Padrão |
| --- | --- |
| `ROUTE_INTELLIGENCE_URL` | `http://localhost:8000` |
| `DELIVERY_ROUTE_CONNECT_TIMEOUT_MS` | `1000` |
| `DELIVERY_ROUTE_TIMEOUT_MS` | `3000` |

Os timeouts devem ser positivos. O segundo limita a chamada completa, incluindo leitura do corpo. A URL aceita HTTP/HTTPS com host, sem usuário, query, fragmento ou caminho adicional. Não há teste remoto no startup nem chamada por aresta. O modelo é carregado pelo Python conforme seu próprio [guia de execução](intelligent-routing-api.md).

Inicie `delivery-db`, Delivery, Gateway e Python conforme o [README](../README.md) e o guia Python. Para configurar valores personalizados, acrescente as novas variáveis de `.env.example` ao `.env` existente, preservando seus demais valores.

O grafo é sintético e cobre somente pontos a até 1 metro dos seus nós. Os exemplos antigos do ciclo de entregas usam outras coordenadas e podem retornar `422` no planejamento. Para demonstrar A → C, use as coordenadas abaixo; cada execução cria uma entrega local com pedido fictício:

```powershell
$baseUrl = 'http://localhost:8080'
$deliveryBody = @{
    orderId = [Guid]::NewGuid().ToString()
    origin = @{ description = 'Restaurante sintético'; latitude = -23.5505; longitude = -46.6333 }
    destination = @{ description = 'Destino sintético'; latitude = -23.5610; longitude = -46.6560 }
} | ConvertTo-Json -Depth 3
$delivery = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/deliveries" -ContentType 'application/json' -Body $deliveryBody
$routeUrl = "$baseUrl/api/deliveries/$($delivery.id)/route"
$request = @{ departureAt = '2026-09-29T19:00:00-03:00' } | ConvertTo-Json
$plan = Invoke-RestMethod -Method Post -Uri $routeUrl -ContentType 'application/json' -Body $request
$plan
Invoke-RestMethod $routeUrl
```

O cenário padrão e o bundle Windows retornam A → B → C, 2,9 km e aproximadamente 16,06845 minutos. Para usar uma entrega criada pelo fluxo real de pedidos, solicite a entrega do [pedido confirmado](order-delivery-integration.md) e obtenha o `id` em `GET /api/deliveries/by-order/{orderId}`, com coleta e destino correspondentes ao grafo, e use os mesmos endpoints.

## Validação

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
.\api-gateway\mvnw.cmd -f .\api-gateway\pom.xml clean verify
```

A suíte de Delivery inclui contrato Java, servidor HTTP de teste, PostgreSQL descartável via Testcontainers, rollback, concorrência entre planos e transições, liberação da conexão durante espera remota, respostas inválidas e preservação do plano anterior. O Maven configura Mockito como agente explícito para Java 21, conforme a [documentação do Mockito](https://github.com/mockito/mockito/blob/v5.23.0/mockito-core/src/main/java/org/mockito/Mockito.java). O aviso de compartilhamento de classes da JVM durante instrumentação e os logs de violações de constraints provocadas pelos testes não indicam falha da aplicação.

Verificado em 2026-10-03, no Windows AMD64 com Java 21.0.12.1, Docker Desktop e PostgreSQL 17:

- 243 testes de Delivery passaram, sem falhas ou testes ignorados; `verify` gerou o JAR executável. A suíte anterior tinha 172 testes; foram acrescentados 71 para contratos, integração, persistência e concorrência de rotas.
- O Gateway passou no `clean verify`, com seu teste de contexto e JAR executável.
- Gateway, Delivery e Python foram executados em portas temporárias, com PostgreSQL descartável e o bundle Windows existente. O Gateway usou a mesma regra `/api/deliveries/**`, com o destino ajustado à porta temporária.
- O fluxo real criou a entrega, calculou A → B → C, consultou o plano pelas duas portas e replanejou preservando o ciclo. Ao parar Python, a nova consulta retornou `503` e o plano anterior continuou disponível. Reiniciar Python permitiu salvar outro plano por uma nova solicitação. Cancelar a entrega rejeitou outro planejamento com `409`, preservando o registro salvo.
- Os processos e o banco temporário foram encerrados após a verificação, sem acessar volumes locais. Não houve treinamento, alteração do serviço Python ou repetição da sua suíte nesta feature; sua inferência foi exercitada pelo fluxo real. Os builds dos demais serviços Java não foram repetidos.

## Próxima etapa

A [demonstração com Compose](route-intelligence-compose.md) acrescenta imagens, rede por hostnames, bundle compatível com Linux montado somente para leitura, health checks e verificação de recuperação. O treinamento continua fora do startup. As [observações por trecho](delivery-segment-observations.md) preservam as features do plano e associam previsões a travessias simuladas. Frontend segue como evolução posterior do [roadmap](roadmap.md).
