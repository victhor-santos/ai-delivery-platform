# Pagamento de pedidos

O pedido passa a ser pago pelo Order Service, que chama o [Payment Service](simulated-payments.md) em nome do cliente. Uma aprovação confirma o pedido; sem ela não há confirmação, e sem confirmação não há entrega. O valor cobrado é sempre o total calculado do pedido. Os pagamentos continuam simulados: nenhum dinheiro é movimentado.

## Contrato HTTP

Atende diretamente em 8083 e pelo Gateway em 8080, com o Bearer token de [autenticação](authentication.md).

| Requisição | Resultado |
| --- | --- |
| `POST /api/orders/{id}/payment` com `Idempotency-Key` e `{method}` | `200` com a intenção concluída: `APPROVED` ou `DECLINED` |
| `POST /api/orders/{id}/payment` sem token | `401` |
| Pedido ausente ou de outro cliente | `404` |
| Pedido cancelado, já confirmado ou outra intenção em andamento | `409` |
| Payment recusou a requisição sem cobrar | `409`, com a intenção registrada como `REJECTED` |
| Mesma chave com outro método | `422` |
| Resultado desconhecido (Payment indisponível, timeout, resposta inválida) | `503`; a intenção fica pendente |

`method` usa os códigos de [pagamentos simulados](simulated-payments.md#contrato-http). `amount`, `status` e outros campos enviados no corpo são ignorados. A resposta contém `orderId`, `paymentId` (a tentativa no Payment Service), `status`, `declineReason`, `method`, `amount`, `currency` (`BRL`), `requestedAt` e `completedAt`. Uma recusa, como em Payment, é um resultado registrado com sucesso, e não um erro HTTP.

`GET /api/orders/{id}` acrescenta `paymentRequestedAt`, preenchido enquanto um pagamento está em andamento, e `paymentId`, a tentativa aprovada que confirmou o pedido. `POST /api/orders/{id}/confirm` foi removido: a rota responde `404`, e a confirmação só acontece por aprovação.

## Estados e regras

- Um pedido `CREATED` com total pode ser pago. Ao iniciar a intenção, Order grava `paymentRequestedAt`; enquanto ela estiver pendente, o pedido não pode ser cancelado nem receber outra intenção.
- Aprovação: o pedido vai para `CONFIRMED` com `paymentId`. Daí em diante não pode ser cancelado (`409`), e novos pagamentos retornam `409`.
- Recusa ou rejeição: `paymentRequestedAt` é limpo e o pedido continua `CREATED`. O cliente pode tentar com outra chave ou cancelar.
- `POST /api/orders/{id}/delivery` exige `CONFIRMED`; antes do pagamento retorna `409`.

Do lado do Payment, `POST /api/payments` agora consulta `GET /api/orders/{id}` com o token do cliente antes de gravar uma tentativa nova. Só aceita o pedido do próprio cliente, em `CREATED`, aguardando pagamento e pelo total exato; caso contrário, retorna `404` ou `409` sem gravar. Assim, uma cobrança direta em `/api/payments` fora do fluxo do pedido é recusada. Se Order não responder, Payment retorna `503`. Repetições de uma chave já gravada são devolvidas sem nova consulta.

## Falhas e cobrança única

Não há transação distribuída. Order grava a intenção como `PENDING` em `order_payments` antes de chamar Payment e a conclui depois, encaminhando a mesma `Idempotency-Key`. Com isso:

- **Resposta perdida ou Payment indisponível:** Order retorna `503` e a intenção fica pendente. Repetir a requisição com a mesma chave retoma a intenção; como Payment é idempotente por chave, devolve a tentativa já feita em vez de cobrar de novo.
- **Outra chave durante a pendência:** `409`, até que a original seja retomada.
- **Pedido já cobrado sob outra chave** (Payment responde `409`): Order procura a tentativa aprovada em `GET /api/payments?orderId=` e confirma o pedido com ela, em vez de deixá-lo pago e aberto. A busca considera até 100 tentativas do pedido.
- **Resposta inconsistente** (outro pedido, valor, moeda ou tentativa não simulada): é tratada como resultado desconhecido (`503`), sem concluir a intenção.

O banco garante as regras sob concorrência. `order_payments` tem chave primária `(order_id, idempotency_key)` e um índice único parcial que permite uma única intenção `PENDING` ou `APPROVED` por pedido. Em `orders`, uma constraint exige que `paymentRequestedAt` só exista em `CREATED` com total e que `paymentId` só exista em `CONFIRMED`. A migração é `V5__add_order_payments.sql`.

## Configuração

| Variável | Serviço | Padrão nativo | Compose |
| --- | --- | --- | --- |
| `PAYMENT_SERVICE_URL` | Order | `http://localhost:8084` | `http://payment-service:8084` |
| `ORDER_REMOTE_TIMEOUT_MS` | Order | `5000` | `${ORDER_REMOTE_TIMEOUT_MS:-5000}` |
| `ORDER_SERVICE_URL` | Payment | `http://localhost:8083` | `http://order-service:8083` |
| `PAYMENT_REMOTE_TIMEOUT_MS` | Payment | `3000` | `${PAYMENT_REMOTE_TIMEOUT_MS:-3000}` |

Mantenha o timeout de Payment abaixo do de Order. Assim, quando Order demora a responder à conferência, Payment retorna `503` antes de Order desistir por timeout. No Compose, `order-service` espera `payment-service` ficar saudável; Payment só chama Order durante uma requisição, então a dependência de inicialização continua em um único sentido.

## Limites

- Não há estorno, expiração de intenções pendentes nem reconciliação em segundo plano: uma intenção pendente só avança quando o cliente repete a requisição com a mesma chave.
- Payment registra a regra de chave por cliente. Reusar a mesma chave em dois pedidos faz Payment responder `422`, e o segundo pedido registra a intenção como `REJECTED` (`409`). Use uma chave nova por intenção.
- A comunicação é HTTP síncrona; não há mensageria.

## Exemplo e validação

```powershell
$baseUrl = 'http://localhost:8080'
# $auth e $order como no exemplo de pedidos
$path = "$baseUrl/api/orders/$($order.id)"
$key = [guid]::NewGuid().ToString()
$headers = $auth + @{ 'Idempotency-Key' = $key }
$payment = Invoke-RestMethod -Method Post "$path/payment" -Headers $headers -ContentType 'application/json' `
    -Body '{"method":"sim-card-approved"}'
$payment.status # APPROVED
Invoke-RestMethod -Method Post "$path/payment" -Headers $headers -ContentType 'application/json' `
    -Body '{"method":"sim-card-approved"}' # mesma intenção, sem nova cobrança
(Invoke-RestMethod $path -Headers $auth).status # CONFIRMED
```

```bash
(cd services/order-service && ./mvnw verify)
(cd services/payment-service && ./mvnw verify)
pwsh -NoProfile -File scripts/smoke-route-demo.ps1 -CheckRecovery -CheckPersistence
```

Order testa o domínio da intenção, a persistência com PostgreSQL 17 via Testcontainers (constraints, intenção única por pedido, migração), o caso de uso e o cliente HTTP. Os testes de integração substituem Payment por um servidor local e cobrem aprovação, recusa, repetição, `422`, rejeição, resposta perdida e retomada, resposta inválida, cobrança sob outra chave e disputas concorrentes. Payment testa a conferência do pedido: dono, estado, total, `404`/`409` sem gravar e `503` quando Order falha. O smoke passa pelo fluxo completo no Gateway: recusa, repetição, `422`, cobrança direta recusada, aprovação confirmando o pedido, `409` para novo pagamento e cancelamento, `404` para outra conta e o mesmo resultado depois de recriar os containers.

Em 08/10/2026, no Ubuntu, `verify` passou com 369 testes em Order e 143 em Payment, sem falhas, erros ou casos ignorados. A demo foi reconstruída com os doze containers saudáveis; o smoke passou completo com `-CheckRecovery -CheckPersistence` e também com `-OrderOnly`. As suítes dos demais serviços não foram reexecutadas; seus serviços passaram no smoke.
