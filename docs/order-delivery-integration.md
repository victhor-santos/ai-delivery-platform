# Integração entre pedidos e entregas

Um pedido confirmado pode solicitar entrega por `POST /api/orders/{id}/delivery`, sem corpo. O endpoint atende diretamente no Order Service (:8083) e pelo Gateway (:8080). A criação do pedido exige [itens de cardápio e quantidades](order-items.md) e preserva nomes e preços. A confirmação continua sendo uma ação manual independente de pagamento; solicitar entrega é o passo seguinte.

## Fluxo e respostas

1. Order exige um pedido existente em `CONFIRMED`.
2. Na primeira solicitação, consulta o restaurante por HTTP no Catalog Service e exige restaurante ativo com localização de coleta.
3. Persiste a intenção de entrega, origem e destino no próprio banco e, na mesma transação, o evento `order.delivery-requested.v1` no outbox. Essa transação também bloqueia o cancelamento do pedido.
4. Retorna `202` com `orderId` e `status: "REQUESTED"`. O evento é publicado no RabbitMQ em seguida, e o Delivery cria a entrega ao consumi-lo.

Desde a [solicitação por RabbitMQ](delivery-messaging.md), Order não chama o Delivery por HTTP. O `202` confirma que a solicitação e o evento foram gravados, não que a entrega já existe; ela aparece em `GET /api/deliveries/by-order/{orderId}` depois do consumo. Order também não altera o pedido para um estado fictício de entrega concluída.

| Resultado | HTTP |
| --- | --- |
| Solicitação registrada ou repetida | `202` em Order |
| Pedido inexistente | `404` |
| UUID malformado | `400` |
| Pedido não confirmado ou restaurante inexistente/inativo/sem coleta | `409` |
| Disputa de versão com outra alteração do pedido | `409` |
| Catálogo indisponível, timeout ou resposta inválida | `503` |

Os erros usam `application/problem+json`, sem detalhes internos. `GET /api/orders/{id}` inclui `deliveryRequestedAt`: `null` antes da intenção, timestamp UTC depois dela. `confirmedAt` é preservado e `updatedAt` passa a refletir a solicitação. A preparação da entrega mantém os itens, quantidades, nomes, preços e total do pedido, sem consultar novamente o cardápio ou recalcular valores.

## Novas tentativas e snapshots

Order guarda origem (nome e coordenadas do restaurante) e destino (endereço e coordenadas do pedido) em `order_delivery_requests`. Uma nova tentativa usa esses mesmos dados e não consulta o catálogo novamente. Atualizações posteriores do restaurante não mudam a entrega já solicitada.

O Delivery cria a entrega a partir do evento com `INSERT ... ON CONFLICT (order_id) DO NOTHING`, seguido da consulta e comparação dos dados persistidos. Um evento repetido recupera a mesma entrega, com UUID, horários e histórico preservados, inclusive depois de cancelada ou concluída. Snapshots ou cliente diferentes vão para a fila de mensagens mortas. O antigo `PUT /api/deliveries/by-order/{orderId}` foi removido; o cadastro manual `POST /api/deliveries` mantém seu contrato: pedido duplicado retorna `409`.

O outbox repete a publicação até o broker confirmar, então uma queda do RabbitMQ ou do Delivery só atrasa a criação. Repetir `POST /api/orders/{id}/delivery` depois de uma resposta perdida devolve a mesma solicitação, sem novo evento.

## Cancelamento e limites

O cancelamento do pedido retorna `409` depois que a intenção foi persistida. Assim, uma entrega ainda não criada pelo Delivery não fica associada a um pedido cancelado. Quando a validação inicial do catálogo falha, nenhuma intenção é gravada e o pedido ainda pode ser cancelado.

O controle de versão do pedido decide disputas entre cancelamento e preparação da entrega. A gravação da intenção e do marcador ocorre na mesma transação; apenas uma das operações concorrentes pode vencer. A migration V2 preserva os pedidos anteriores, deixando `deliveryRequestedAt` vazio.

Não há remoção de intenções nem coordenação de cancelamentos entre serviços. Delivery ainda permite seu cadastro manual de demonstração; a verificação de pedido confirmado pertence ao fluxo do Order Service.

## Configuração e demonstração

Order usa `CATALOG_SERVICE_URL` (padrão `http://localhost:8082`) e `ORDER_REMOTE_TIMEOUT_MS` (padrão `5000`), além das variáveis `RABBITMQ_*` descritas na [solicitação por RabbitMQ](delivery-messaging.md). O cliente limita conexão a dois segundos e cada requisição ao timeout configurado. As URLs podem ser fornecidas no `.env` da raiz ou no ambiente. Nenhum serviço consulta tabelas do banco de outro serviço.

Inicie os bancos, o RabbitMQ e as aplicações User, Catalog, Order, Delivery e Gateway conforme o README. A solicitação exige o token do cliente que criou o pedido; outra conta recebe `404`. O exemplo cria dados locais:

```powershell
$baseUrl = 'http://localhost:8080'
$email = "demo-$([guid]::NewGuid().ToString('N'))@example.test"
$account = @{ name = 'Cliente Demo'; email = $email; password = 'demonstration-password-123' }
Invoke-RestMethod -Method Post "$baseUrl/api/users/auth/register" -ContentType 'application/json' `
    -Body ($account | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$baseUrl/api/users/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $account.password } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.accessToken)" }
$restaurant = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/catalog/restaurants" `
    -ContentType 'application/json' `
    -Body '{"name":"Restaurante Central","pickupLocation":{"latitude":-23.55,"longitude":-46.63}}'
$item = Invoke-RestMethod -Method Post `
    -Uri "$baseUrl/api/catalog/restaurants/$($restaurant.id)/menu-items" `
    -ContentType 'application/json' -Body '{"name":"Prato do dia","price":29.90}'
$body = @{
    restaurantId = $restaurant.id
    destination = @{ address = 'Rua Central, 42'; latitude = -23.56; longitude = -46.64 }
    items = @(@{ menuItemId = $item.id; quantity = 2 })
} | ConvertTo-Json -Depth 10
$order = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders" -Headers $auth -ContentType 'application/json' -Body $body
$order.total # 59.80, calculado a partir dos preços do catálogo
$path = "$baseUrl/api/orders/$($order.id)"
$pay = $auth + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
Invoke-RestMethod -Method Post -Uri "$path/payment" -Headers $pay -ContentType 'application/json' `
    -Body '{"method":"sim-card-approved"}' # confirma o pedido
Invoke-RestMethod -Method Post -Uri "$path/delivery" -Headers $auth # 202, status REQUESTED
Start-Sleep -Seconds 1
Invoke-RestMethod "$baseUrl/api/deliveries/by-order/$($order.id)" # criada a partir do evento
Invoke-RestMethod -Method Post -Uri "$path/delivery" -Headers $auth # mesma solicitação, sem nova entrega
Invoke-RestMethod $path -Headers $auth # inclui deliveryRequestedAt e os itens/valores preservados
```

Depois, use a [API do ciclo de entregas](delivery-lifecycle.md) para atribuir um entregador e registrar coleta, partida, chegada e conclusão.

## Validação

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml clean verify
```

Os testes usam PostgreSQL 17 descartável via Testcontainers. As integrações de Order substituem o catálogo por um servidor HTTP local e usam um RabbitMQ descartável para verificar o evento publicado; a [solicitação por RabbitMQ](delivery-messaging.md) descreve essa validação. Os parágrafos abaixo registram a validação da integração HTTP anterior. Também há testes de timeout, disputa entre cancelamento e intenção, preservação de pedidos da V1 e chamadas idempotentes simultâneas no Delivery.

Em 02/10/2026, os builds de Delivery (172 testes) e Order (92 testes) passaram, sem falhas, erros ou testes ignorados, gerando os JARs executáveis. Delivery foi validado com `clean verify`; Order com `clean verify` e depois `verify`, após acrescentar os testes de disputa e timeout.

Catalog, Order, Delivery e Gateway também foram iniciados com bancos PostgreSQL descartáveis. Pelo Gateway, foram verificados criação/consulta, repetição preservando UUID e snapshots, restaurante sem coleta, cancelamento bloqueado após intenção e recuperação depois de interromper e reiniciar Delivery. Na indisponibilidade, Order retornou `503`; a tentativa seguinte recuperou o fluxo sem duplicação. Os processos e o container temporário foram encerrados, preservando os volumes de desenvolvimento.

Em 05/10/2026, após a integração dos itens e preços aos pedidos, Order passou em `clean verify` com 270 testes, sem falhas, erros ou casos ignorados, e gerou o JAR executável. A suíte mantém as verificações de entrega e acrescenta preservação da composição e do total nas transições e na preparação idempotente da entrega, além da migração V2→V3 sobre um pedido com intenção já persistida.
