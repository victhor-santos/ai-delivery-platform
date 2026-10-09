# Entregas na interface web

Esta etapa continua o [checkout](frontend-checkout.md) a partir do pedido pago. A página do pedido (`/orders/{id}`) solicita a entrega, acompanha seus estados e mostra a rota prevista na cidade sintética. Um painel de simulação operacional avança o ciclo até a conclusão.

## Fluxo

| Parte da página | Chamadas |
| --- | --- |
| Solicitação | `POST /api/orders/{id}/delivery` (token do dono) e nova leitura do pedido |
| Acompanhamento | `GET /api/deliveries/by-order/{orderId}` a cada 5 segundos, até `DELIVERED` ou `CANCELLED` |
| Rota | `GET` e `POST /api/deliveries/{id}/route` |
| Simulação operacional | `POST /api/deliveries/couriers`, `/assign`, `/pick-up`, `/start-transit`, `/arrive`, `/complete` e `/cancel` |

A seção de entrega só aparece em pedidos `CONFIRMED`. A [integração entre pedidos e entregas](order-delivery-integration.md) define as regras do servidor.

## Solicitação e acompanhamento

A solicitação é idempotente. Rede ou `5xx` deixam o resultado desconhecido: o pedido já pode ter `deliveryRequestedAt` sem que o Delivery Service tenha a entrega. Nesse caso a consulta por pedido responde `404`, e a página mostra "Tentar novamente", que repete a mesma chamada e recupera ou cria a entrega sem duplicá-la. Recusas como restaurante inativo (`409`) mostram o `detail`.

A linha do tempo exibe criação, atribuição, coleta, saída, chegada e conclusão com os horários do servidor. Uma entrega cancelada termina no cancelamento. Uma falha passageira na consulta mantém a última entrega visível e tenta de novo no próximo ciclo. Uma consulta iniciada antes de um comando não substitui a resposta mais recente, comparada por `updatedAt`.

## Rota

O mapa é um SVG dos nós e ligações do grafo `synthetic-city-v1`, projetados a partir das coordenadas. Ele destaca a coleta, o destino e a última rota salva. Ao lado ficam percurso (por exemplo `A → B → C`), distância, tempo previsto, partida considerada, versões do modelo e do grafo e a origem sintética dos dados. As ligações estão em `frontend/src/checkout/syntheticCity.ts`, junto dos nós, e precisam acompanhar o arquivo do grafo. O mapa não mostra o sentido dos trechos de mão única.

Antes da partida (`CREATED`, `ASSIGNED` e `PICKED_UP`), "Calcular rota" planeja com a partida no instante atual, que serve só de contexto para o tráfego simulado. Recalcular substitui o plano anterior. Indisponibilidade do serviço de rotas preserva o plano salvo. `422`, como destino sem caminho, mostra o motivo. Depois da partida o plano continua visível, sem novo cálculo.

Entregas com coleta ou destino fora da cidade sintética não oferecem mapa nem cálculo. A página explica que elas não têm rota, em vez de fazer uma chamada que falharia com `OUTSIDE_ROUTE_COVERAGE`.

## Simulação operacional

Ainda não há contas de entregador, restaurante ou operador, e os endpoints de entregas continuam públicos no servidor (veja a [autorização dos recursos](resource-authorization.md)). Para a demonstração chegar ao fim, o painel identificado como "Simulação operacional" executa o próximo comando do ciclo:

| Estado | Comando |
| --- | --- |
| `CREATED` | Atribuir entregador: cria um entregador e o atribui |
| `ASSIGNED` | Registrar coleta |
| `PICKED_UP` | Sair para entrega |
| `IN_TRANSIT` sem chegada | Registrar chegada |
| `IN_TRANSIT` com chegada | Concluir entrega |

O cancelamento aparece em `CREATED` e `ASSIGNED`. Cada atribuição cria um entregador novo porque o serviço não lista entregadores e um entregador ocupado recusaria outra entrega. Se a atribuição falhar depois do cadastro, o entregador criado fica sem uso. Um `409` mostra o `detail` e consulta a entrega de novo, pois ela pode ter mudado em outra aba.

Restringir esses comandos a papéis autorizados exige identidade de operador e entregador nos serviços. Isso ficou registrado como evolução posterior no [roadmap](roadmap.md). As observações de travessia por trecho continuam disponíveis pela API e pelo smoke, mas não pela interface.

## Limites

- O cliente acompanha a entrega por consulta periódica; não há eventos em tempo real.
- O mapa é esquemático. Não há posição do entregador entre os nós nem mapas reais.

## Validação

Em 08/10/2026, no Ubuntu:

- `npm run lint`, `npm test` (12 arquivos, 70 testes) e `npm run build` passaram;
- `docker compose --profile demo up -d --build --wait` iniciou os treze containers saudáveis, e o seed não criou nada novo;
- no Chrome, pela porta 3000, um pedido pago da Cantina da Praça para o ponto C mostrou "Solicitar entrega"; a solicitação criou a entrega e exibiu a linha do tempo e o painel operacional. A extensão do navegador desconectou em seguida, e o restante não foi verificado visualmente;
- pela porta 3000, com chamadas HTTP nessa mesma entrega: plano ausente (`404`), cálculo A → B → C com 2,9 km e dados sintéticos, atribuição, coleta, saída, chegada, conclusão e `409` ao repetir a conclusão;
- `scripts/smoke-route-demo.ps1` passou no fluxo completo.

Os serviços Java e Python não mudaram nesta etapa, e suas suítes não foram reexecutadas.
