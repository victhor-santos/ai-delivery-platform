# Domínio de entregas

As regras de uma entrega ficam em Java puro, no pacote `com.victhor.delivery.delivery.domain`. O serviço possui [persistência PostgreSQL](delivery-persistence.md) e [API HTTP de entregas e entregadores](delivery-lifecycle.md). A integração automática com pedidos permanece na próxima etapa.

## Modelo

`Delivery` guarda UUID próprio, referência ao pedido, origem, destino, entregador atribuído, estado e horários. O UUID é gerado na criação. Uma entrega começa em `CREATED`, sem entregador e sem eventos posteriores.

Origem e destino são valores imutáveis: `DeliveryLocation` contém uma descrição de até 255 caracteres e um `GeoPoint`. A descrição pode identificar o local de coleta ou conter o endereço de destino; não é um endereço validado por geocodificação. As coordenadas seguem os limites de latitude [-90, 90] e longitude [-180, 180], inclusive, e rejeitam `NaN` e infinitos. `0,0` é válido.

As localizações não podem ser substituídas na entrega. Quando houver integração, elas serão copiadas dos dados disponíveis naquele momento. Atualizações posteriores no catálogo não devem alterar a origem de uma entrega existente.

`Courier` representa somente UUID e indicador ativo. O repositório e a API permitem cadastrar e consultar esses dados. A API gera o UUID e inicia o entregador ativo; ainda não há ativação/desativação. A atribuição no adaptador carrega o entregador do banco antes de chamar o domínio, que rejeita um entregador inativo.

## Ciclo de vida

| Operação | Estado exigido | Resultado |
| --- | --- | --- |
| `assign(courier, now)` | `CREATED` | `ASSIGNED`, com entregador ativo |
| `pickUp(now)` | `ASSIGNED` | `PICKED_UP` |
| `startTransit(now)` | `PICKED_UP` | `IN_TRANSIT` |
| `arrive(now)` | `IN_TRANSIT`, sem chegada registrada | Registra chegada e mantém `IN_TRANSIT` |
| `complete(now)` | `IN_TRANSIT`, com chegada registrada | `DELIVERED` |
| `cancel(now)` | `CREATED` ou `ASSIGNED` | `CANCELLED` |

O cancelamento não é permitido depois da coleta. Se já havia atribuição, o UUID do entregador e seu horário permanecem no histórico. Estados terminais não aceitam outros comandos. Não existe reatribuição nesta etapa.

Comandos repetidos são rejeitados, inclusive uma segunda chegada. A API preserva esse contrato e retorna `409` para comandos repetidos. A futura criação de entrega por pedido terá um contrato próprio para lidar com novas tentativas da integração.

Os horários usam `Instant`: `createdAt`, `updatedAt`, `assignedAt`, `pickedUpAt`, `departedAt`, `arrivedAt`, `deliveredAt` e `cancelledAt`. Cada evento exige um instante igual ou posterior ao último evento. Horários iguais são aceitos; horários anteriores e valores ausentes são rejeitados antes de qualquer alteração.

Chegada e conclusão são separadas para distinguir deslocamento de atendimento. O intervalo entre partida e chegada representa o trânsito total da entrega; ele não é, por si só, um rótulo de treinamento por trecho. A coleta de travessias por trecho terá uma etapa própria.

`Delivery` encapsula estado mutável, sem setters públicos. As alterações passam pelos comandos acima. As localizações permanecem imutáveis. A instância não deve ser compartilhada entre requisições concorrentes; controle de versão e transações ficam no adaptador de persistência.

`Delivery.restore` reconstrói dados persistidos usando as mesmas regras de transição e confere o estado e o último horário. Na reconstrução, o entregador representa quem estava atribuído naquele histórico; a atividade atual dele não altera uma entrega passada. A atribuição de uma nova entrega consulta o registro atual.

## Exemplo em Java

```java
var origin = new DeliveryLocation("Restaurante Central", new GeoPoint(-23.55, -46.63));
var destination = new DeliveryLocation("Rua das Flores, 42", new GeoPoint(-23.56, -46.64));
var delivery = Delivery.create(orderId, origin, destination, clock.instant());

delivery.assign(courier, clock.instant());
delivery.pickUp(clock.instant());
delivery.startTransit(clock.instant());
delivery.arrive(clock.instant());
delivery.complete(clock.instant());
```

O relógio é fornecido por quem coordena a operação; o domínio recebe o instante e não consulta o relógio do sistema. Os testes usam horários controlados, sem esperas.

## O que exige persistência e integração

O objeto isolado não consegue garantir uma entrega por pedido nem impedir que o mesmo entregador seja atribuído a duas entregas. O PostgreSQL agora garante isso com unicidade por pedido e índice único por entregador nas entregas em andamento. O domínio não verifica se o pedido está confirmado ou se o restaurante está ativo: essas verificações pertencem à integração entre serviços.

A persistência foi integrada pelo PR #11. `feature/delivery-lifecycle` acrescenta casos de uso e API sobre essa base. Depois, `feature/order-delivery-integration` conectará pedidos confirmados à criação de entregas, com snapshots e tratamento de repetição.

O Compose possui `delivery-db`, separado de `catalog-db` e `order-db`. As transições são síncronas e precisam ser confirmadas na transação antes de retornar. Mensageria e chamadas ao serviço Python continuam no roadmap.

## Testes

Na raiz do repositório:

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
```

Os testes verificam criação, coordenadas, descrições, atribuição, sequência completa, cancelamento, estados terminais, comandos repetidos e ordem temporal. A matriz de estados inclui entrega em trânsito antes e depois da chegada, além de cancelamentos com e sem atribuição. Operações rejeitadas precisam preservar todos os campos.

Os testes de domínio não precisam de banco. A suíte completa, incluindo contexto Spring e persistência, exige Docker e usa PostgreSQL via Testcontainers, sem acessar os volumes locais. Os testes HTTP cobrem o ciclo, validação de entrada, erros e conflitos concorrentes; consulte o [contrato da API](delivery-lifecycle.md).
