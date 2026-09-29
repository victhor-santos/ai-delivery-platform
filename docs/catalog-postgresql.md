# PostgreSQL local do catálogo

O Catalog Service conecta ao PostgreSQL e usa Flyway para controlar o schema. Os casos de uso já cadastram e consultam restaurantes por um adaptador JPA. Os endpoints HTTP de restaurantes serão entregues na próxima etapa.

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

O Compose inicia somente PostgreSQL 17, com banco `catalog`, porta publicada em `127.0.0.1`, volume nomeado `catalog_postgres_data` e health check com `pg_isready`.

## Iniciar o catálogo

Na raiz do repositório:

```powershell
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

O diretório explícito permite carregar o `.env` da raiz. A aplicação recebe `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` e `CATALOG_DB_PASSWORD` pelo arquivo ou por variáveis de ambiente. A senha é obrigatória. Pela IDE, configure a raiz como diretório de trabalho ou forneça essas variáveis ao processo.

Flyway aplica `V1__create_restaurants.sql` na inicialização, criando a tabela `restaurants` e o índice `(name, id)`. O Hibernate usa `ddl-auto=validate`, e `open-in-view` fica desabilitado. Não edite migrations já aplicadas: mudanças futuras devem usar uma nova versão.

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

Os testes de integração criam bancos PostgreSQL 17 descartáveis via Testcontainers, separados do volume local. Não precisam de Compose ou `.env`. Verificam a inicialização do contexto, a migration Flyway, as restrições de nome, os dados gravados pelos casos de uso, as consultas e a paginação. Os testes de domínio e casos de uso também continuam na suíte, que falha se Docker estiver indisponível.

O build gera `services/catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar`. As versões de JPA, driver PostgreSQL, Flyway e Testcontainers são geridas pelo Spring Boot 4.1.1.

## Integração dos casos de uso com JPA

`RestaurantService` depende da interface `RestaurantRepository`. A configuração Spring em `infrastructure` fornece o serviço com o adaptador `JpaRestaurantRepository`, que converte entre `Restaurant` e `RestaurantEntity`. O domínio e os casos de uso permanecem independentes de Spring e JPA.

O adaptador abre uma transação de escrita para salvar e transações de leitura para consultar. O cadastro retorna após a confirmação da transação. Cada caso de uso atual faz uma única chamada de persistência; futuros fluxos com várias gravações precisarão de uma transação que englobe todas elas.

A listagem ordena por `name ASC, id ASC`, permitindo nomes repetidos com desempate por UUID. O limite de tamanho é 100, e a resposta da camada de aplicação inclui conteúdo, página, tamanho e total de registros.

## Preservar os dados

Para parar preservando os dados:

```powershell
docker compose stop catalog-db
```

Não remova volumes nem dados locais. Alterar as credenciais no `.env` não altera as de um volume já inicializado; mantenha a configuração correspondente ao banco existente.
