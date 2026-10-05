# Cardápio por restaurante

O Catalog Service mantém itens de cardápio em seu próprio PostgreSQL. Cada item pertence a um restaurante existente e possui UUID, nome, descrição opcional, preço em reais e disponibilidade. O Order Service consome esses itens por HTTP na criação de pedidos e guarda quantidades, nomes e preços preservados, junto ao total. Veja o [contrato de itens dos pedidos](order-items.md).

## Contrato HTTP

O prefixo é `/api/catalog/restaurants/{restaurantId}/menu-items`, tanto na porta 8082 quanto pelo Gateway na porta 8080.

| Operação | Entrada | Resultado |
| --- | --- | --- |
| `POST` no prefixo | `name`, `price` e `description` opcional | `201`, `Location` relativo e item disponível |
| `GET` no prefixo | `page=0` e `size=20` opcionais | `200` com `{content, page, size, totalElements, totalPages}` |
| `GET /{id}` | UUID do item | `200` com o item |
| `PUT /{id}` | `name`, `price`, `available` e `description` opcional | `200` com todos os detalhes substituídos |

Exemplo de resposta:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "restaurantId": "22222222-2222-4222-8222-222222222222",
  "name": "Prato do dia",
  "description": "Arroz, feijão e salada",
  "price": 25.90,
  "currency": "BRL",
  "available": true
}
```

O servidor gera o UUID e define `available=true` no cadastro. A moeda é sempre `BRL`. Campos extras no corpo são ignorados, incluindo tentativas de escolher identidade, restaurante, moeda ou disponibilidade inicial. O restaurante vem do caminho da requisição e não pode ser transferido pelo `PUT`.

O nome é obrigatório, com até 120 caracteres após retirar espaços nas extremidades. A descrição aceita até 1000 caracteres depois da mesma normalização; ausência, `null` ou texto vazio vira `null` na resposta. Nomes repetidos são permitidos.

`price` deve ser um número JSON entre `0.01` e `99999999.99`, com precisão de centavos. O domínio usa `BigDecimal` e normaliza duas casas sem arredondar: `25`, `25.9` e `25.900` são aceitos; `25.901` é rejeitado. Strings, booleanos, preços nulos, negativos ou zero são inválidos. `available` no `PUT` exige um booleano JSON explícito; omissão, `null`, strings e números são rejeitados.

O `PUT` substitui os campos editáveis. Omitir a descrição remove a descrição anterior. Repetir o mesmo corpo mantém o resultado, sem criar outro item. Não há exclusão física; um item pode ser marcado como indisponível e continua nas consultas. A listagem inclui os indisponíveis para administração do cardápio. Restaurantes inativos também podem administrar seus itens; disponibilidade do item não torna o restaurante ativo.

Todas as consultas e atualizações filtram simultaneamente restaurante e item. Restaurante inexistente, item inexistente ou item pertencente a outro restaurante retornam `404`. Um restaurante existente sem itens retorna uma página vazia. JSON, UUIDs, campos ou paginação inválidos retornam `400`. Erros seguem `application/problem+json`; falhas inesperadas retornam `500` com detalhe genérico.

`page` começa em zero. `size` fica entre 1 e 100, com padrão 20; o offset `page * size` deve caber em `2147483647`. A ordenação é por `name ASC, id ASC`, incluindo desempate para nomes repetidos. Alterações entre consultas podem mudar as páginas; a paginação não mantém um snapshot entre requisições.

## Executar pelo Gateway

Com catálogo, seu banco e Gateway iniciados conforme o [README](../README.md):

```powershell
$baseUrl = 'http://localhost:8080'
$restaurant = Invoke-RestMethod -Method Post "$baseUrl/api/catalog/restaurants" `
    -ContentType 'application/json' -Body '{"name":"Cantina Demo"}'
$menuUrl = "$baseUrl/api/catalog/restaurants/$($restaurant.id)/menu-items"
$item = Invoke-RestMethod -Method Post $menuUrl -ContentType 'application/json' `
    -Body '{"name":"Prato do dia","description":"Arroz e feijao","price":25.90}'
Invoke-RestMethod "${menuUrl}?page=0&size=20"
Invoke-RestMethod "$menuUrl/$($item.id)"
Invoke-RestMethod -Method Put "$menuUrl/$($item.id)" -ContentType 'application/json' `
    -Body '{"name":"Prato especial","price":29.90,"available":false}'
```

O smoke existente também aceita execução apenas do catálogo:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -CatalogOnly
```

`-GatewayUrl` permite outra porta. Esse modo exige somente catálogo, PostgreSQL e Gateway. Cria restaurantes e item, altera preço/disponibilidade e confere consultas, paginação e isolamento entre restaurantes. Os registros ficam no banco escolhido. Para testar também a compra, com Order e seu banco disponíveis, use `-OrderOnly`: o smoke cria um pedido com duas unidades, altera o preço e a disponibilidade no catálogo e confere que a confirmação mantém nomes e valores originais. Os modos `-CatalogOnly` e `-OrderOnly` são exclusivos e não podem ser combinados com `-CheckRecovery` ou `-CheckPersistence`, que exercitam a demonstração completa. Sem um desses modos, o smoke segue do cardápio e pedido com itens para o fluxo de entregas e rotas.

## Camadas e persistência

`MenuItem` valida os dados sem dependências de Spring, JPA ou HTTP. `MenuItemService` coordena os casos de uso pelas portas `RestaurantRepository` e `MenuItemRepository`; `MenuItemPage` representa a paginação. Controller e DTOs mantêm o contrato público sem expor a entidade JPA.

`JpaMenuItemRepository` implementa a porta com transações curtas. O cadastro verifica a existência do restaurante e salva o item; a chave estrangeira reforça a integridade. A atualização busca pela identidade completa, altera os detalhes e confirma a transação antes de retornar. Um UUID ausente não é criado por uma atualização. Não há controle de versão ou ETag: substituições concorrentes seguem a última gravação confirmada, como a atualização de localização já existente.

`V3__create_menu_items.sql` cria `menu_items`, com chave estrangeira para `restaurants` sem exclusão em cascata, nome obrigatório, descrição opcional, `NUMERIC(10,2)` para preço e disponibilidade obrigatória. O índice `(restaurant_id, name, id)` atende ao filtro e à ordenação. Restaurantes antigos e suas localizações são preservados; nenhum item é inventado pela migration.

O banco impõe limites de tamanho, nome não vazio e preço positivo dentro do intervalo. `NUMERIC(10,2)` pode arredondar frações de centavo enviadas diretamente por SQL; a rejeição dessas frações ocorre no domínio antes de qualquer gravação feita pela aplicação. A moeda é fixa no contrato e não ocupa uma coluna. Não há consulta ao banco de outro serviço nem relacionamento JPA bidirecional com restaurantes.

## Validação da feature de cardápio

Em 05/10/2026, `clean verify` do catálogo passou com 262 testes, sem falhas ou casos ignorados, e gerou o JAR executável. São 131 novos casos cobrindo regras monetárias, normalização, casos de uso, HTTP, isolamento por restaurante, PostgreSQL, constraints e atualização sem criação acidental. A migration V2→V3 foi testada com restaurantes existentes, e o teste V1→V2 mantém seu destino explícito.

Gateway passou em `clean verify` com 6 testes. O smoke `-CatalogOnly` também passou com os JARs de catálogo e Gateway em execução, usando um PostgreSQL descartável sem volumes. Confirmou criação, atualização, consultas, paginação e rejeição de acesso/alteração pelo restaurante errado; uma consulta SQL conferiu a gravação. Processos e container de teste foram encerrados sem alterar os bancos de desenvolvimento.

A demonstração completa com Python e as opções de recuperação/recriação de containers não foi reexecutada na feature de cardápio. Naquela etapa, o modo completo recebeu as mesmas verificações de cardápio, incluindo consulta do item após reinício. A execução integrada ocorreu depois, na feature de itens dos pedidos; veja o [registro de validação em 05/10/2026](route-intelligence-compose.md#validação-de-itens-de-pedidos-em-05102026).

## Integração com pedidos

Orders recebe apenas IDs e quantidades dos itens, consulta o catálogo por uma porta HTTP, exige restaurante ativo e itens disponíveis e salva nomes/preços unitários como snapshots junto ao total em BRL. Alterações posteriores de nome, preço ou disponibilidade no cardápio não recalculam pedidos já criados nem impedem sua confirmação. Itens de outro restaurante são rejeitados no fluxo de compra. A coleta continua opcional para criar um pedido e é exigida apenas na primeira solicitação de entrega.

A criação usa uma consulta de restaurante e uma consulta para cada item, todas fora da transação do pedido. Não há snapshot atômico entre essas consultas, reserva de estoque ou acesso ao banco do catálogo pelo Order. Ausência de restaurante/item, restaurante inativo ou item indisponível resulta em `409` no cadastro do pedido; timeout, falha HTTP ou resposta inválida resulta em `503`. Veja [limites e exemplos da compra](order-items.md). Autenticação e autorização continuam previstas em etapas próprias do [roadmap](roadmap.md).
