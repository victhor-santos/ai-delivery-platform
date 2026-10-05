# PostgreSQL local do catálogo

O Catalog Service cadastra e consulta restaurantes e seus itens de cardápio por HTTP, persiste os dados com JPA no PostgreSQL e usa Flyway para controlar o schema. Também atualiza a localização de coleta dos restaurantes e os dados dos itens.

Execute os comandos abaixo na raiz do repositório, com Docker e Docker Compose v2 instalados e o Docker em execução:

```powershell
docker version
docker info
docker compose version
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

O `.env.example` contém valores fictícios para uso local. O `.env` é ignorado pelo Git. Configure `CATALOG_DB_USERNAME`, `CATALOG_DB_PASSWORD` e `CATALOG_DB_PORT` antes de iniciar o banco. Use entradas simples `CHAVE=valor`, sem aspas ou `export`.

A porta padrão é 5432. Se já estiver ocupada, escolha outra em `CATALOG_DB_PORT` e ajuste também a porta em `CATALOG_DB_URL`. Por exemplo, use `5433` e `jdbc:postgresql://localhost:5433/catalog`. Não interrompa um banco existente para liberar a porta.

```powershell
docker compose up -d --wait catalog-db
docker compose ps
```

O serviço `catalog-db` do Compose inicia PostgreSQL 17, com banco `catalog`, porta publicada em `127.0.0.1`, volume nomeado `catalog_postgres_data` e health check com `pg_isready`. O banco de pedidos usa outro serviço e volume.

## Iniciar o catálogo

Na raiz do repositório:

```powershell
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

O diretório explícito permite carregar o `.env` da raiz. A aplicação recebe `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` e `CATALOG_DB_PASSWORD` pelo arquivo ou por variáveis de ambiente. A senha é obrigatória. Pela IDE, configure a raiz como diretório de trabalho ou forneça essas variáveis ao processo.

Flyway aplica as migrations pendentes na inicialização. `V1__create_restaurants.sql` cria a tabela `restaurants` e o índice `(name, id)`. `V2__add_restaurant_pickup_location.sql` adiciona `pickup_latitude` e `pickup_longitude` como `DOUBLE PRECISION`, sem alterar os registros existentes. O Hibernate usa `ddl-auto=validate`, e `open-in-view` fica desabilitado. Não edite migrations já aplicadas: mudanças futuras devem usar uma nova versão.

`V3__create_menu_items.sql` cria `menu_items`, com UUID, `restaurant_id`, nome, descrição opcional, preço e disponibilidade. A chave estrangeira exige restaurante existente e impede sua exclusão enquanto houver itens, sem exclusão em cascata. O índice `(restaurant_id, name, id)` acompanha a ordem da listagem por restaurante. A migração preserva restaurantes e coordenadas e inicia o cardápio vazio, sem criar produtos de exemplo.

O preço é `NUMERIC(10,2)`, positivo e limitado a `99999999.99`. A moeda do contrato é BRL. Nome e descrição têm limites de 120 e 1000 caracteres; nome vazio é rejeitado também pelo banco, e a descrição ausente usa `NULL`. PostgreSQL pode arredondar casas excedentes ao converter uma escrita direta para `NUMERIC(10,2)`. A API e o domínio usam `BigDecimal` e rejeitam frações de centavo antes da escrita, sem arredondamento silencioso.

As coordenadas podem estar ambas vazias ou ambas preenchidas. Constraints do PostgreSQL rejeitam pares incompletos, valores fora dos limites geográficos, `NaN` e infinitos. A ausência de localização é representada por `NULL`, sem usar coordenadas fictícias.

Com a aplicação iniciada:

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health
Invoke-RestMethod http://localhost:8082/api/catalog/ping
```

O health deve retornar `UP`, incluindo a verificação de conexão com o banco. O ping mantém a resposta `{"service":"catalog-service","status":"ok"}`.

## Testes e build

Com Docker funcionando, execute na raiz:

```powershell
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml clean verify
```

Os testes de integração criam bancos PostgreSQL 17 descartáveis via Testcontainers, separados do volume local. Não precisam de Compose ou `.env`. Verificam a inicialização do contexto, as migrations Flyway, constraints, dados gravados pelos casos de uso, consultas e paginação. O cardápio também tem testes de preço, descrição opcional, disponibilidade, atualização sem criação acidental, isolamento por restaurante e migração V2→V3 preservando dados existentes. Os testes HTTP exercitam cadastro, atualização, consultas, entradas inválidas, recursos inexistentes, ping e Actuator; um teste MVC verifica que falhas internas não expõem detalhes na resposta. Os testes de domínio e casos de uso também continuam na suíte, que falha se Docker estiver indisponível.

Em 05/10/2026, `clean verify` aprovou os 262 testes do catálogo, incluindo os 131 novos testes desta feature de cardápio, e gerou o JAR executável.

O build gera `services/catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar`. As versões de JPA, driver PostgreSQL, Flyway e Testcontainers são geridas pelo Spring Boot 4.1.1.

## Integração dos casos de uso com JPA

`RestaurantService` depende da interface `RestaurantRepository`. A configuração Spring em `infrastructure` fornece o serviço com o adaptador `JpaRestaurantRepository`, que converte entre `Restaurant` e `RestaurantEntity`. O domínio e os casos de uso permanecem independentes de Spring e JPA.

`MenuItemService` usa `MenuItemRepository` para os itens e `RestaurantRepository` para exigir restaurante existente. `MenuItemConfiguration` fornece o serviço com o adaptador `JpaMenuItemRepository`; `MenuItemEntity` guarda a referência ao restaurante como UUID, sem associação JPA. Nenhuma tabela de outro serviço é consultada.

O adaptador abre uma transação de escrita para cadastrar ou atualizar a localização, e transações de leitura para consultar. A atualização busca a entidade e altera suas coordenadas na mesma transação; o JPA grava a mudança ao confirmar a operação. O caso de uso recebe o restaurante atualizado pela porta de persistência. Requisições simultâneas de localização seguem a última gravação confirmada. Futuros fluxos com várias gravações relacionadas precisarão de uma transação que englobe todas elas.

A listagem ordena por `name ASC, id ASC`, permitindo nomes repetidos com desempate por UUID. O limite de tamanho é 100, e a resposta da camada de aplicação inclui conteúdo, página, tamanho e total de registros.

Para itens, consultas e atualizações usam o par `restaurantId` e UUID do item. O `PUT` substitui nome, descrição, preço e disponibilidade na mesma transação, preservando UUID e restaurante. Um item ausente ou pertencente a outro restaurante retorna `404`, sem criar ou transferir registros. A escrita usa a entidade carregada na transação e o dirty checking do JPA. Não há `@Version` nessa atualização: em gravações concorrentes, prevalece a última confirmada. A listagem filtra o restaurante e mantém a mesma ordenação por nome e UUID, incluindo itens indisponíveis.

## API de restaurantes

| Requisição | Resposta |
| --- | --- |
| `POST /api/catalog/restaurants` com `{"name":"Cantina Central"}` | `201`, cabeçalho `Location` e corpo `{id, name, active, pickupLocation}` |
| `GET /api/catalog/restaurants/{id}` | `200` com o restaurante ou `404` |
| `GET /api/catalog/restaurants?page=0&size=20` | `200` com `{content, page, size, totalElements, totalPages}` |
| `PUT /api/catalog/restaurants/{id}/pickup-location` com `{latitude, longitude}` | `200` com o restaurante atualizado ou `404` |

O nome deve ser uma string não vazia, com até 120 caracteres após remover espaços nas extremidades. UUID e `active=true` são definidos pelo servidor; campos adicionais no JSON são ignorados. A página começa em zero, e o tamanho padrão é 20, com valores permitidos de 1 a 100. O produto `page * size` deve ser no máximo `2147483647`, limite de offset do JPA. A ordenação é fixa por nome e UUID, ambos ascendentes.

Entrada inválida retorna `400`; UUID válido sem restaurante correspondente retorna `404`. Os erros usam `application/problem+json`, com `status`, `title` e `detail`. Falhas inesperadas retornam `500` com mensagem genérica, sem detalhes internos.

`pickupLocation` é opcional no cadastro e aparece como `null` nas respostas quando ausente. Se informado, deve conter latitude e longitude numéricas e finitas, nos limites inclusivos de -90 a 90 e -180 a 180, respectivamente. O `PUT` exige o par completo, preserva os demais dados do restaurante e é idempotente. Os testes também verificam a migração de um banco com dados na V1 para a V2, a preservação dos registros e a rejeição de coordenadas inválidas pela API e pelo banco.

```powershell
$baseUrl = 'http://localhost:8082'
$created = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/api/catalog/restaurants" -ContentType 'application/json' -Body '{"name":"Cantina Central"}'
$created.StatusCode
$created.Headers['Location']
$restaurant = $created.Content | ConvertFrom-Json
Invoke-RestMethod "$baseUrl/api/catalog/restaurants/$($restaurant.id)"
Invoke-RestMethod "$baseUrl/api/catalog/restaurants?page=0&size=20"
```

Com o Gateway em execução, a rota existente `/api/catalog/**` atende essas mesmas chamadas pela porta 8080. O `Location` é relativo (`/api/catalog/restaurants/{id}`), para funcionar nas duas portas.

## API de cardápio

Os endpoints ficam sob `/api/catalog/restaurants/{restaurantId}/menu-items`, diretamente na porta 8082 ou pelo Gateway na 8080. Cadastro gera UUID e `available=true`; o `PUT /{id}` exige nome, preço e disponibilidade, com descrição opcional. O preço é um número JSON em BRL, com precisão de centavos; strings numéricas são rejeitadas. O contrato de resposta inclui `currency: "BRL"`. A paginação tem os mesmos limites da listagem de restaurantes. Não há exclusão de itens nesta etapa.

Veja o [contrato, exemplos e limitações do cardápio](restaurant-menu.md) e a [compra com itens e preços preservados](order-items.md). Com catálogo e Gateway disponíveis, `scripts/smoke-route-demo.ps1 -CatalogOnly` verifica cadastro, consulta, paginação, atualização e isolamento por restaurante sem depender dos demais serviços. O smoke completo também verifica o cardápio; `-CheckPersistence` acrescenta a consulta do item após recriar os containers.

## Preservar os dados

Para parar preservando os dados:

```powershell
docker compose stop catalog-db
```

Não remova volumes nem dados locais. Alterar as credenciais no `.env` não altera as de um volume já inicializado; mantenha a configuração correspondente ao banco existente.
