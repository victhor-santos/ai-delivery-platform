# Pagamentos simulados

O Payment Service registra tentativas de pagamento explicitamente simuladas em PostgreSQL próprio. Não aceita dados de cartão, não chama adquirentes e não movimenta dinheiro: o resultado é fixado pelo código do método escolhido. Esta etapa ainda não consulta o Order Service; a conferência do valor e do dono do pedido pertence a `feature/order-payment-integration`.

## Contrato HTTP

Os caminhos atendem diretamente em 8084 e pelo Gateway em 8080. Todos exigem o Bearer token de [autenticação](authentication.md), exceto ping e health/info.

| Operação | Resultado |
| --- | --- |
| `POST /api/payments` com `Idempotency-Key` e `{orderId,amount,method}` | `201` e `Location` para uma tentativa nova; `200` com a mesma tentativa quando a chave e a intenção se repetem |
| `GET /api/payments/{id}` | `200` com a tentativa do próprio cliente; `404` se ausente ou de outra conta |
| `GET /api/payments?orderId=...&page=0&size=20` | Página das tentativas do cliente para o pedido, por criação e UUID |

| `method` | `status` | `declineReason` |
| --- | --- | --- |
| `sim-card-approved` | `APPROVED` | `null` |
| `sim-card-declined` | `DECLINED` | `CARD_DECLINED` |
| `sim-card-insufficient-funds` | `DECLINED` | `INSUFFICIENT_FUNDS` |

A resposta contém `id`, `orderId`, `amount`, `currency` (`BRL`), `method`, `status`, `declineReason`, `simulated: true` e `createdAt`. Uma recusa é uma tentativa registrada com sucesso (`201`), não um erro HTTP. O cliente vem do `sub` do token; `customerId`, `id`, `status` e `currency` enviados no corpo são ignorados.

`amount` é um número JSON positivo em centavos, até `494999999950.50`, o mesmo limite do total de um pedido. Texto, zero, frações de centavo, métodos desconhecidos (inclusive números parecidos com cartões) e `orderId` inválido retornam `400` sem gravar.

## Idempotência e cobrança única

`Idempotency-Key` é obrigatório e deve ter de 8 a 100 letras, dígitos ou `. _ : -`; um UUID novo por intenção de pagamento é o recomendado. A chave pertence ao cliente:

- mesma chave com o mesmo `orderId`, valor e método devolve a tentativa armazenada com `200`, sem processar de novo;
- mesma chave com outro pedido, valor ou método retorna `422`, conforme o rascunho IETF do cabeçalho `Idempotency-Key`;
- depois de uma aprovação, nova tentativa do mesmo cliente para o pedido retorna `409`. Recusas não bloqueiam: o cliente pode tentar com outra chave.

O banco garante as regras sob concorrência. `UNIQUE (customer_id, idempotency_key)` impede duas tentativas com a mesma chave, e o índice único parcial `(customer_id, order_id) WHERE status = 'APPROVED'` impede uma segunda aprovação. Quando uma requisição perde a disputa, `PaymentService` consulta a chave novamente: se a vencedora tem a mesma intenção, devolve-a como repetição; caso contrário, mantém o conflito. Constraints também conferem formato da chave, faixa do valor e coerência entre método, status e motivo da recusa.

## Arquitetura

`PaymentAttempt`, `SimulatedPaymentMethod` e `IdempotencyKey` formam o domínio sem Spring; uma tentativa é decidida na criação e nunca muda. `PaymentService` usa a porta `PaymentRepository`; `JpaPaymentRepository` traduz as constraints para `IdempotencyKeyAlreadyUsedException` e `OrderAlreadyPaidException`. A migração `V1__create_payment_attempts.sql` cria a tabela; Flyway aplica e Hibernate apenas valida.

O serviço valida tokens com `payment.auth.secret=${USER_AUTH_SECRET}`, com as mesmas regras do [Order](resource-authorization.md), sem emiti-los. O Compose acrescenta `payment-db` (banco `payments`, volume `payment_postgres_data`, porta 5437) e fornece `PAYMENT_DB_*` e a chave ao serviço. Ao executar nativamente, inicie `payment-db` e forneça essas variáveis.

## Limites

O valor e o pedido informados não são conferidos com o Order Service nesta etapa; como `orderId` não é validado, a regra de cobrança única é por cliente. Não há estorno, captura separada, expiração, vínculo do pagamento ao estado do pedido nem bloqueio de entrega sem pagamento. Essas regras e a recuperação de falhas entre pedido, pagamento e entrega ficam para `feature/order-payment-integration`.

## Exemplo e validação

```powershell
$baseUrl = 'http://localhost:8080'
# $auth e $order como no exemplo de pedidos
$body = @{ orderId = $order.id; amount = $order.total; method = 'sim-card-approved' } | ConvertTo-Json
$key = [guid]::NewGuid().ToString()
$payment = Invoke-RestMethod -Method Post "$baseUrl/api/payments" -Headers ($auth + @{ 'Idempotency-Key' = $key }) `
    -ContentType 'application/json' -Body $body
Invoke-RestMethod "$baseUrl/api/payments?orderId=$($order.id)" -Headers $auth
```

```bash
(cd services/payment-service && ./mvnw verify)
pwsh -NoProfile -File scripts/smoke-route-demo.ps1 -CheckRecovery -CheckPersistence
```

Os testes cobrem domínio e casos de uso com relógio fixo, persistência com PostgreSQL 17 via Testcontainers (campos, centavos exatos, constraints e paginação), oito requisições simultâneas com a mesma chave gravando uma única tentativa e oito aprovações simultâneas com chaves diferentes cobrando o pedido uma vez, além de HTTP: `201`/`200`/`422`/`409`, `400` sem gravar, `401` antes de tocar dados e `404` para outra conta. O teste de concorrência revelou que uma repetição podia receber `409` quando a requisição vencedora já tinha aprovado o pedido; a nova consulta da chave após qualquer conflito corrigiu o caso.

Em 07/10/2026, no Ubuntu, `verify` de Payment passou com 115 testes, sem falhas, erros ou casos ignorados; antes da API, as suítes de domínio e persistência também passaram em três execuções seguidas. A demo, agora com doze containers, foi reconstruída e o smoke completo passou com `-CheckRecovery -CheckPersistence`, incluindo recusa, repetição, `422`, aprovação, `409`, `401`, `404` para a outra conta e a mesma tentativa após recriar os containers. `-UsersOnly`, `-OrderOnly` e `-CatalogOnly` também passaram. As suítes dos demais serviços não foram reexecutadas; seus serviços passaram no smoke.
