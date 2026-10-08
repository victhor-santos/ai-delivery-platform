# Pedidos

O Order Service registra criação, consulta, confirmação e cancelamento de pedidos. Um pedido confirmado pode [solicitar entrega](order-delivery-integration.md), com validação do restaurante e snapshots persistidos.

Novos pedidos exigem itens e quantidades. O cadastro consulta o catálogo por HTTP, exige restaurante ativo e itens disponíveis e salva nomes, preços unitários e total em BRL. Alterações posteriores no cardápio não recalculam o pedido. O [contrato de itens e valores](order-items.md) descreve limites, erros e preservação dos pedidos antigos.

Todas as operações exigem o Bearer token do cliente; o pedido registra o `customerId` do token e só atende esse cliente, retornando `404` para outra conta. Veja a [autorização dos recursos](resource-authorization.md). O pedido é confirmado somente por um [pagamento simulado aprovado](order-payment-integration.md), cobrado pelo total do pedido; depois dele, `POST /api/orders/{id}/delivery` solicita a entrega e verifica a localização de coleta.

## Estados e dados

```text
CREATED ──payment──> CONFIRMED
   │                    │
 cancel               cancel (sem pagamento
   │                    │      aprovado)
   └──────> CANCELLED <──┘
```

O servidor gera o UUID e inicia o pedido como `CREATED`. O destino contém endereço obrigatório de até 255 caracteres, latitude entre -90 e 90 e longitude entre -180 e 180. Espaços nas extremidades do endereço são removidos. Coordenadas precisam ser números finitos; strings numéricas são rejeitadas. O destino não pode ser alterado nesta etapa.

A composição também é imutável nesta etapa. Cada linha contém `menuItemId`, nome e preço consultados no catálogo, quantidade e subtotal calculado. O total é a soma dos subtotais, sem taxas ou descontos. A criação aceita de 1 a 50 itens distintos, com quantidade inteira de 1 a 99. Preços, nomes, moeda e totais fornecidos pelo cliente são ignorados. Pedidos anteriores à V3 possuem `items: []`, `total: null` e `currency: null`, indicando composição e valor desconhecidos.

`createdAt` e `updatedAt` são preenchidos no cadastro. `confirmedAt` e `cancelledAt` começam como `null` e registram as respectivas transições. Os horários usam UTC e precisão de microssegundos. Um cancelamento após a confirmação preserva os dois eventos.

Cancelar um pedido já cancelado retorna o estado atual, sem mudar os horários. Um pedido com pagamento em andamento ou aprovado não pode ser cancelado (`409`), e um pedido cancelado não pode ser pago (`409`). `paymentRequestedAt` e `paymentId` acompanham o pagamento; veja [pagamento de pedidos](order-payment-integration.md). O cadastro não é idempotente: repetir `POST /api/orders` cria outro pedido.

O pedido inclui `deliveryRequestedAt`, inicialmente `null`. Depois da intenção de entrega persistida, o cancelamento é rejeitado com `409`, inclusive se Delivery estiver indisponível. Uma nova tentativa de solicitação recupera a entrega pelo mesmo pedido e snapshots, sem criar duplicatas. Veja [o contrato, falhas e exemplos da integração](order-delivery-integration.md).

## Banco e execução

Na raiz do repositório, copie `.env.example` para `.env` apenas se esse arquivo ainda não existir. Se já houver configuração local, acrescente as entradas `ORDER_DB_*` sem substituir as do catálogo:

```dotenv
ORDER_DB_USERNAME=orders
ORDER_DB_PASSWORD=orders_local_only_change_me
ORDER_DB_PORT=5434
ORDER_DB_URL=jdbc:postgresql://localhost:5434/orders
```

A senha acima é um exemplo para desenvolvimento; escolha seu valor local antes de inicializar o banco. `.env` é ignorado pelo Git. O Compose permite executar somente o catálogo sem configurar pedidos, mas um `order-db` vazio não inicializa com senha vazia. O serviço Java também exige `ORDER_DB_PASSWORD`.

```powershell
docker compose up -d --wait order-db
docker compose ps
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

O PostgreSQL fica em `127.0.0.1:5434` e o serviço em `8083`. Se trocar a porta do banco, ajuste tanto `ORDER_DB_PORT` quanto `ORDER_DB_URL`. O volume `order_postgres_data` é independente do catálogo. Mudar a senha no arquivo não muda a senha de um volume já inicializado.

Flyway aplica V1 (pedidos), V2 (intenções de entrega) e V3 (itens e total); Hibernate valida o schema com `ddl-auto=validate`. A tabela guarda destino, estado, timestamps, total opcional para registros antigos e uma versão para controle de concorrência. `order_items` guarda os snapshots ordenados, na mesma transação do pedido. Não há chave estrangeira para tabelas do catálogo nem consultas ao banco de outro serviço.

Para parar o banco e preservar os dados:

```powershell
docker compose stop order-db
```

## Chamadas HTTP

Use `http://localhost:8083` para acessar o serviço ou `http://localhost:8080` pelo Gateway. A rota `/api/orders/**` mantém o caminho completo. O Gateway pode ser iniciado em outro terminal:

```powershell
.\api-gateway\mvnw.cmd -f .\api-gateway\pom.xml spring-boot:run
```

Para criar um pedido, inicie também o catálogo, User e seus bancos conforme o [README](../README.md). O exemplo cria uma conta, faz login e cadastra restaurante e item pelo Gateway:

```powershell
$baseUrl = 'http://localhost:8080'
$email = "demo-$([guid]::NewGuid().ToString('N'))@example.test"
$account = @{ name = 'Cliente Demo'; email = $email; password = 'demonstration-password-123' }
Invoke-RestMethod -Method Post "$baseUrl/api/users/auth/register" -ContentType 'application/json' `
    -Body ($account | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$baseUrl/api/users/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $account.password } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.accessToken)" }
$restaurant = Invoke-RestMethod -Method Post "$baseUrl/api/catalog/restaurants" `
    -ContentType 'application/json' -Body '{"name":"Cantina Demo"}'
$menuUrl = "$baseUrl/api/catalog/restaurants/$($restaurant.id)/menu-items"
$item = Invoke-RestMethod -Method Post $menuUrl -ContentType 'application/json' `
    -Body '{"name":"Prato do dia","price":25.90}'
$body = @{
    restaurantId = $restaurant.id
    items = @(@{ menuItemId = $item.id; quantity = 2 })
    destination = @{
        address = 'Rua das Flores, 42'
        latitude = -23.5610
        longitude = -46.6560
    }
} | ConvertTo-Json -Depth 10

$response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/api/orders" -Headers $auth `
    -ContentType 'application/json' -Body $body
$response.StatusCode
$response.Headers['Location']
$order = $response.Content | ConvertFrom-Json
Invoke-RestMethod "$baseUrl/api/orders/$($order.id)" -Headers $auth
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($order.id)/cancel" -Headers $auth
```

O cadastro retorna `201` e `Location: /api/orders/{id}`. Consulta e cancelamento retornam `200` com o mesmo formato. A confirmação acontece pelo [pagamento do pedido](order-payment-integration.md):

```json
{
  "id": "eebf0950-c061-4ba9-9daf-97f68e732f5f",
  "customerId": "5c0f9a4e-2a4f-4c5b-9d7e-0e6b1a2c3d4e",
  "restaurantId": "9d6c1458-0c80-45cd-a9c0-b420f25c8246",
  "destination": {
    "address": "Rua das Flores, 42",
    "latitude": -23.561,
    "longitude": -46.656
  },
  "status": "CREATED",
  "createdAt": "2026-09-30T15:00:00Z",
  "updatedAt": "2026-09-30T15:00:00Z",
  "confirmedAt": null,
  "cancelledAt": null,
  "deliveryRequestedAt": null,
  "items": [{
    "menuItemId": "b1c1de8c-b40e-4167-a52b-e68e3b338f96",
    "name": "Prato do dia",
    "quantity": 2,
    "unitPrice": 25.90,
    "lineTotal": 51.80
  }],
  "total": 51.80,
  "currency": "BRL",
  "paymentRequestedAt": null,
  "paymentId": null
}
```

Erros usam `application/problem+json`:

| Status | Situação |
| --- | --- |
| `400` | JSON inválido, UUID malformado, destino inválido, itens ausentes/duplicados ou quantidade inválida |
| `404` | UUID válido sem pedido correspondente |
| `409` | Restaurante/item ausente ou indisponível, confirmação de pedido cancelado ou atualização concorrente |
| `503` | Falha, timeout ou resposta inválida de uma dependência HTTP |
| `500` | Falha inesperada, com mensagem genérica na resposta |

Ao receber conflito de concorrência, consulte novamente o pedido antes de decidir por outra operação. Não há retry automático. `GET /api/orders/ping`, `/actuator/health` e `/actuator/info` continuam disponíveis. O health de pedidos inclui a conexão com seu banco.

## Código e testes

O controller valida o DTO e chama `OrderService`. Na criação, o serviço consulta a porta `CatalogLookup` fora da transação do banco, monta `OrderItem` e `OrderPricing` e grava o agregado pela porta `OrderRepository`. Uma falha de consulta impede a gravação. Na infraestrutura, o adaptador JPA salva composição e total juntos; nas transições, carrega o estado e chama as regras do domínio dentro da transação. Depois do commit, o controller monta a resposta. As regras de preços e lifecycle não dependem de HTTP ou JPA.

As atualizações usam `@Version`: duas transações que leram a mesma versão não podem sobrescrever o estado silenciosamente. O schema também rejeita coordenadas inválidas e combinações inconsistentes de estado e horários.

Com Docker funcionando, a partir da raiz:

```powershell
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml clean verify
```

Os testes cobrem regras do domínio, relógio controlado, contrato HTTP, consulta ao catálogo com precisão decimal, dados persistidos, composição imutável, transições repetidas, legados, erros, constraints, rollback e conflito entre duas transações. Os bancos de Testcontainers são descartáveis e não acessam os volumes locais. Não é necessário subir Compose nem configurar `.env` para a suíte. Docker indisponível faz a integração falhar.

Em 05/10/2026, `clean verify` passou com 270 testes, sem falhas, erros ou casos ignorados, e gerou o JAR. O [guia de itens](order-items.md) registra a validação integrada desta feature.

O build gera `services/order-service/target/order-service-0.0.1-SNAPSHOT.jar`. Com o banco local iniciado, também é possível executar da raiz:

```powershell
java -jar .\services\order-service\target\order-service-0.0.1-SNAPSHOT.jar
```

## Validação local em 30/09/2026

`clean verify` passou nas seis aplicações: 75 testes de pedidos, 131 de catálogo e um teste de contexto em cada uma das outras quatro aplicações. Não houve falhas ou testes ignorados.

Com os JARs de catálogo, pedidos e Gateway em execução, passaram 28 verificações HTTP. Foram conferidos cadastro com `201` e `Location`, consulta, confirmação e cancelamento repetidos, erros `400`, `404` e `409`, pings, saúde e leitura do catálogo pelo Gateway. Os dois pedidos criados na verificação continuaram disponíveis após reiniciar o Order Service.

`catalog-db` e `order-db` ficaram saudáveis no Docker. Os pedidos de demonstração foram preservados, assim como os dados e volumes existentes. As aplicações Java iniciadas para a verificação foram encerradas. A integração de pedidos com catálogo e entregas ainda não faz parte desta versão.
