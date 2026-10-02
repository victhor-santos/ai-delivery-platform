# Validação da persistência de entregas

Revisão da branch `feature/delivery-persistence`, baseada no commit `920a23c` (domínio de entregas, PR #10), em 01/10/2026. O escopo desta etapa é PostgreSQL, Flyway, adaptadores JPA e testes; a API de negócio permanece na etapa seguinte do roadmap.

## Build e testes

Executado `clean verify` pelo Maven Wrapper de cada aplicação, com JDK 21 e Docker em execução. As integrações usam PostgreSQL 17 descartável via Testcontainers.

| Aplicação | Testes | Resultado |
| --- | ---: | --- |
| API Gateway | 1 | Aprovado |
| User Service | 1 | Aprovado |
| Catalog Service | 131 | Aprovado |
| Order Service | 75 | Aprovado |
| Payment Service | 1 | Aprovado |
| Delivery Service | 128 | Aprovado |
| Total | 337 | Sem falhas, erros ou testes ignorados |

Os seis JARs executáveis foram gerados. Delivery cobre reconstrução dos oito cenários do ciclo, persistência das transições, unicidade por pedido, reserva concorrente de entregador, versão otimista, precisão temporal e validação do schema. A revisão acrescentou verificações de preservação do estado e da versão após comandos rejeitados, unicidade por pedido após cancelamento e ping/Actuator com servidor HTTP real em porta aleatória.

O teste de horário inválido considera a tradução feita pelo proxy `@Repository`: `InvalidDataAccessApiUsageException` com causa `IllegalArgumentException`. A regra de domínio não mudou.

Também passaram `docker compose config --quiet`, `git diff --check` e a verificação dos links locais do README e de `docs/`.

## Execução dos JARs e Gateway

Delivery foi iniciado na porta 8085 e o Gateway na porta 18080, mantendo a rota original `/api/deliveries/**`. Um PostgreSQL 17 temporário, com armazenamento em memória e porta dinâmica, isolou a verificação dos bancos de desenvolvimento.

- Flyway aplicou a V1 em um banco vazio, e Hibernate validou o schema.
- Health retornou `UP` nas duas aplicações.
- `/api/deliveries/ping` retornou HTTP 200 e `delivery-service/ok`, diretamente e pelo Gateway.
- `/actuator/info` retornou HTTP 200 nas duas aplicações.

Os processos e o container temporário foram encerrados após a verificação. Os volumes locais foram preservados. Essa checagem valida inicialização e roteamento; não existe ainda API HTTP de negócio em Delivery.

## Avisos observados

Os testes em Java 21 emitem avisos de carregamento dinâmico do agente Mockito/Byte Buddy e de compartilhamento de classes. O Gateway emite avisos `HV000271` sobre `@Valid` em coleções internas do Spring Cloud. Não foram encontrados avisos de compilação no código da aplicação. As dependências e os níveis de log foram preservados; esses avisos não foram ocultados e não impediram o build.

## Entrega no Git

As mudanças formam uma feature de persistência, com código, testes e documentação no mesmo commit. Mensagem prevista pelo roadmap:

```text
feat: persist deliveries and couriers with PostgreSQL
```

Antes do commit, incluir os arquivos novos de `application`, `infrastructure/persistence`, migrations e testes. O diff comum não mostra o conteúdo dos arquivos ainda não rastreados. `.env`, artefatos de build e configurações locais em `.ai/` ficam fora do versionamento.

Após o PR e sua integração, a próxima etapa é `feature/delivery-lifecycle`: casos de uso, DTOs, cadastro mínimo de entregador, endpoints de transição e respostas HTTP controladas. A integração com pedidos confirmados e sua política de idempotência vêm depois. Não é necessário antecipar o serviço Python para concluir esta etapa.
