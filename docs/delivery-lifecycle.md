# API do ciclo de entregas

Delivery oferece criação e consulta de entregas, cadastro mínimo de entregador e comandos do ciclo. A API atende diretamente em `http://localhost:8085` e pelo Gateway em `http://localhost:8080`. Todos os endpoints usam `/api/deliveries`, incluindo entregadores; a rota existente do Gateway encaminha o caminho completo.

O [planejamento de rotas](delivery-route-integration.md) acrescenta `POST` e `GET /api/deliveries/{id}/route`, com resposta própria, sem alterar o ciclo descrito abaixo.

## Contrato HTTP

| Requisição | Resultado de sucesso |
| --- | --- |
| `POST /api/deliveries/couriers`, sem corpo | `201`, UUID gerado, `active=true` e `Location` |
| `GET /api/deliveries/couriers/{id}` | `200`, UUID e indicador ativo |
| `POST /api/deliveries` | `201`, entrega `CREATED` e `Location` |
| `GET /api/deliveries/{id}` | `200`, entrega com localizações e histórico |
| `GET /api/deliveries/by-order/{orderId}` | `200`, entrega associada ao pedido |
| `PUT /api/deliveries/by-order/{orderId}` com `origin` e `destination` | `201` na criação; `200` para os mesmos snapshots; `409` para dados diferentes |
| `POST /api/deliveries/{id}/assign` com `courierId` | `200`, estado `ASSIGNED` |
| `POST /api/deliveries/{id}/pick-up`, sem corpo | `200`, estado `PICKED_UP` |
| `POST /api/deliveries/{id}/start-transit`, sem corpo | `200`, estado `IN_TRANSIT` |
| `POST /api/deliveries/{id}/arrive`, sem corpo | `200`, chegada registrada; mantém `IN_TRANSIT` |
| `POST /api/deliveries/{id}/complete`, sem corpo | `200`, estado `DELIVERED`; exige chegada |
| `POST /api/deliveries/{id}/cancel`, sem corpo | `200`, estado `CANCELLED`; permitido antes da coleta |

O cadastro recebe `orderId`, `origin` e `destination`. Cada localização exige `description` preenchida, com até 255 caracteres após remoção dos espaços nas extremidades, `latitude` entre -90 e 90 e `longitude` entre -180 e 180. Limites inclusivos e `0,0` são válidos. Coordenadas devem ser números JSON finitos; strings e booleanos são rejeitados.

UUID da entrega, estado, entregador inicial e horários são definidos pelo servidor. Campos extras enviados no cadastro, como `id`, `status` e `createdAt`, são ignorados. As localizações são snapshots imutáveis. Os horários são instantes UTC com precisão de microssegundos, gerados por `Clock` nos casos de uso; o cliente não fornece horários das transições.

A resposta da entrega inclui `id`, `orderId`, `origin`, `destination`, `courierId`, `status`, `createdAt`, `updatedAt`, `assignedAt`, `pickedUpAt`, `departedAt`, `arrivedAt`, `deliveredAt` e `cancelledAt`. Eventos ainda não ocorridos e entregador ainda não atribuído são `null`. A versão JPA é interna e não é exposta.

## Repetições, conflitos e erros

- `400`: JSON inválido, UUID malformado, campos obrigatórios ausentes, descrição ou coordenadas inválidas.
- `404`: entrega ou entregador inexistente, incluindo consulta por pedido sem entrega.
- `409`: transição fora de ordem, comando repetido, entregador inativo/ocupado, pedido com entrega existente ou conflito de versão.
- `500`: falha inesperada, com mensagem genérica.

Erros usam `application/problem+json` com `status`, `title` e `detail`, sem SQL ou stack trace. Violações de unicidade retornam `409`; outras violações inesperadas de integridade retornam `500`.

Um entregador pode ter somente uma entrega em andamento. Conclusão ou cancelamento libera o entregador sem apagar o histórico. Uma entrega cancelada continua reservando seu `orderId`. Comandos repetidos retornam `409`, inclusive uma segunda chegada ou cancelamento. Depois de conflito ou timeout, consulte o estado atual antes de tentar novamente.

## Executar o ciclo em PowerShell

Inicie `delivery-db`, Delivery e Gateway conforme o README. Este exemplo cria dados no banco local; cada execução gera outro UUID de pedido de demonstração. Para chamar diretamente o serviço, altere `$baseUrl` para `http://localhost:8085`.

```powershell
$baseUrl = 'http://localhost:8080'
$courier = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/deliveries/couriers"
$orderId = [Guid]::NewGuid().ToString()
$body = @{
    orderId = $orderId
    origin = @{ description = 'Restaurante Central'; latitude = -23.55; longitude = -46.63 }
    destination = @{ description = 'Rua Central, 42'; latitude = -23.56; longitude = -46.64 }
} | ConvertTo-Json -Depth 3

$created = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/api/deliveries" `
    -ContentType 'application/json' -Body $body
$created.StatusCode # 201
$created.Headers['Location']
$delivery = $created.Content | ConvertFrom-Json
$path = "$baseUrl/api/deliveries/$($delivery.id)"

Invoke-RestMethod "$baseUrl/api/deliveries/couriers/$($courier.id)"
Invoke-RestMethod "$baseUrl/api/deliveries/by-order/$orderId"
$assignment = @{ courierId = $courier.id } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$path/assign" -ContentType 'application/json' -Body $assignment
Invoke-RestMethod -Method Post -Uri "$path/pick-up"
Invoke-RestMethod -Method Post -Uri "$path/start-transit"
Invoke-RestMethod -Method Post -Uri "$path/arrive"
Invoke-RestMethod -Method Post -Uri "$path/complete"
Invoke-RestMethod $path # DELIVERED, com histórico preservado
```

Para cancelar outra entrega, execute `POST /api/deliveries/{id}/cancel` enquanto ela estiver em `CREATED` ou `ASSIGNED`. O exemplo acima já terminou em `DELIVERED` e rejeita cancelamento.

## Limites desta etapa

O cadastro manual do exemplo aceita uma referência de demonstração e não consulta Order ou Catalog. No fluxo integrado, `POST /api/orders/{id}/delivery` exige pedido confirmado e restaurante ativo com coleta, persiste snapshots e usa o `PUT` idempotente do Delivery. Consulte a [integração entre pedidos e entregas](order-delivery-integration.md). A confirmação do pedido continua separada da solicitação; o cancelamento após intenção de entrega é rejeitado.

O cadastro de entregador guarda somente UUID e indicador ativo. Não há perfis, localização atual, autenticação, listagem, desativação ou reatribuição. Nenhuma migration adicional é necessária: a API usa o schema e os adaptadores integrados no PR #11.

## Validação

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
```

A suíte completa exige Docker e usa PostgreSQL 17 descartável via Testcontainers. Foram aprovados 170 testes em 01/10/2026: domínio, reconstrução, persistência, relógio injetado, casos de uso, HTTP e erros. Não houve falhas, erros ou testes ignorados. O JAR executável foi gerado.

Os testes HTTP cobrem o ciclo completo, snapshots, horários gerados pelo servidor, consultas, cancelamento, liberação do entregador, dados inválidos, recursos ausentes, comandos repetidos e concorrência na criação/atribuição. Os testes do handler verificam conflitos de versão e mensagens controladas para falhas inesperadas. Os pings e Actuator permanecem cobertos.

Em 02/10/2026, os JARs de Delivery e Gateway foram iniciados com um PostgreSQL 17 descartável. Diretamente e pelo Gateway, foram verificados cadastro de entregador/entrega (`201`), todas as transições até `DELIVERED` (`200`), consultas por pedido/entregador (`200`) e conclusão repetida (`409`). Health retornou `UP`. Os processos e o container temporário foram encerrados, preservando os volumes locais.

O [registro anterior de persistência](delivery-validation.md) documenta a etapa integrada no PR #11. A validação desta etapa se concentra no Delivery; nenhum outro serviço ou rota do Gateway foi alterado.
