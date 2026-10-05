# Itens e valores do pedido

O cadastro de pedidos consulta o cardápio por HTTP e salva uma composição comercial imutável. Nome, preço e total são definidos pelo servidor. O catálogo continua responsável por preços e disponibilidade atuais; Orders guarda os valores usados na criação, em banco próprio.

## Cadastro e resposta

`POST /api/orders` exige `restaurantId`, `destination` e `items`:

```json
{
  "restaurantId": "22222222-2222-4222-8222-222222222222",
  "destination": {"address": "Rua Central, 42", "latitude": -23.561, "longitude": -46.656},
  "items": [{"menuItemId": "11111111-1111-4111-8111-111111111111", "quantity": 2}]
}
```

Os UUIDs devem corresponder a um restaurante e item cadastrados. A resposta `201` conserva `Location: /api/orders/{id}` e os campos de lifecycle anteriores; acrescenta:

```json
{
  "items": [{
    "menuItemId": "11111111-1111-4111-8111-111111111111",
    "name": "Prato do dia",
    "quantity": 2,
    "unitPrice": 25.90,
    "lineTotal": 51.80
  }],
  "total": 51.80,
  "currency": "BRL"
}
```

Consultas, confirmação e cancelamento retornam a mesma composição. `items` preserva a ordem da seleção enviada. O subtotal é `unitPrice × quantity`; o total é a soma dos subtotais. Não há taxas, descontos, edição de itens ou pagamento nesta etapa. O ciclo existente e a solicitação idempotente de entrega continuam em funcionamento.

O cliente informa somente IDs e quantidades para a composição. Campos extras são ignorados, inclusive nome, preço unitário, moeda, subtotais, total, UUID do pedido, estado e timestamps. O `POST` continua sem chave de idempotência: repetir uma criação válida pode criar outro pedido.

## Validação e dinheiro

- De 1 a 50 itens distintos por pedido; IDs duplicados são rejeitados.
- Quantidade inteira de 1 a 99 por item. Ausência, `null`, strings, booleanos e números decimais, incluindo `2.0`, são rejeitados.
- Restaurante deve existir e estar ativo. Todos os itens devem existir nesse restaurante e estar disponíveis.
- Preços do catálogo devem ser números JSON em BRL entre `0.01` e `99999999.99`, sem fração de centavo. Zeros adicionais são aceitos; valores não são arredondados.

`OrderItem` usa `BigDecimal`, normaliza o preço unitário para duas casas e calcula o subtotal sem ponto flutuante. `OrderPricing` copia a lista, rejeita duplicados e valida o total contra a soma dos snapshots. O maior total suportado pelos limites é `494999999950.50`, que cabe no `NUMERIC(14,2)` do banco.

Entrada inválida retorna `400`; seleção de restaurante/item inexistente, inativo ou indisponível retorna `409`. Timeout, falha HTTP, JSON inválido, recurso com identidade trocada, moeda incorreta ou dados inválidos na resposta do catálogo retornam `503`. Pedido inexistente continua retornando `404`. Erros usam `application/problem+json`, sem expor detalhes internos.

## Consulta ao catálogo e transação

`OrderService` valida a seleção antes de qualquer chamada remota. `CatalogLookup` é uma porta própria de compra, separada de `RestaurantLookup`, que obtém a localização para entregar. Uma compra não exige localização de coleta; essa informação continua obrigatória ao solicitar a entrega.

`HttpCatalogLookup` reutiliza o cliente e as propriedades existentes `order.integration.catalog-url` e `order.integration.timeout-ms`. Consulta o restaurante e cada item pelo caminho aninhado. Valida IDs, pertencimento, moeda BRL, tipos e dados monetários antes de retornar. Seu leitor JSON preserva `BigDecimal` sem alterar o mapper compartilhado. Interrupções preservam o estado de interrupção da thread.

São realizadas 1 + N consultas sequenciais, fora da transação de persistência. O timeout é individual por requisição, de 5 segundos por padrão; no limite de 50 itens, esses tempos podem acumular até aproximadamente 255 segundos. A criação não oferece um snapshot atômico de todo o cardápio: mudanças entre consultas podem produzir preços observados em instantes diferentes. A resposta confirma os valores efetivamente salvos. Um contrato de consulta em lote poderá ser avaliado se o volume exigir.

Somente após resolver toda a seleção o serviço salva o pedido. O adaptador JPA grava a linha de pedido, total e itens na mesma transação. Falha de catálogo ou da transação impede confirmação de criação e não deixa uma composição parcial. Confirmar, cancelar ou registrar intenção de entrega não consulta novamente os preços. Não há expiração de cotação nesta etapa.

## Persistência e registros antigos

`V3__add_order_items.sql` acrescenta `orders.total` e cria `order_items`, com posição de 0 a 49, UUID do item, nome, quantidade e preço unitário. A chave primária é `(order_id, item_position)` e há unicidade por `(order_id, menu_item_id)`. A FK referencia somente `orders`, com exclusão em cascata dos filhos do agregado; não há exclusão pública do pedido.

`OrderEntity` usa `@ElementCollection` de `OrderItemEmbeddable` e `@OrderColumn` para preservar a ordem. O mapeamento para domínio ocorre dentro da transação; a coleção não é exposta na API. As transições alteram apenas estado e horários e conservam `@Version` para concorrência. O subtotal é derivado e não ocupa outra coluna.

Pedidos anteriores à V3 ficam sem composição e com valor desconhecido: API retorna `items: []`, `total: null` e `currency: null`. Destino, estado, horários, versão e intenções de entrega existentes são preservados. Esses registros continuam consultáveis e seguem o lifecycle anterior; nenhum preço é criado retroativamente. Novos `POST`s sem itens recebem `400`.

O banco protege referências internas, unicidade, tamanhos, posições, quantidades e intervalos monetários. A consistência entre total e snapshots é verificada pelo domínio no cadastro e ao restaurar o agregado; uma escrita SQL que desalinhe essas informações é detectada na leitura. `NUMERIC` pode arredondar entradas feitas diretamente por SQL, enquanto a aplicação rejeita frações de centavo antes de salvar. A moeda é fixa em BRL e não possui coluna própria.

## Testar

Com Docker disponível:

```powershell
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml clean verify
```

Com catálogo, pedidos, seus bancos e Gateway iniciados, sem depender de Delivery ou Python:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -OrderOnly
```

O smoke cria uma seleção com duas unidades a `29.90` e confere total `59.80` em BRL. Depois altera nome, preço e disponibilidade no catálogo, consulta e confirma o pedido, exigindo os snapshots originais. Cada execução deixa registros de demonstração no banco escolhido. `-OrderOnly` e `-CatalogOnly` são mutuamente exclusivos e não aceitam `-CheckRecovery` ou `-CheckPersistence`; essas opções pertencem ao fluxo completo.

Em 05/10/2026, `clean verify` de Orders passou com 270 testes, sem falhas, erros ou casos ignorados, incluindo valores exatos, dados remotos inválidos, falhas sem gravação parcial, rollback, ordenação, transições, concorrência e preservação de registros V2 na migration. O JAR executável foi gerado.

O modo `-OrderOnly` e o fluxo completo com `-CheckRecovery -CheckPersistence` também passaram em um projeto Compose isolado. A validação conferiu os preços preservados na consulta e confirmação do pedido, entrega, rota, observações simuladas/CSV, recuperação do Python e itens, total e moeda após recriar os containers. As sete imagens foram construídas e os dez containers ficaram prontos. O ambiente descartável e seus volumes próprios foram removidos ao final; os bancos, volumes e `.env` de desenvolvimento foram preservados. Veja os detalhes em [demonstração integrada](route-intelligence-compose.md).

## Continuação

A próxima feature implementará cadastro e perfis de usuários, seguida de autenticação e autorização conforme o [roadmap](roadmap.md). Pagamentos serão explicitamente simulados em uma etapa própria; confirmação manual do pedido continua sem significado financeiro.
