# Delivery Order System

Projeto educacional de pedidos para delivery em Java e Spring Boot. O repositório reúne seis aplicações independentes: um API Gateway e cinco serviços.

## Estado atual

A base inclui pacotes Java organizados por aplicação, endpoints de demonstração, Actuator e roteamento HTTP pelo Gateway. Ainda não há cadastro, pedidos reais, pagamentos, entregas ou persistência.

PostgreSQL, Flyway, RabbitMQ, Docker Compose, Testcontainers e autenticação fazem parte das próximas etapas. Não são necessários para executar a versão atual.

## Requisitos

- JDK 21, com `JAVA_HOME` configurado e `java` disponível no terminal.
- PowerShell para os exemplos abaixo.
- Acesso à internet na primeira execução para baixar Maven e dependências.

Cada aplicação inclui o Maven Wrapper; não é necessário instalar Maven separadamente. Versões atuais: Spring Boot 4.1.1 e Spring Cloud 2025.1.3 no Gateway.

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

Todas as aplicações expõem `/actuator/health` e `/actuator/info` em sua própria porta. O endpoint `info` pode retornar `{}`. O health do Gateway informa a saúde dele, não a de todos os serviços.

## Executar localmente

Abra seis terminais na raiz do repositório e execute um comando por terminal:

```powershell
# Terminal 1
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml spring-boot:run
# Terminal 2
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml spring-boot:run
# Terminal 3
.\services\order-service\mvnw.cmd -f .\services\order-service\pom.xml spring-boot:run
# Terminal 4
.\services\payment-service\mvnw.cmd -f .\services\payment-service\pom.xml spring-boot:run
# Terminal 5
.\services\delivery-service\mvnw.cmd -f .\services\delivery-service\pom.xml spring-boot:run
# Terminal 6
.\api-gateway\mvnw.cmd -f .\api-gateway\pom.xml spring-boot:run
```

Use `Ctrl+C` em cada terminal para encerrar a aplicação. As portas precisam estar livres. Também é possível entrar na pasta de uma aplicação e executar `.\mvnw.cmd spring-boot:run`.

## Verificar por HTTP

Exemplo com o Order Service:

```powershell
Invoke-RestMethod http://localhost:8083/actuator/health
Invoke-RestMethod http://localhost:8083/api/orders/ping
Invoke-RestMethod http://localhost:8080/api/orders/ping
```

O health deve retornar `status: UP`. O ping direto e pelo Gateway deve retornar HTTP 200 e o mesmo conteúdo:

```json
{"service":"order-service","status":"ok"}
```

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
Invoke-RestMethod http://localhost:8080/actuator/health
```

O campo `service` deve identificar o serviço correspondente. O Gateway preserva o caminho da requisição. Os pings são demonstrações de HTTP, não verificações de regras de negócio.

## Testes e build

Na raiz, execute os testes de todas as aplicações:

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

Para testar e gerar os JARs executáveis, substitua `clean test` por `clean verify`. Os artefatos ficam em `target/` de cada aplicação.

Os testes atuais verificam a inicialização do contexto Spring (`contextLoads`). Eles não substituem a verificação HTTP dos endpoints e do roteamento descrita acima.

Veja as responsabilidades e decisões em [docs/architecture.md](docs/architecture.md).
