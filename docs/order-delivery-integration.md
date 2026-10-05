# Integração entre pedidos e entregas

Um pedido confirmado pode solicitar entrega por `POST /api/orders/{id}/delivery`, sem corpo. O endpoint atende diretamente no Order Service (:8083) e pelo Gateway (:8080). A criação do pedido exige [itens de cardápio e quantidades](order-items.md) e preserva nomes e preços. A confirmação continua sendo uma ação manual independente de pagamento; solicitar entrega é o passo seguinte.

## Fluxo e respostas

1. Order exige um pedido existente em `CONFIRMED`.
2. Na primeira solicitação, consulta o restaurante por HTTP no Catalog Service e exige restaurante ativo com localização de coleta.
3. Persiste a intenção de entrega, origem e destino no próprio banco. Essa transação também bloqueia o cancelamento do pedido.
4. Chama `PUT /api/deliveries/by-order/{orderId}` com os snapshots persistidos, fora da transação local.
5. Valida a resposta e retorna `200` com `orderId`, `deliveryId` e `status` da entrega.

Uma resposta de sucesso identifica uma entrega confirmada pelo Delivery Service. Order não presume que ela foi criada quando ocorre timeout, falha HTTP ou resposta inválida. Também não altera o pedido para um estado fictício de entrega concluída.

| Resultado | HTTP |
| --- | --- |
| Entrega criada ou recuperada | `200` em Order |
| Pedido inexistente | `404` |
| UUID malformado | `400` |
| Pedido não confirmado, restaurante inexistente/inativo/sem coleta ou conflito nos dados da entrega | `409` |
| Disputa de versão com outra alteração do pedido | `409` |
| Serviço remoto indisponível, timeout ou resposta inválida | `503` |

Os erros usam `application/problem+json`, sem detalhes internos. `GET /api/orders/{id}` inclui `deliveryRequestedAt`: `null` antes da intenção, timestamp UTC depois dela. `confirmedAt` é preservado e `updatedAt` passa a refletir a solicitação. A preparação da entrega mantém os itens, quantidades, nomes, preços e total do pedido, sem consultar novamente o cardápio ou recalcular valores.

## Novas tentativas e snapshots

Order guarda origem (nome e coordenadas do restaurante) e destino (endereço e coordenadas do pedido) em `order_delivery_requests`. Uma nova tentativa usa esses mesmos dados e não consulta o catálogo novamente. Atualizações posteriores do restaurante não mudam a entrega já solicitada.

Delivery oferece o contrato idempotente `PUT /api/deliveries/by-order/{orderId}`, com `origin` e `destination` no formato da API de entregas. Retorna `201` na criação e `200` quando o pedido já tem uma entrega com os mesmos snapshots. UUID, horários e histórico existentes são preservados, inclusive quando a entrega já foi cancelada ou concluída. Snapshots diferentes retornam `409`.

A criação usa `INSERT ... ON CONFLICT (order_id) DO NOTHING` na transação, seguida da consulta e comparação dos dados persistidos. Duas chamadas simultâneas recuperam a mesma entrega. O cadastro manual anterior, `POST /api/deliveries`, mantém seu contrato: pedido duplicado retorna `409`.

Se Delivery gravar os dados e a resposta se perder, o cliente poderá repetir `POST /api/orders/{id}/delivery` para recuperar a mesma entrega. Não há repetição automática nem transação distribuída. A solicitação local permanece persistida durante a indisponibilidade remota.

## Cancelamento e limites

O cancelamento do pedido retorna `409` depois que a intenção foi persistida, inclusive quando a resposta remota é `503`. Assim, uma entrega cuja criação ainda é incerta não fica associada a um pedido cancelado. Quando a validação inicial do catálogo falha, nenhuma intenção é gravada e o pedido ainda pode ser cancelado.

O controle de versão do pedido decide disputas entre cancelamento e preparação da entrega. A gravação da intenção e do marcador ocorre na mesma transação; apenas uma das operações concorrentes pode vencer. A migration V2 preserva os pedidos anteriores, deixando `deliveryRequestedAt` vazio.

A primeira versão não remove intenções, coordena cancelamentos entre serviços ou executa tentativas em segundo plano. Depois de uma intenção pendente, a recuperação consiste em restabelecer os serviços e repetir a solicitação. Delivery ainda permite seu cadastro manual de demonstração; a verificação de pedido confirmado pertence ao fluxo do Order Service. Pagamento, autenticação e mensageria permanecem no roadmap.

## Configuração e demonstração

Order usa `CATALOG_SERVICE_URL` (padrão `http://localhost:8082`), `DELIVERY_SERVICE_URL` (padrão `http://localhost:8085`) e `ORDER_REMOTE_TIMEOUT_MS` (padrão `5000`). O cliente limita conexão a dois segundos e cada requisição ao timeout configurado. As URLs podem ser fornecidas no `.env` da raiz ou no ambiente. Nenhum serviço consulta tabelas do banco de outro serviço.

Inicie os três bancos e as aplicações Catalog, Order, Delivery e Gateway conforme o README. O exemplo cria dados locais:

```powershell
$baseUrl = 'http://localhost:8080'
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
$order = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders" -ContentType 'application/json' -Body $body
$order.total # 59.80, calculado a partir dos preços do catálogo
$path = "$baseUrl/api/orders/$($order.id)"
Invoke-RestMethod -Method Post -Uri "$path/confirm"
$receipt = Invoke-RestMethod -Method Post -Uri "$path/delivery"
Invoke-RestMethod "$baseUrl/api/deliveries/$($receipt.deliveryId)"
Invoke-RestMethod -Method Post -Uri "$path/delivery" # recupera a mesma entrega
Invoke-RestMethod $path # inclui deliveryRequestedAt e os itens/valores preservados
```

Depois, use a [API do ciclo de entregas](delivery-lifecycle.md) para atribuir um entregador e registrar coleta, partida, chegada e conclusão.

## Validação

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml clean verify
```

Os testes usam PostgreSQL 17 descartável via Testcontainers. As integrações de Order substituem os serviços remotos por servidores HTTP locais para exercitar repetição, resposta perdida/inválida, indisponibilidade, restaurante inativo/sem coleta e conflitos. Também há testes de timeout, disputa entre cancelamento e intenção, preservação de pedidos da V1 e chamadas idempotentes simultâneas no Delivery.

Em 02/10/2026, os builds de Delivery (172 testes) e Order (92 testes) passaram, sem falhas, erros ou testes ignorados, gerando os JARs executáveis. Delivery foi validado com `clean verify`; Order com `clean verify` e depois `verify`, após acrescentar os testes de disputa e timeout.

Catalog, Order, Delivery e Gateway também foram iniciados com bancos PostgreSQL descartáveis. Pelo Gateway, foram verificados criação/consulta, repetição preservando UUID e snapshots, restaurante sem coleta, cancelamento bloqueado após intenção e recuperação depois de interromper e reiniciar Delivery. Na indisponibilidade, Order retornou `503`; a tentativa seguinte recuperou o fluxo sem duplicação. Os processos e o container temporário foram encerrados, preservando os volumes de desenvolvimento.

Em 05/10/2026, após a integração dos itens e preços aos pedidos, Order passou em `clean verify` com 270 testes, sem falhas, erros ou casos ignorados, e gerou o JAR executável. A suíte mantém as verificações de entrega e acrescenta preservação da composição e do total nas transições e na preparação idempotente da entrega, além da migração V2→V3 sobre um pedido com intenção já persistida.
