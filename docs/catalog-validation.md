# Validação do catálogo

Validação local realizada em 29/09/2026 na branch `feature/catalog-restaurants`, a partir do commit `1048fe4`, com as alterações de documentação e remoção de comentários ainda presentes na árvore de trabalho. Este registro descreve uma execução específica; alterações posteriores exigem nova validação conforme seu impacto.

## Ambiente

- Java 21.0.12.1 e Spring Boot 4.1.1.
- Docker Engine 29.8.1, com containers Linux.
- PostgreSQL 17.11, usando a imagem `postgres:17-alpine` do Compose existente.
- Banco local saudável, publicado em `127.0.0.1:5433`.
- Catálogo na porta 8082 e Gateway na porta 8080, executados como JARs na máquina.

Não foi necessário iniciar ou recriar o banco. O `.env` existente foi usado sem alteração e permanece ignorado pelo Git. Nenhuma credencial integra este registro.

## Testes e build

Comandos executados a partir da raiz:

```powershell
.\services\catalog-service\mvnw.cmd -f .\services\catalog-service\pom.xml -B clean verify
.\api-gateway\mvnw.cmd -f .\api-gateway\pom.xml -B clean verify
```

| Projeto | Testes | Falhas | Erros | Ignorados | Build |
| --- | ---: | ---: | ---: | ---: | --- |
| Catalog Service | 76 | 0 | 0 | 0 | Sucesso, JAR executável gerado |
| API Gateway | 1 | 0 | 0 | 0 | Sucesso, JAR executável gerado |

Os testes do catálogo utilizaram PostgreSQL real via Testcontainers, em bancos separados do volume de desenvolvimento. A suíte cobre domínio, aplicação, persistência, migrations, validação do schema, API e tratamento de erros. Os avisos de carregamento dinâmico do agente Mockito não impediram a execução no Java 21.

## Verificação HTTP com os processos reais

Foram executadas 34 verificações HTTP. As operações abaixo passaram diretamente no catálogo e pelo Gateway:

| Verificação | Resultado |
| --- | --- |
| Cadastro | `201`, UUID válido, nome sem espaços nas extremidades, `active=true` e `Location` relativo |
| Consulta pelo endereço de `Location` | `200`, com os dados cadastrados |
| Listagem das páginas 0 e 1 com tamanho 1 | `200`; metadados e tamanho da primeira página conferidos |
| Listagem sem parâmetros | `200`, com padrões `page=0` e `size=20` |
| Nome em branco, nome numérico e JSON malformado | `400` |
| Tamanho 101, página negativa e UUID malformado | `400` |
| UUID inexistente | `404` |
| Respostas de erro | `application/problem+json`, status correspondente, sem campos de stack trace ou exceção |
| Ping do catálogo | `200`, serviço e estado esperados |
| Actuator de cada aplicação, em sua própria porta | Health `200` e `UP`; info `200` |

Depois dos cadastros, o processo do catálogo foi encerrado e iniciado novamente. Os dois registros continuaram consultáveis nas duas portas, confirmando a persistência após reinício da aplicação. A inicialização validou a migration Flyway e o mapeamento Hibernate no PostgreSQL local.

Os dois restaurantes criados têm nomes iniciados por `Validacao Catalogo 20260929-202504`, seguidos pela porta utilizada no cadastro. Eles foram mantidos no banco. Nenhum registro anterior, volume ou container de desenvolvimento foi removido.

## Limites desta execução

- Os processos Java iniciados para a verificação foram encerrados ao final; o PostgreSQL do Compose permaneceu saudável e em execução.
- O Compose atual executa somente o banco. A execução dos serviços Java em containers não faz parte desta entrega.
- Os testes e builds de usuários, pedidos, pagamentos e entregas não foram reexecutados nesta validação do catálogo.
- Não foram realizados commit, push, abertura de PR ou merge.

Os procedimentos de configuração e chamadas estão no [README](../README.md) e no [guia de PostgreSQL do catálogo](catalog-postgresql.md).
