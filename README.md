# Delivery Order System

Sistema de pedidos para delivery em Java e Spring Boot, desenvolvido como projeto de portfólio em AI Engineering. O repositório reúne um API Gateway, cinco serviços Java e um serviço Python de roteamento.

## Estado atual

O User Service cadastra e consulta perfis e endereços em PostgreSQL próprio. O nome e os endereços podem ser atualizados; o e-mail é normalizado, único e imutável nesta etapa. Os endereços são consultados pelo par usuário/endereço. O cadastro com senha, login e consulta da identidade por JWT estão implementados. Perfis, endereços e pedidos exigem Bearer token e só atendem o próprio dono. Veja [perfis, validação e persistência de usuários](docs/user-profiles.md), [autenticação e configuração da chave](docs/authentication.md) e [autorização dos recursos](docs/resource-authorization.md).

O Catalog Service cadastra e consulta restaurantes e seus itens de cardápio em PostgreSQL, com migrations Flyway, validação de entrada, paginação e testes de integração com Testcontainers. Um restaurante tem UUID, nome obrigatório e indicador `active`; o cadastro gera o UUID e inicia o restaurante ativo.

A localização de coleta pode ser informada no cadastro ou atualizada depois. Ela contém latitude e longitude e é salva no PostgreSQL. Restaurantes sem localização continuam válidos no catálogo; esse dado é exigido na primeira solicitação de entrega de um pedido.

Cada item do cardápio pertence a um restaurante e tem nome, descrição opcional, preço em BRL e disponibilidade. A API permite cadastrar, consultar, listar e substituir seus dados, com valores monetários exatos e isolamento por restaurante. Veja o [contrato do cardápio](docs/restaurant-menu.md).

O Order Service cria, consulta, confirma e cancela pedidos em um PostgreSQL próprio. Cada pedido pertence ao cliente do token usado na criação, e somente ele pode consultá-lo ou alterá-lo. Na criação, consulta restaurante e itens no catálogo, exige disponibilidade e salva nomes, preços unitários e quantidades, junto ao total em BRL. Mudanças posteriores no cardápio preservam esses valores. Um pedido confirmado pode solicitar entrega, com validação da coleta no catálogo, snapshots persistidos e criação idempotente no Delivery. Pagamento ainda não foi implementado. Veja os [itens e preços dos pedidos](docs/order-items.md) e a [integração entre pedidos e entregas](docs/order-delivery-integration.md).

O Delivery Service cria e consulta entregas e entregadores por HTTP, com persistência em PostgreSQL. A API permite atribuir entregador, registrar coleta, partida, chegada, conclusão e cancelamento antes da coleta. Consulta Python, salva o último plano de rota e registra [travessias simuladas por trecho](docs/delivery-segment-observations.md), com snapshots da previsão e exportação CSV por disponibilidade temporal. Veja também o [planejamento de rotas](docs/delivery-route-integration.md), o [contrato e os exemplos da API](docs/delivery-lifecycle.md), o [domínio de entregas](docs/delivery-domain.md) e a [configuração do banco](docs/delivery-persistence.md).

Os cinco serviços Java mantêm seus endpoints `/ping` e roteamento HTTP pelo Gateway. As seis aplicações Java expõem Actuator. Pagamentos ainda têm apenas a estrutura inicial. Autorização dos recursos e RabbitMQ permanecem em etapas posteriores.

Route Intelligence possui aplicação FastAPI, configuração por ambiente, `/health`, testes e dependências travadas. Já calcula rotas em um grafo sintético com Dijkstra e tempos fixos de referência, por um comando de terminal. A [API de rotas previstas](docs/intelligent-routing-api.md) combina o modelo em lote com Dijkstra. Delivery já consulta essa API e persiste o plano por entrega. Veja a [execução do serviço Python](docs/route-intelligence-foundation.md) e a [demonstração de roteamento](docs/road-graph.md).

O gerador offline já produz observações sintéticas por trecho, com seed, timestamps de disponibilidade, schema de features, partições temporais por cenário e manifesto com checksums. Os dados completos são gerados localmente e ficam fora do Git. Veja [como gerar e conferir o dataset](docs/route-segment-dataset.md).

O treinamento offline compara Dummy, regressão linear, Random Forest e referência física, seleciona pela validação e avalia o modelo salvo no teste reservado. O predictor em lote valida entradas e tempos e é carregado uma vez por processo na API. Veja [treinamento, resultados e artefatos](docs/route-segment-model.md), incluindo a validação nativa no Windows e a compatibilidade por plataforma.

## Evolução para AI Engineering

A consulta `POST /api/routes/fastest` retorna o caminho de menor tempo previsto, com prontidão dos recursos e falhas controladas. Delivery integra e persiste o plano e associa as previsões aos tempos observados em travessias simuladas. O perfil `demo` do Compose executa o fluxo completo em containers, com modelo Linux montado somente para leitura e treinamento offline. Java continua cuidando das transações. A [avaliação offline dos CSVs](docs/segment-observation-evaluation.md) valida os exports e produz métricas das previsões armazenadas, com cortes de disponibilidade e proveniência. A [preparação das observações](docs/segment-observation-dataset.md) cria partições temporais por entrega, com manifesto e carregamento validado próprios. O [treinamento dessas observações](docs/segment-observation-training.md) seleciona um modelo na validação e o avalia no teste reservado, com bundle exclusivo para uso offline. Publicação desse modelo na API e interface web são evoluções posteriores.

A [integração de rotas com Delivery](docs/delivery-route-integration.md) está implementada. Grafo e observações são fictícios e não representam ruas ou trânsito reais. Python não é necessário para executar os serviços Java.

- [Arquitetura de Route Intelligence e domínio de Delivery](docs/route-intelligence.md).
- [Contrato HTTP Java ↔ Python](docs/route-intelligence-contract.md).
- [Dados, prevenção de leakage e avaliação dos modelos](docs/route-intelligence-data.md).
- [Roadmap por feature branch](docs/roadmap.md).

## Demonstração em containers

Com Docker usando containers Linux, execute na raiz:

Se o `.env` já existe, complete as entradas `USER_DB_*` a partir do `.env.example` antes de executar o Compose, preservando a configuração existente.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/initialize-auth-secret.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-route-model.ps1
docker compose --profile demo up -d --build --wait --wait-timeout 240
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1
```

Gateway atende em `http://localhost:8080`; Python em `http://localhost:8000`. As portas Java 8081–8085 são internas neste perfil. Sem `demo`, o Compose continua iniciando somente bancos. Não é necessário instalar Java ou Python na máquina para esta demonstração. O script verifica um modelo existente e só treina se não houver bundle; a API nunca treina ao iniciar.

O perfil utiliza quatro bancos e sete aplicações, totalizando onze containers. Cada JVM usa heap entre 64 e 384 MB e dimensiona seus pools para dois processadores, evitando que as seis aplicações dimensionem memória/threads pelo total da VM Docker. `DEMO_JAVA_TOOL_OPTIONS` permite ajustar esses parâmetros somente no Compose; execução nativa permanece independente.

Veja o [guia do Compose](docs/route-intelligence-compose.md) para configuração, compatibilidade do modelo, testes de queda/recuperação e preservação dos volumes. O smoke cria registros de demonstração no banco. Os comandos de execução nativa abaixo continuam disponíveis.

Para verificar cadastro com senha, login, identidade autenticada, perfis e endereços, com User, seu banco e Gateway disponíveis, execute `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -UsersOnly`. Para verificar restaurantes e cardápios, use `-CatalogOnly`; para incluir criação, consulta, confirmação e preservação de preços dos pedidos, acrescente Order e seu banco e use `-OrderOnly`. Os três modos são exclusivos e não podem ser combinados com `-CheckRecovery` ou `-CheckPersistence`. O smoke completo verifica perfis e endereços, itens e preços dos pedidos e o fluxo de entrega/rota, incluindo sua preservação após recriar containers quando solicitado.

## Requisitos para desenvolvimento e execução nativa

- JDK 21, com `JAVA_HOME` configurado e `java` disponível no terminal.
- PowerShell para os exemplos abaixo.
- Docker com suporte a containers Linux e Docker Compose v2, em execução, para os bancos locais e os testes de integração de usuários, catálogo, pedidos e entregas.
- Acesso à internet na primeira execução para baixar Maven, dependências e a imagem PostgreSQL.
- Para Route Intelligence: Python 3.12+ e `uv`; a versão de referência é 3.12. A preparação está no [guia do serviço Python](docs/route-intelligence-foundation.md).

### Linux

Os comandos PowerShell desta documentação têm equivalentes diretos em Bash. Use `./mvnw` no lugar de `mvnw.cmd` e `/` como separador; os wrappers são versionados como executáveis. O usuário precisa acessar o daemon Docker sem `sudo`, inclusive para os testes com Testcontainers (`docker info` deve funcionar no terminal da IDE). Para isso, adicione-o ao grupo `docker` uma vez e entre novamente na sessão; com o pacote snap, crie o grupo e reinicie o daemon:

```bash
sudo addgroup --system docker   # o pacote apt já cria o grupo
sudo usermod -aG docker "$USER"
sudo snap disable docker && sudo snap enable docker   # somente no Docker instalado por snap
```

`newgrp docker` aplica o grupo apenas ao terminal atual; depois de sair e entrar na sessão, ele vale para todos. Para o `.env` e a chave JWT:

```bash
[ -f .env ] || cp .env.example .env
chmod 600 .env
scripts/initialize-auth-secret.sh
docker compose up -d --wait catalog-db order-db delivery-db user-db
./services/user-service/mvnw -f services/user-service/pom.xml clean verify
./services/user-service/mvnw -f services/user-service/pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

O script Bash segue as mesmas regras do `.ps1`: preserva uma chave existente, recusa entradas duplicadas e não exibe o valor. Um `.env` copiado do Windows pode manter finais de linha CRLF; Compose e Spring os aceitam, mas `sed -i 's/\r$//' .env` os normaliza. Os scripts de preparação do modelo e de smoke continuam em PowerShell e exigem `pwsh` (PowerShell 7) no Linux, por exemplo `sudo snap install powershell --classic`, chamado como `pwsh -NoProfile -File scripts/smoke-route-demo.ps1`. Em 06/10/2026 o smoke completo, com `-CheckRecovery -CheckPersistence`, passou no Ubuntu com PowerShell 7.6. O script de preparação do modelo não foi executado no Linux, porque o bundle Linux já existia; confira a propriedade dos arquivos gerados nos bind mounts do modelo.

Cada aplicação Java inclui o Maven Wrapper; não é necessário instalar Maven separadamente. Versões da base: Spring Boot 4.1.1 e Spring Cloud 2025.1.3 no Gateway. Usuários, catálogo, pedidos e entregas usam as versões de Spring Data JPA, PostgreSQL JDBC, Flyway e Testcontainers geridas pelo Spring Boot; os bancos locais e os testes usam PostgreSQL 17.

## Estrutura

```text
deliveryOrderSystem/
├── api-gateway/
├── services/
│   ├── user-service/
│   ├── catalog-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── delivery-service/
│   └── route-intelligence-service/
├── compose.yaml
├── Dockerfile.java
├── .env.example
├── scripts/
└── docs/
    └── architecture.md
```

Cada aplicação Java possui `pom.xml`, Maven Wrapper, código e testes próprios. Não há um build Maven agregador na raiz. O serviço Python possui `pyproject.toml`, `uv.lock` e testes com pytest, sem dependência do build Maven.

## Portas e endpoints

| Aplicação | Porta | Endpoint de demonstração |
| --- | --- | --- |
| API Gateway | 8080 | Encaminha os endpoints abaixo |
| User Service | 8081 | `/api/users/ping` |
| Catalog Service | 8082 | `/api/catalog/ping` |
| Order Service | 8083 | `/api/orders/ping` |
| Payment Service | 8084 | `/api/payments/ping` |
| Delivery Service | 8085 | `/api/deliveries/ping` |
| Route Intelligence | 8000 | `/health` e `POST /api/routes/fastest`, acesso direto |

As portas da tabela são as portas de execução nativa e as portas internas dos containers. Todas as aplicações Java expõem `/actuator/health` e `/actuator/info` em sua própria porta. O endpoint `info` pode retornar `{}`. O health do Gateway informa a saúde dele, não a de todos os serviços. Os endpoints de saúde de usuários, catálogo, pedidos e entregas incluem a conexão com seus bancos. O `/health` do Python retorna `200 UP` somente com grafo, modelo e tráfego compatíveis; sem esses recursos retorna `503 DOWN`. Python não é encaminhado pelo Gateway.

## PostgreSQL e configuração local

Execute os comandos desta documentação a partir da raiz do repositório. Confira se o Docker está disponível e se o daemon está funcionando:

```powershell
docker version
docker info
docker compose version
```

Crie a configuração local sem sobrescrever um arquivo existente:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/initialize-auth-secret.ps1
```

O `.env.example` contém somente valores de desenvolvimento. Ajuste o `.env` antes de iniciar o banco; ele é ignorado pelo Git e não deve conter credenciais de produção. Use entradas simples `CHAVE=valor`, sem `export` ou aspas, pois o Compose e o Spring leem o mesmo arquivo.

| Variável | Uso local |
| --- | --- |
| `CATALOG_DB_URL` | JDBC do catálogo; padrão `jdbc:postgresql://localhost:5432/catalog` |
| `CATALOG_DB_USERNAME` | Usuário do banco; padrão `catalog` |
| `CATALOG_DB_PASSWORD` | Senha local obrigatória, definida no `.env` ou no ambiente |
| `CATALOG_DB_PORT` | Porta publicada pelo Compose; padrão `5432` |
| `ORDER_DB_URL` | JDBC de pedidos; padrão `jdbc:postgresql://localhost:5434/orders` |
| `ORDER_DB_USERNAME` | Usuário do banco de pedidos; padrão `orders` |
| `ORDER_DB_PASSWORD` | Senha local obrigatória para iniciar o banco e o serviço de pedidos |
| `ORDER_DB_PORT` | Porta de pedidos publicada pelo Compose; padrão `5434` |
| `DELIVERY_DB_URL` | JDBC de entregas; padrão `jdbc:postgresql://localhost:5435/deliveries` |
| `DELIVERY_DB_USERNAME` | Usuário do banco de entregas; padrão `deliveries` |
| `DELIVERY_DB_PASSWORD` | Senha local obrigatória para iniciar o banco e o serviço de entregas |
| `DELIVERY_DB_PORT` | Porta de entregas publicada pelo Compose; padrão `5435` |
| `USER_DB_URL` | JDBC de usuários; padrão `jdbc:postgresql://localhost:5436/users` |
| `USER_DB_USERNAME` | Usuário do banco de usuários; padrão `users` |
| `USER_DB_PASSWORD` | Senha local obrigatória para iniciar o banco e o serviço de usuários |
| `USER_DB_PORT` | Porta de usuários publicada pelo Compose; padrão `5436` |
| `USER_AUTH_SECRET` | Chave JWT obrigatória, Base64 de pelo menos 32 bytes; gerada pelo script acima |
| `DEMO_JAVA_TOOL_OPTIONS` | Opções opcionais das seis JVMs no perfil `demo`; padrão `-Xms64m -Xmx384m -XX:ActiveProcessorCount=2` |

Se a porta 5432 já estiver ocupada, escolha outra porta em `CATALOG_DB_PORT` e ajuste também `CATALOG_DB_URL`. A mesma regra vale para pedidos, entregas e usuários. Se você já possui `.env`, acrescente as entradas `ORDER_DB_*`, `DELIVERY_DB_*` e `USER_DB_*` que faltarem em relação a `.env.example`, sem substituir os valores existentes. Variáveis de ambiente podem sobrescrever os valores do arquivo. O Compose exige as senhas dos quatro bancos e `USER_AUTH_SECRET` ao resolver sua configuração, mesmo que o comando selecione somente um deles. O script de chave preserva um valor já configurado e nunca o exibe; mantenha a mesma chave entre reinícios.

O Compose define `catalog-db`, `order-db`, `delivery-db` e `user-db`, com `postgres:17-alpine`, portas publicadas em `127.0.0.1` e health check `pg_isready`. Os volumes são separados: `catalog_postgres_data`, `order_postgres_data`, `delivery_postgres_data` e `user_postgres_data`. Para iniciar os quatro:

```powershell
docker compose up -d --wait catalog-db order-db delivery-db user-db
docker compose ps
```

Os bancos se chamam `catalog`, `orders`, `deliveries` e `users`. Cada aplicação com persistência importa opcionalmente `.env` do diretório de execução e exige sua senha para se conectar. Ao iniciar cada serviço, Flyway aplica as migrations em seu `src/main/resources/db/migration`; Hibernate apenas valida o schema (`ddl-auto=validate`). `open-in-view` fica desabilitado.

O volume preserva os dados entre reinícios. Alterar usuário ou senha no `.env` não altera as credenciais de um banco já inicializado; use os valores correspondentes ao volume existente. Para interromper o banco preservando seus dados:

```powershell
docker compose stop catalog-db
docker compose stop order-db
docker compose stop delivery-db
docker compose stop user-db
```

Não remova o volume para executar ou testar esta etapa. Os testes usam um banco descartável separado, criado pelo Testcontainers.

## Executar localmente

Para trabalhar com restaurantes e cardápios, basta iniciar `catalog-db` e abrir dois terminais na raiz:

```powershell
# Terminal 1: o diretório explícito faz o Spring carregar o .env da raiz
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
# Terminal 2
.\api-gateway\mvnw.cmd -f .\api-gateway\pom.xml spring-boot:run
```

Os demais serviços continuam disponíveis, um comando por terminal:

```powershell
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
.\services\payment-service\mvnw.cmd -f .\services\payment-service\pom.xml spring-boot:run
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

O User Service exige `USER_AUTH_SECRET` e `user-db` em execução, Order exige `USER_AUTH_SECRET` e `order-db`, e Delivery exige `delivery-db`. Use `Ctrl+C` em cada terminal para encerrar a aplicação. As portas precisam estar livres. Ao executar usuários, catálogo, pedidos ou entregas pela IDE, configure o diretório de trabalho como a raiz do repositório ou forneça as variáveis de ambiente ao processo.

## Cadastrar perfis e endereços

A API atende diretamente em `http://localhost:8081` ou pelo Gateway em `http://localhost:8080`. O perfil é criado por `POST /api/users/auth/register`; as demais requisições exigem `Authorization: Bearer <token>` do próprio usuário, e recursos de outra pessoa retornam `404`:

| Requisição | Resultado |
| --- | --- |
| `GET /api/users/{id}` | `200` com perfil ou `404` |
| `PUT /api/users/{id}/profile` com `{name}` | `200`, preservando UUID e e-mail |
| `POST /api/users/{userId}/addresses` | `201`, endereço criado e cabeçalho `Location` |
| `GET /api/users/{userId}/addresses?page=0&size=20` | `200` com página de endereços |
| `GET/PUT /api/users/{userId}/addresses/{addressId}` | `200` com endereço consultado ou substituído; `404` se ausente ou de outro usuário |

O endereço recebe `label`, `address`, `latitude` e `longitude`. Nome, rótulo e endereço são obrigatórios, com limites de 120, 80 e 255 caracteres. E-mail é normalizado para minúsculas e deve seguir o formato ASCII definido no contrato; duplicidade retorna `409`. Formato válido não comprova propriedade do e-mail. Pedidos continuam recebendo seu próprio destino, sem referência a um endereço salvo.

Veja [contrato, exemplos e testes de usuários](docs/user-profiles.md). Para criar uma conta com senha, use `POST /api/users/auth/register`; `POST /api/users/auth/login` retorna um Bearer JWT de 15 minutos e `GET /api/users/auth/me` consulta a identidade autenticada. Perfis sem credenciais não recebem senha padrão nem podem ser assumidos por outro cadastro. Catálogo e entregas ainda são públicos. Veja [autenticação](docs/authentication.md) e [autorização](docs/resource-authorization.md).

## Criar e acompanhar entregas

A API atende diretamente em `http://localhost:8085` ou pelo Gateway em `http://localhost:8080`, sob `/api/deliveries`. Ela inclui cadastro de entregadores em `/api/deliveries/couriers`, criação de entregas, consulta por entrega/pedido e comandos do ciclo. Os horários são gerados pelo servidor; comandos repetidos retornam `409`.

Delivery também oferece `POST` e `GET /api/deliveries/{id}/route` para planejar e consultar a rota. A nova previsão exige Python com modelo compatível; falhas preservam o plano anterior. Veja [configuração e exemplos de planejamento](docs/delivery-route-integration.md).

Veja [o contrato, exemplos completos e validação de entregas](docs/delivery-lifecycle.md). Para criar a entrega com dados do pedido e do catálogo, use a [solicitação de entrega de um pedido confirmado](docs/order-delivery-integration.md).

## Criar e acompanhar pedidos

Diretamente em `http://localhost:8083` ou pelo Gateway em `http://localhost:8080`:

| Requisição | Resultado |
| --- | --- |
| `POST /api/orders` | `201`, pedido criado e cabeçalho `Location` |
| `GET /api/orders/{id}` | `200` com o pedido ou `404` |
| `POST /api/orders/{id}/confirm` | `200` com estado `CONFIRMED`; `409` se cancelado |
| `POST /api/orders/{id}/cancel` | `200` com estado `CANCELLED` |
| `POST /api/orders/{id}/delivery` | `200` com `orderId`, `deliveryId` e estado da entrega; exige pedido confirmado |

O cadastro recebe `restaurantId`, `destination` com `address`, `latitude` e `longitude`, e `items` com `menuItemId` e `quantity`. São aceitos de 1 a 50 itens distintos, com quantidades inteiras de 1 a 99. O servidor consulta o catálogo e calcula os valores; preços enviados pelo cliente não definem o total. Ausência de restaurante ou item, restaurante inativo ou item de outro restaurante/indisponível resulta em `409`; falhas do catálogo retornam `503` sem detalhes internos. A resposta inclui os itens com nomes e preços preservados, `lineTotal`, `total` e `currency: "BRL"`. Pedidos anteriores à migração mantêm `items: []`, `total: null` e `currency: null`.

A confirmação é manual e não representa aprovação de pagamento. Repetir uma confirmação ou cancelamento já aplicado preserva os timestamps e a composição do pedido. Conflitos de atualização retornam `409`; consulte o pedido antes de tentar novamente.

Veja [o fluxo, exemplos completos e testes de pedidos](docs/orders.md).

## Cadastrar e consultar restaurantes

O mesmo contrato atende diretamente em `http://localhost:8082` e pelo Gateway em `http://localhost:8080`. A rota existente `/api/catalog/**` preserva o caminho completo.

| Requisição | Resultado |
| --- | --- |
| `POST /api/catalog/restaurants` com `{"name":"Restaurante Central"}` | `201 Created`, corpo com `id`, `name`, `active`, `pickupLocation` e cabeçalho `Location` |
| `GET /api/catalog/restaurants/{id}` | `200 OK` com o restaurante ou `404 Not Found` |
| `GET /api/catalog/restaurants?page=0&size=20` | `200 OK` com página de restaurantes |
| `PUT /api/catalog/restaurants/{id}/pickup-location` | `200 OK` com o restaurante e a localização atualizada |

O nome é obrigatório, não pode conter apenas espaços e aceita até 120 caracteres. Espaços nas extremidades são removidos. UUID e estado ativo são definidos pelo servidor. `pickupLocation` é opcional no cadastro; quando informado, exige os dois números: `latitude` entre -90 e 90 e `longitude` entre -180 e 180. Os limites são inclusivos, e strings numéricas, `NaN` e infinitos não são aceitos.

Exemplo completo em PowerShell, executado primeiro diretamente e depois pelo Gateway. Cada execução cria um restaurante no banco local:

```powershell
foreach ($baseUrl in @('http://localhost:8082', 'http://localhost:8080')) {
    $created = Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri "$baseUrl/api/catalog/restaurants" `
        -ContentType 'application/json' -Body '{"name":"Restaurante Central"}'
    $created.StatusCode # 201
    $created.Headers['Location'] # /api/catalog/restaurants/{UUID}
    $restaurant = $created.Content | ConvertFrom-Json
    $restaurant # id, name, active=true e pickupLocation
    Invoke-RestMethod "$baseUrl/api/catalog/restaurants/$($restaurant.id)"
    Invoke-RestMethod "$baseUrl/api/catalog/restaurants?page=0&size=20"
}
```

A página começa em zero; `page` deve ser não negativo e `size` deve estar entre 1 e 100. Os valores padrão são `page=0` e `size=20`. Para respeitar o limite de offset do JPA, `page * size` não pode ultrapassar `2147483647`. A ordem é fixa por `name ASC, id ASC`; UUID desempata nomes iguais. Não há parâmetro de ordenação configurável. Uma página sem resultados retorna `content: []`.

Formato da resposta paginada:

```json
{
  "content": [
    {"id":"9d6c1458-0c80-45cd-a9c0-b420f25c8246","name":"Restaurante Central","active":true,"pickupLocation":null}
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Entrada inválida retorna `400 Bad Request`, incluindo nome ausente/em branco, JSON inválido, UUID malformado e paginação fora dos limites. Um UUID válido que não existe retorna `404 Not Found`. Os erros usam `application/problem+json` com `status`, `title` e `detail`; podem incluir os campos padrão `type` e `instance`. Falhas inesperadas retornam `500` com mensagem genérica, sem detalhes internos.

### Localização de coleta

Cadastre um restaurante com localização diretamente ou pelo Gateway:

```powershell
$baseUrl = 'http://localhost:8080'
$body = '{"name":"Cantina Central","pickupLocation":{"latitude":-23.5505,"longitude":-46.6333}}'
$restaurant = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/catalog/restaurants" -ContentType 'application/json' -Body $body
```

Para informar ou substituir a localização de um restaurante existente:

```powershell
$location = '{"latitude":-22.9068,"longitude":-43.1729}'
Invoke-RestMethod -Method Put -Uri "$baseUrl/api/catalog/restaurants/$($restaurant.id)/pickup-location" -ContentType 'application/json' -Body $location
Invoke-RestMethod "$baseUrl/api/catalog/restaurants/$($restaurant.id)"
```

O `PUT` recebe as duas coordenadas e devolve o restaurante atualizado. Repetir a mesma requisição mantém o resultado, sem criar outro restaurante. Nome, UUID e estado ativo são preservados. Nesta etapa não há remoção de localização. Entrada inválida retorna `400`, e restaurante inexistente retorna `404`.

As consultas por UUID e por página incluem `pickupLocation`, que será `null` nos restaurantes sem esse dado. A migration `V2__add_restaurant_pickup_location.sql` preserva os registros existentes e mantém as coordenadas vazias até serem informadas. `0,0` é uma coordenada válida e não representa ausência de localização.

Exemplos de erro abaixo geram exceção HTTP no PowerShell, com os status indicados:

```powershell
# 400: nome inválido
Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://localhost:8082/api/catalog/restaurants' -ContentType 'application/json' -Body '{"name":" "}'
# 400: tamanho acima do limite
Invoke-WebRequest -UseBasicParsing 'http://localhost:8082/api/catalog/restaurants?page=0&size=101'
# 404: UUID inexistente em um banco sem esse registro
Invoke-WebRequest -UseBasicParsing 'http://localhost:8082/api/catalog/restaurants/00000000-0000-0000-0000-000000000000'
```

## Verificar pings e Actuator

Com catálogo e Gateway em execução:

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/info
Invoke-RestMethod http://localhost:8082/api/catalog/ping
Invoke-RestMethod http://localhost:8080/api/catalog/ping
Invoke-RestMethod http://localhost:8080/actuator/health
```

O health deve retornar `status: UP`. O ping direto e pelo Gateway deve retornar HTTP 200 e `{"service":"catalog-service","status":"ok"}`.

Para verificar os cinco serviços, com as seis aplicações em execução:

```powershell
$services = @(
    @{ Port = 8081; Resource = 'users' },
    @{ Port = 8082; Resource = 'catalog' },
    @{ Port = 8083; Resource = 'orders' },
    @{ Port = 8084; Resource = 'payments' },
    @{ Port = 8085; Resource = 'deliveries' }
)
foreach ($service in $services) {
    Invoke-RestMethod "http://localhost:$($service.Port)/actuator/health"
    Invoke-RestMethod "http://localhost:$($service.Port)/api/$($service.Resource)/ping"
    Invoke-RestMethod "http://localhost:8080/api/$($service.Resource)/ping"
}
```

O campo `service` deve identificar o serviço correspondente. Os pings são demonstrações de HTTP, não verificações de regras de negócio.

## Testes e build

Com Docker funcionando, execute da raiz:

```powershell
# Testes do catálogo
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml clean test
# Testes e JAR executável do catálogo
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml clean verify
# Testes e JAR executável de pedidos
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml clean verify
# Testes e JAR executável de usuários
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml clean verify
```

Os testes de domínio cobrem perfis, e-mails e endereços, além das regras de restaurantes, cardápios e composição dos pedidos, incluindo preços sem frações de centavo e cálculo exato dos totais. Os testes de integração inicializam Spring e PostgreSQL real via Testcontainers, aplicam Flyway e exercitam cadastro, dados persistidos, consulta, paginação, entrada inválida e recursos inexistentes. Usuários verificam senhas BCrypt, claims/assinatura/expiração JWT, contratos de autenticação, rollback de perfil/credencial, migração dos legados, unicidade de e-mail inclusive em cadastros simultâneos e isolamento dos endereços por perfil; catálogo verifica isolamento dos itens por restaurante. Pedidos usam servidores HTTP locais para testar a consulta ao catálogo, indisponibilidade e preservação dos valores após alterações remotas e transições. As migrations também são testadas sobre dados existentes, preservando pedidos antigos sem inventar preços. Os contextos com persistência usam o banco do Testcontainers. Não é necessário subir o Compose, criar `.env` ou fornecer credenciais locais para esses testes; eles não acessam o volume de desenvolvimento. A suíte completa exige Docker e falha quando ele está indisponível, em vez de ignorar a integração.

O JAR fica em `services/catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar`. Com o PostgreSQL local iniciado e `.env` configurado, ele também pode ser executado da raiz:

```powershell
java -jar .\services\catalog-service\target\catalog-service-0.0.1-SNAPSHOT.jar
```

Para testar todas as aplicações:

```powershell
$projects = @(
    'api-gateway',
    'services/user-service',
    'services/catalog-service',
    'services/order-service',
    'services/payment-service',
    'services/delivery-service'
)
foreach ($project in $projects) {
    & "$project/mvnw.cmd" -f "$project/pom.xml" clean test
    if ($LASTEXITCODE -ne 0) { throw "Testes falharam em $project" }
}
```

Substitua `clean test` por `clean verify` para também gerar os JARs em `target/` de cada aplicação. Usuários e pedidos têm testes de domínio, HTTP, persistência e concorrência, também com PostgreSQL descartável. Entregas têm testes de domínio, casos de uso, HTTP, erros, persistência e concorrência; a suíte completa exige Docker e usa Testcontainers. Gateway e pagamentos mantêm os testes de inicialização de contexto. As integrações HTTP automatizadas de usuários, catálogo, pedidos e entregas testam diretamente os serviços; confira também o encaminhamento real pelo Gateway usando os exemplos documentados.

Veja o fluxo e as responsabilidades em [docs/architecture.md](docs/architecture.md), os detalhes de persistência em [docs/catalog-postgresql.md](docs/catalog-postgresql.md) e o [registro de validação do catálogo](docs/catalog-validation.md).
