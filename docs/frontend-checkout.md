# Checkout na interface web

Esta etapa leva a [interface web](frontend-foundation.md) do login até o pedido pago: restaurantes, cardápio, quantidades, resumo de valores, destino, criação do pedido e [pagamento simulado](order-payment-integration.md). O acompanhamento da entrega está nas [entregas na interface web](frontend-deliveries.md).

## Fluxo

| Página | Caminho | Chamadas |
| --- | --- | --- |
| Restaurantes | `/restaurants?page=N` | `GET /api/catalog/restaurants?page=N&size=20` |
| Cardápio e carrinho | `/restaurants/{id}` | restaurante, cardápio inteiro, `GET /api/users/auth/me` e endereços do perfil |
| Pedido | `/orders/{id}` | `GET /api/orders/{id}`, pagamento e cancelamento |

Todas as páginas exigem sessão. O catálogo é público no servidor, mas comprar exige token, e manter a navegação inteira autenticada evita um meio-termo sem utilidade na demonstração.

## Restaurantes e cardápio

- A lista é paginada de 20 em 20, na ordem do catálogo (nome e UUID). Restaurantes inativos aparecem marcados como "Fechado"; a página deles mostra o cardápio sem carrinho.
- O cardápio é lido inteiro, em páginas de 100 (o máximo do catálogo). Itens indisponíveis continuam visíveis, marcados, sem controle de quantidade.
- O carrinho segue os limites do Order Service: até 50 itens distintos, cada um com quantidade de 1 a 99. Ele vive na página e se perde ao sair dela.
- Os subtotais e o total são calculados em centavos inteiros e formatados em BRL. É uma estimativa: o servidor consulta o catálogo de novo ao criar o pedido, e a página do pedido mostra os valores salvos por ele.

## Destino

O destino é um endereço salvo no perfil ou um ponto da cidade sintética.

- **Endereços salvos:** os 100 primeiros do perfil (`/api/users/{id}/addresses`), ordenados por rótulo. Um endereço novo pode ser cadastrado ali mesmo, com rótulo, texto e coordenadas, e já fica selecionado. Não há geocodificação.
- **Cidade sintética:** os sete nós do grafo `synthetic-city-v1` (A a G), com o texto "Ponto sintético X". A lista fica em `frontend/src/checkout/syntheticCity.ts` e precisa acompanhar o arquivo do grafo se ele mudar.

O planejamento de rotas só aceita pontos a até 1 metro de um nó do grafo. Um endereço fora disso cria o pedido normalmente, mas recebe um aviso: a entrega não terá rota. Por isso os pontos sintéticos são a opção que funciona até o fim do fluxo.

## Pedido e pagamento

`POST /api/orders` envia apenas restaurante, destino e IDs com quantidades. O cadastro não é idempotente, então o botão fica bloqueado enquanto a requisição está em andamento. Recusas do servidor, como item indisponível ou restaurante fechado (`409`), aparecem com o `detail` e permitem nova tentativa.

A página do pedido mostra estado, restaurante, destino, itens com os preços preservados e o total. Pedidos anteriores aos itens aparecem com "Valor desconhecido" e não podem ser pagos.

O pagamento usa um dos métodos simulados (`sim-card-approved`, `sim-card-declined`, `sim-card-insufficient-funds`) e uma `Idempotency-Key` nova por intenção:

| Resultado | Interface |
| --- | --- |
| `APPROVED` | Pedido confirmado; pagamento e cancelamento somem |
| `DECLINED` | Motivo da recusa; a próxima tentativa usa outra chave e pode trocar o método |
| Rede ou `5xx` | Resultado desconhecido; a chave e o método ficam guardados e o botão vira "Retomar pagamento" |
| `409`, `422` e outros `4xx` | `detail` do servidor; a chave é descartada e o pedido é consultado de novo |

Uma intenção pendente só avança quando o cliente repete a mesma chave (não há expiração nem reconciliação no servidor). Por isso a chave fica no `localStorage`, em `delivery.payment.{orderId}`: recarregar ou fechar a aba não deixa o pedido preso. Ela só é usada enquanto o pedido tem `paymentRequestedAt`, e é apagada quando o resultado chega ou o pedido sai de `CREATED`. Uma intenção iniciada em outro navegador não pode ser retomada aqui; a página explica isso e não oferece pagamento nem cancelamento. A chave não é segredo: sem o token do dono, ela não dá acesso ao pedido.

O cancelamento aparece para pedidos `CREATED` sem pagamento em andamento.

## Dados de demonstração

O catálogo do Compose começa vazio. Com a demonstração em execução:

```powershell
pwsh -NoProfile -File scripts/seed-demo-catalog.ps1
```

O script cria, pelo Gateway, três restaurantes com coleta em nós do grafo (Cantina da Praça em A, Sushi Paulista em F e Padaria Central em D) e seus cardápios, definidos em `scripts/demo-catalog.json`. Um item nasce indisponível para mostrar esse estado. Restaurantes e itens são reconhecidos pelo nome: repetir o script só cria o que falta e preserva alterações. `-GatewayUrl` aceita outra porta. Também funciona no Windows PowerShell 5.1.

## Limites

- Não há listagem de pedidos por cliente no Order Service; um pedido é reaberto pelo endereço da sua página.
- O carrinho não é persistido e atende um restaurante por vez.
- Catálogo e endereços não são atualizados enquanto a página está aberta; preços alterados nesse intervalo aparecem no pedido criado.

## Validação

Em 08/10/2026, no Ubuntu:

- `npm run lint`, `npm test` (11 arquivos, 54 testes) e `npm run build` passaram, inclusive sobre `git archive HEAD`;
- `docker compose --profile demo up -d --build --wait` iniciou os treze containers saudáveis, e o seed criou o catálogo na primeira execução e não criou nada na segunda;
- pela porta 3000, com chamadas HTTP: cadastro, login, endereços, pedido com dois itens e snapshots, pagamento recusado, aprovado e repetido com a mesma chave, `409` ao cancelar o pedido pago, solicitação de entrega com destino no ponto C e cancelamento de um pedido não pago;
- no Chrome: cadastro, cardápio, carrinho com total, destino sintético, pedido, recusa por saldo insuficiente e aprovação, sem erros no console;
- `scripts/smoke-route-demo.ps1` passou no fluxo completo.

Os serviços Java e Python não mudaram nesta etapa, e suas suítes não foram reexecutadas.
