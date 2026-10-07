# Autorização de perfis, endereços e pedidos

Esta etapa vincula os recursos do cliente à identidade do [token de acesso](authentication.md). User e Order validam o mesmo Bearer JWT HS256 e só atendem o dono do recurso. Catálogo, entregas e pagamentos continuam públicos; papéis operacionais pertencem a etapas próprias.

## Regras de acesso

| Serviço | Público | Exige token | Dono do recurso |
| --- | --- | --- | --- |
| User | `POST /api/users/auth/register`, `POST /api/users/auth/login`, `GET /api/users/ping`, health/info | Demais caminhos de `/api/users/**` | `{id}`/`{userId}` do caminho igual ao `sub` do token |
| Order | `GET /api/orders/ping`, health/info | Todos os caminhos de `/api/orders/**` | `customerId` do pedido igual ao `sub` do token |

Token ausente, malformado, vencido, assinado com outra chave ou com emissor, audiência, subject ou duração inválidos retorna `401` em `application/problem+json`, com `WWW-Authenticate: Bearer`, antes de qualquer consulta ou alteração. Um token válido aplicado ao perfil, endereço ou pedido de outra pessoa retorna `404` com a mesma mensagem de um recurso inexistente. Assim a resposta não revela se o UUID existe nem chama catálogo, Delivery ou transições persistidas.

O identificador do cliente vem somente do `sub` validado. Campos como `customerId`, `userId` ou `id` enviados no corpo continuam ignorados. O `POST /api/users`, que criava perfis sem senha, foi removido: a conta passa a ser criada apenas por `/api/users/auth/register`. Um token válido nesse caminho antigo recebe `404`.

## Pedidos com cliente

`Order` passou a ter `customerId`, obrigatório em `Order.create` e preservado em confirmação, cancelamento e solicitação de entrega. A resposta HTTP inclui `customerId`. `OrderService` e `OrderDeliveryService` recebem o cliente autenticado e verificam `isPlacedBy` antes de ler ou transicionar; como o dono não muda depois da criação, a verificação não abre condição de corrida com a transição. Os repositórios e o contrato com Delivery não mudaram.

A migration `V4__add_order_customers.sql` adiciona `customer_id` anulável. Pedidos anteriores ficam preservados com valor nulo e não pertencem a ninguém: continuam no banco, mas recebem `404` por qualquer token. Atribuí-los a um cliente exigiria uma prova de propriedade que os dados antigos não têm.

## Chave compartilhada

Order valida tokens com `order.auth.secret=${USER_AUTH_SECRET}`, sem emitir tokens. `AccessTokenVerifier` repete as verificações do User: HS256, emissor `https://delivery-order-system.local`, audiência `delivery-order-system`, subject UUID canônico, emissão/vencimento, duração máxima de 15 minutos e tolerância de 30 segundos. O Compose fornece a mesma chave aos dois serviços. Como HS256 usa uma chave simétrica, qualquer serviço que a recebe pode emitir tokens; chaves assimétricas e rotação continuam pendentes.

O Gateway apenas encaminha `Authorization`. Ao executar Order nativamente, forneça `USER_AUTH_SECRET` junto às variáveis do banco. Os testes usam a fixture pública de `src/test/resources/application-test.properties`, carregada somente pelo perfil `test`.

## Limites desta etapa

Não há papéis de restaurante, entregador ou administrador. Por isso a confirmação do pedido ainda é feita pelo próprio cliente, e o cadastro de restaurantes, cardápios, entregadores, rotas e observações segue público. Não existe listagem de pedidos por cliente. Revogação de tokens, refresh e limitação de tentativas continuam como descritos na [autenticação](authentication.md).

## Validação

```bash
(cd services/user-service && bash mvnw verify)
(cd services/order-service && bash mvnw verify)
docker compose --profile demo up -d --build --wait --wait-timeout 240
pwsh -NoProfile -File scripts/smoke-route-demo.ps1 -CheckRecovery -CheckPersistence
```

Os testes de User cobrem `401` antes dos casos de uso para tokens ausentes, malformados, vencidos ou de outra chave, `404` para perfil/endereço alheio sem chamar os serviços e a remoção do cadastro sem senha. Os de Order cobrem o verificador de tokens (assinatura, emissor, audiência, subject, emissão futura e duração), `401` sem alterar dados, `customerId` vindo do token e ignorado no corpo, `404` para pedido de outro cliente nas quatro operações sem chamar catálogo nem gravar solicitação de entrega, e a migração V3→V4 preservando pedidos e itens com cliente nulo.

Em 06/10/2026, no Ubuntu, `verify` passou com 355 testes em User e 293 em Order, sem falhas, erros ou casos ignorados. A demo foi reconstruída com o Compose do repositório, e o smoke completo passou com `-CheckRecovery -CheckPersistence` no PowerShell 7.6, assim como `-UsersOnly`, `-OrderOnly` e `-CatalogOnly`. O token foi renovado antes das consultas após recriar os containers. No banco de desenvolvimento restaurado, a V4 preservou os pedidos anteriores sem cliente; os novos pedidos da demonstração registraram o UUID da conta. As suítes de Catalog, Delivery, Payment, Gateway e Python não foram reexecutadas nesta feature; seus serviços passaram no smoke.
