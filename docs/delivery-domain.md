# Domínio de entregas

Esta etapa define as regras de uma entrega em Java puro, no pacote `com.victhor.delivery.delivery.domain`. Ainda não há cadastro de entregas por HTTP, persistência ou integração com pedidos. O serviço mantém apenas seu ping e os endpoints do Actuator.

## Modelo

`Delivery` guarda UUID próprio, referência ao pedido, origem, destino, entregador atribuído, estado e horários. O UUID é gerado na criação. Uma entrega começa em `CREATED`, sem entregador e sem eventos posteriores.

Origem e destino são valores imutáveis: `DeliveryLocation` contém uma descrição de até 255 caracteres e um `GeoPoint`. A descrição pode identificar o local de coleta ou conter o endereço de destino; não é um endereço validado por geocodificação. As coordenadas seguem os limites de latitude [-90, 90] e longitude [-180, 180], inclusive, e rejeitam `NaN` e infinitos. `0,0` é válido.

As localizações não podem ser substituídas na entrega. Quando houver integração, elas serão copiadas dos dados disponíveis naquele momento. Atualizações posteriores no catálogo não devem alterar a origem de uma entrega existente.

`Courier` representa somente UUID e indicador ativo. Ainda não há cadastro ou consulta de entregadores. A atribuição recebe esse valor e rejeita um entregador inativo; o serviço de aplicação futuro será responsável por carregar seu estado confiável.

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

Comandos repetidos são rejeitados, inclusive uma segunda chegada. Esse é o contrato do domínio atual; ainda não há política de idempotência HTTP. A futura criação de entrega por pedido terá um contrato próprio para lidar com novas tentativas da integração.

Os horários usam `Instant`: `createdAt`, `updatedAt`, `assignedAt`, `pickedUpAt`, `departedAt`, `arrivedAt`, `deliveredAt` e `cancelledAt`. Cada evento exige um instante igual ou posterior ao último evento. Horários iguais são aceitos; horários anteriores e valores ausentes são rejeitados antes de qualquer alteração.

Chegada e conclusão são separadas para distinguir deslocamento de atendimento. O intervalo entre partida e chegada representa o trânsito total da entrega; ele não é, por si só, um rótulo de treinamento por trecho. A coleta de travessias por trecho terá uma etapa própria.

`Delivery` encapsula estado mutável, sem setters públicos. As únicas alterações possíveis passam pelos comandos acima. As localizações permanecem imutáveis. A instância não deve ser compartilhada entre requisições concorrentes; controle de versão e transações pertencem à futura persistência.

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

O objeto isolado não consegue garantir uma entrega por pedido nem impedir que o mesmo entregador seja atribuído a duas entregas. Essas regras precisarão de consulta, transação e restrições no banco. Também não verifica se o pedido está confirmado ou se o restaurante está ativo: essas verificações pertencem à integração entre serviços.

A próxima branch, `feature/delivery-lifecycle`, acrescentará persistência, migrations, controle de concorrência, cadastro mínimo de entregador e API. Depois, `feature/order-delivery-integration` conectará pedidos confirmados à criação de entregas, com snapshots e tratamento de repetição.

Não foi adicionado banco ao Compose nesta etapa. `catalog-db` e `order-db` continuam independentes. Não há razão para introduzir `@Async` nas transições do objeto: elas precisam ser validadas e, futuramente, confirmadas na transação antes da resposta. Mensageria e chamadas ao serviço Python continuam no roadmap.

## Testes

Na raiz do repositório:

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
```

Os testes verificam criação, coordenadas, descrições, atribuição, sequência completa, cancelamento, estados terminais, comandos repetidos e ordem temporal. A matriz de estados inclui entrega em trânsito antes e depois da chegada, além de cancelamentos com e sem atribuição. Operações rejeitadas precisam preservar todos os campos.

O teste de contexto Spring continua na suíte. Nenhum desses testes precisa de Docker, PostgreSQL ou credenciais. A API de negócio e sua integração com banco serão testadas quando existirem.
