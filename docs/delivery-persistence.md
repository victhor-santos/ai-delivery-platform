# Persistência de entregas

Delivery usa um PostgreSQL próprio para guardar entregas e a identidade mínima dos entregadores. A persistência inclui migrations, adaptadores JPA e testes de integração. Os [casos de uso e a API HTTP](delivery-lifecycle.md) utilizam esses mesmos repositórios e regras.

O [registro de validação](delivery-validation.md) documenta a etapa de persistência integrada pelo PR #11.

## Configuração local

Se `.env` já existir, acrescente apenas as entradas de entregas de `.env.example`, preservando a configuração dos outros bancos:

```dotenv
DELIVERY_DB_USERNAME=deliveries
DELIVERY_DB_PASSWORD=deliveries_local_only_change_me
DELIVERY_DB_PORT=5435
DELIVERY_DB_URL=jdbc:postgresql://localhost:5435/deliveries
```

A senha acima é um exemplo local. Escolha o valor antes de inicializar o banco e não versione `.env`. O Compose permite iniciar os bancos antigos sem definir a senha de entregas; porém, PostgreSQL não inicializa um volume vazio de `delivery-db` com senha vazia. O serviço Java também exige `DELIVERY_DB_PASSWORD`.

Na raiz do repositório:

```powershell
docker compose up -d --wait delivery-db
docker compose ps
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

O banco `deliveries` fica em `127.0.0.1:5435`, e o serviço continua na porta 8085. Se mudar a porta do banco, altere tanto `DELIVERY_DB_PORT` quanto `DELIVERY_DB_URL`. O volume `delivery_postgres_data` é separado dos volumes de catálogo e pedidos. Alterar credenciais no arquivo não altera um banco já inicializado.

Para interromper o banco sem remover dados:

```powershell
docker compose stop delivery-db
```

## Schema e garantias

Flyway aplica `V1__create_delivery_tables.sql`. Hibernate usa `ddl-auto=validate`, e `open-in-view` fica desabilitado. As versões das dependências seguem o Spring Boot, como nos outros serviços.

`couriers` guarda UUID, indicador ativo e versão. `deliveries` guarda a referência ao pedido, as duas localizações, entregador atribuído, estado, horários e versão. A chave estrangeira de entregador aponta para uma tabela do próprio serviço. `order_id` é apenas uma referência: Delivery não consulta tabelas do banco de pedidos.

O banco garante:

- Uma entrega por `order_id`, inclusive depois de cancelamento. Nesta etapa, repetir a criação gera conflito; ainda não há contrato idempotente de integração.
- Um entregador por entrega em andamento, pelo índice único parcial sobre `courier_id` quando o estado é `ASSIGNED`, `PICKED_UP` ou `IN_TRANSIT`.
- Coordenadas válidas, descrições preenchidas e consistência entre estado e horários.
- Referência a um entregador cadastrado, quando houver atribuição.

Concluir ou cancelar libera o entregador para outra entrega sem apagar seu vínculo histórico. A atividade do entregador é conferida ao atribuir pelo adaptador. Ainda não existe operação de ativação/desativação; se ela for adicionada, sua concorrência com atribuição precisará ser tratada.

As restrições garantem consistência dos registros, mas não substituem os comandos do domínio. A aplicação não atualiza estados diretamente por SQL. Regras de transição, como não cancelar depois da coleta, continuam em `Delivery`.

## Camadas e transações

`application` define `DeliveryRepository` e `CourierRepository`, sem Spring ou JPA. `infrastructure.persistence` implementa essas portas, mapeia entidades e abre transações. `DeliveryService` e `CourierService` coordenam as chamadas HTTP por essas portas, sem dependências de Spring ou JPA. A configuração Spring fornece os beans e um `Clock` UTC.

Cada transição carrega a entidade, reconstrói o domínio, executa seu comando e copia o estado para a entidade gerenciada na mesma transação. `@Version` impede que uma escrita baseada em leitura antiga sobrescreva uma alteração já confirmada. Criação e atribuição também dependem das restrições únicas, porque consultar antes de gravar não bastaria em duas requisições simultâneas.

Os métodos de consulta e transição retornam `Optional.empty()` quando a entrega não existe. A atribuição carrega o entregador do banco e informa `CourierNotFoundException` se ele não existir. A API traduz violações de unicidade (SQLSTATE `23505`) e conflitos de versão em `409`, sem expor SQL. Outras violações inesperadas de integridade retornam `500` com mensagem genérica.

`Delivery.restore` valida o histórico reconstruído usando as mesmas regras do domínio. O adaptador normaliza `Instant` para microssegundos antes de salvar e devolver os dados, evitando diferenças de precisão entre a resposta do repositório e uma leitura posterior.

## Testes e execução

Com Docker funcionando, execute da raiz:

```powershell
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml clean verify
```

Testcontainers cria PostgreSQL 17 descartável para a integração e o teste de contexto. Não é preciso `.env` nem Compose para os testes, e os volumes locais não são acessados. A suíte cobre reconstrução de estados, ciclo persistido, consultas por entrega e pedido, cancelamento, liberação do entregador, validação do schema e precisão temporal.

O teste de inicialização sobe o servidor HTTP em porta aleatória e verifica ping, health e info com o banco configurado. A integração também verifica que o cancelamento não permite outra entrega para o mesmo pedido e que transições rejeitadas preservam o estado e a versão persistidos.

Dois testes usam requisições concorrentes ao repositório: criação para o mesmo pedido e atribuição do mesmo entregador a entregas diferentes. Em ambos, apenas uma transação pode vencer. Outro teste carrega duas cópias da mesma entidade e verifica que a versão antiga não pode sobrescrever a nova.

Depois do build, com `delivery-db` em execução, o JAR pode ser iniciado da raiz:

```powershell
java -jar .\services\delivery-service\target\delivery-service-0.0.1-SNAPSHOT.jar
```

Os endpoints existentes continuam disponíveis:

```powershell
Invoke-RestMethod http://localhost:8085/actuator/health
Invoke-RestMethod http://localhost:8085/actuator/info
Invoke-RestMethod http://localhost:8085/api/deliveries/ping
```

Com o Gateway iniciado em outro terminal, `http://localhost:8080/api/deliveries/ping` continua encaminhando para Delivery. O health de Delivery inclui seu banco. A [API de negócio](delivery-lifecycle.md) também usa o prefixo `/api/deliveries/**`. A integração automática com pedidos e o serviço Python continuam planejados.
