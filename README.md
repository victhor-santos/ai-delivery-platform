# Delivery Order System

Projeto educacional de pedidos para delivery em Java e Spring Boot. O repositório reúne seis aplicações independentes: um API Gateway e cinco serviços.

## Estado atual

O Catalog Service cadastra e consulta restaurantes em PostgreSQL, com migrations Flyway, validação de entrada, paginação e testes de integração com Testcontainers. Um restaurante tem UUID, nome obrigatório e indicador `active`; o cadastro gera o UUID e inicia o restaurante ativo.

Os cinco serviços mantêm seus endpoints `/ping` e roteamento HTTP pelo Gateway. As seis aplicações expõem Actuator. Os outros serviços ainda são a base inicial, sem persistência ou regras de negócio. Produtos, cardápios, pedidos reais, pagamentos, entregas, RabbitMQ e autenticação estão fora desta etapa.

## Requisitos

- JDK 21, com `JAVA_HOME` configurado e `java` disponível no terminal.
- PowerShell para os exemplos abaixo.
- Docker com suporte a containers Linux e Docker Compose v2, em execução, para o PostgreSQL local e os testes de integração do catálogo.
- Acesso à internet na primeira execução para baixar Maven, dependências e a imagem PostgreSQL.

Cada aplicação inclui o Maven Wrapper; não é necessário instalar Maven separadamente. Versões da base: Spring Boot 4.1.1 e Spring Cloud 2025.1.3 no Gateway. O catálogo usa as versões de Spring Data JPA, PostgreSQL JDBC, Flyway e Testcontainers geridas pelo Spring Boot; o banco local e os testes usam PostgreSQL 17.

## Estrutura

```text
deliveryOrderSystem/
├── api-gateway/
├── services/
│   ├── user-service/
│   ├── catalog-service/
│   ├── order-service/
│   ├── payment-service/
│   └── delivery-service/
├── compose.yaml
├── .env.example
└── docs/
    └── architecture.md
```

Cada aplicação possui `pom.xml`, Maven Wrapper, código e testes próprios. Não há um build Maven agregador na raiz.

## Portas e endpoints

| Aplicação | Porta | Endpoint de demonstração |
| --- | --- | --- |
| API Gateway | 8080 | Encaminha os endpoints abaixo |
| User Service | 8081 | `/api/users/ping` |
| Catalog Service | 8082 | `/api/catalog/ping` |
| Order Service | 8083 | `/api/orders/ping` |
| Payment Service | 8084 | `/api/payments/ping` |
| Delivery Service | 8085 | `/api/deliveries/ping` |

Todas as aplicações expõem `/actuator/health` e `/actuator/info` em sua própria porta. O endpoint `info` pode retornar `{}`. O health do Gateway informa a saúde dele, não a de todos os serviços. O health do catálogo inclui a conexão com o banco.

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
```

O `.env.example` contém somente valores de desenvolvimento. Ajuste o `.env` antes de iniciar o banco; ele é ignorado pelo Git e não deve conter credenciais de produção. Use entradas simples `CHAVE=valor`, sem `export` ou aspas, pois o Compose e o Spring leem o mesmo arquivo.

| Variável | Uso local |
| --- | --- |
| `CATALOG_DB_URL` | JDBC do catálogo; padrão `jdbc:postgresql://localhost:5432/catalog` |
| `CATALOG_DB_USERNAME` | Usuário do banco; padrão `catalog` |
| `CATALOG_DB_PASSWORD` | Senha local obrigatória, definida no `.env` ou no ambiente |
| `CATALOG_DB_PORT` | Porta publicada pelo Compose; padrão `5432` |

Se a porta 5432 já estiver ocupada, escolha outra porta em `CATALOG_DB_PORT` e ajuste também `CATALOG_DB_URL`. Variáveis de ambiente podem sobrescrever os valores do arquivo. O Compose sobe somente `catalog-db` (`postgres:17-alpine`), publica a porta em `127.0.0.1`, usa o volume nomeado `catalog_postgres_data` e verifica a saúde com `pg_isready`:

```powershell
docker compose up -d --wait catalog-db
docker compose ps
```

O banco se chama `catalog`. A aplicação importa opcionalmente `.env` do diretório de execução e exige a senha para se conectar. Ao iniciar o catálogo, Flyway aplica as migrations em `src/main/resources/db/migration`; Hibernate apenas valida o schema (`ddl-auto=validate`). `open-in-view` fica desabilitado.

O volume preserva os dados entre reinícios. Alterar usuário ou senha no `.env` não altera as credenciais de um banco já inicializado; use os valores correspondentes ao volume existente. Para interromper o banco preservando seus dados:

```powershell
docker compose stop catalog-db
```

Não remova o volume para executar ou testar esta etapa. Os testes usam um banco descartável separado, criado pelo Testcontainers.

## Executar localmente

Para trabalhar com restaurantes, basta iniciar o PostgreSQL acima e abrir dois terminais na raiz:

```powershell
# Terminal 1: o diretório explícito faz o Spring carregar o .env da raiz
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
# Terminal 2
.\api-gateway\mvnw.cmd -f .\api-gateway\pom.xml spring-boot:run
```

Os demais serviços continuam disponíveis, um comando por terminal:

```powershell
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml spring-boot:run
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml spring-boot:run
.\services\payment-service\mvnw.cmd -f .\services\payment-service\pom.xml spring-boot:run
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml spring-boot:run
```

Use `Ctrl+C` em cada terminal para encerrar a aplicação. As portas precisam estar livres. Ao executar o catálogo pela IDE, configure o diretório de trabalho como a raiz do repositório ou forneça as variáveis de ambiente ao processo.

## Cadastrar e consultar restaurantes

O mesmo contrato atende diretamente em `http://localhost:8082` e pelo Gateway em `http://localhost:8080`. A rota existente `/api/catalog/**` preserva o caminho completo.

| Requisição | Resultado |
| --- | --- |
| `POST /api/catalog/restaurants` com `{"name":"Restaurante Central"}` | `201 Created`, corpo com `id`, `name`, `active` e cabeçalho `Location` |
| `GET /api/catalog/restaurants/{id}` | `200 OK` com o restaurante ou `404 Not Found` |
| `GET /api/catalog/restaurants?page=0&size=20` | `200 OK` com página de restaurantes |

O nome é obrigatório, não pode conter apenas espaços e aceita até 120 caracteres. Espaços nas extremidades são removidos. O cliente informa apenas `name`; UUID e estado ativo são definidos no cadastro.

Exemplo completo em PowerShell, executado primeiro diretamente e depois pelo Gateway. Cada execução cria um restaurante no banco local:

```powershell
foreach ($baseUrl in @('http://localhost:8082', 'http://localhost:8080')) {
    $created = Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri "$baseUrl/api/catalog/restaurants" `
        -ContentType 'application/json' -Body '{"name":"Restaurante Central"}'
    $created.StatusCode # 201
    $created.Headers['Location'] # /api/catalog/restaurants/{UUID}
    $restaurant = $created.Content | ConvertFrom-Json
    $restaurant # id, name e active=true
    Invoke-RestMethod "$baseUrl/api/catalog/restaurants/$($restaurant.id)"
    Invoke-RestMethod "$baseUrl/api/catalog/restaurants?page=0&size=20"
}
```

A página começa em zero; `page` deve ser não negativo e `size` deve estar entre 1 e 100. Os valores padrão são `page=0` e `size=20`. Para respeitar o limite de offset do JPA, `page * size` não pode ultrapassar `2147483647`. A ordem é fixa por `name ASC, id ASC`; UUID desempata nomes iguais. Não há parâmetro de ordenação configurável. Uma página sem resultados retorna `content: []`.

Formato da resposta paginada:

```json
{
  "content": [
    {"id":"9d6c1458-0c80-45cd-a9c0-b420f25c8246","name":"Restaurante Central","active":true}
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Entrada inválida retorna `400 Bad Request`, incluindo nome ausente/em branco, JSON inválido, UUID malformado e paginação fora dos limites. Um UUID válido que não existe retorna `404 Not Found`. Os erros usam `application/problem+json` com `status`, `title` e `detail`; podem incluir os campos padrão `type` e `instance`. Falhas inesperadas retornam `500` com mensagem genérica, sem detalhes internos.

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
```

Os testes de domínio cobrem as regras do restaurante. Os testes de integração inicializam Spring e PostgreSQL real via Testcontainers, aplicam Flyway e exercitam cadastro, dados persistidos, consulta, paginação, entrada inválida, restaurante inexistente e os endpoints preservados. O `contextLoads` também usa o banco do Testcontainers. Não é necessário subir o Compose, criar `.env` ou fornecer credenciais locais para esses testes; eles não acessam o volume de desenvolvimento. A suíte completa exige Docker e falha quando ele está indisponível, em vez de ignorar a integração.

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

Substitua `clean test` por `clean verify` para também gerar os JARs em `target/` de cada aplicação. Os outros projetos mantêm os testes de inicialização de contexto. A integração do catálogo testa HTTP diretamente no serviço; confira também o encaminhamento real pelo Gateway usando os exemplos acima.

Veja o fluxo e as responsabilidades em [docs/architecture.md](docs/architecture.md), os detalhes de persistência em [docs/catalog-postgresql.md](docs/catalog-postgresql.md) e o [registro de validação do catálogo](docs/catalog-validation.md).
