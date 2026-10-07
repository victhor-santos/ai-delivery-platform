# Perfis e endereços de usuários

O User Service mantém perfis e endereços em PostgreSQL próprio. O UUID identifica o perfil; nome e endereços podem ser atualizados, enquanto o e-mail permanece imutável nesta etapa. O perfil é criado pelo [cadastro com senha](authentication.md), que também oferece login e identidade JWT; não há confirmação de e-mail. As operações abaixo exigem o Bearer token do próprio usuário, conforme a [autorização dos recursos](resource-authorization.md): sem token válido retornam `401`, e o perfil ou endereço de outra pessoa retorna `404`.

## Contrato HTTP

Os caminhos atendem diretamente na porta 8081 e pelo Gateway na porta 8080, sem remover prefixos:

| Operação | Resultado |
| --- | --- |
| `GET /api/users/{id}` | Consulta perfil; `200` |
| `PUT /api/users/{id}/profile` | Substitui `{name}`; `200`, preservando UUID e e-mail |
| `POST /api/users/{userId}/addresses` | Cadastra endereço; `201`, UUID e `Location` |
| `GET /api/users/{userId}/addresses?page=0&size=20` | Página de endereços do usuário; `200` |
| `GET /api/users/{userId}/addresses/{addressId}` | Consulta um endereço pertencente ao usuário; `200` |
| `PUT /api/users/{userId}/addresses/{addressId}` | Substitui todos os campos editáveis do endereço; `200` |

O perfil responde com `id`, `name` e `email`. O endereço responde com `id`, `userId`, `label`, `address`, `latitude` e `longitude`. A página contém `items`, `page`, `size`, `totalElements` e `totalPages`. A ordenação é por rótulo e UUID, com página a partir de zero, tamanho entre 1 e 100 e offset limitado a `Integer.MAX_VALUE`. Uma página vazia não significa usuário inexistente; esse caso retorna `404`.

O endereço recebido é:

```json
{
  "label": "Casa",
  "address": "Destino sintético C",
  "latitude": -23.561,
  "longitude": -46.656
}
```

UUIDs são gerados pelo servidor. Campos extras são ignorados, incluindo IDs enviados no cadastro e e-mail enviado na atualização do nome. Não há listagem global de usuários, consulta por e-mail, exclusão, endereço principal ou reserva de rótulo único. Repetir um cadastro de endereço pode criar outro registro; o cadastro de perfil com e-mail já usado retorna `409`.

## Validação e identidade

- Nome obrigatório, sem espaços nas extremidades, com até 120 caracteres.
- E-mail obrigatório, normalizado com `strip()` e minúsculas com `Locale.ROOT`, com até 254 caracteres. A aplicação trata o endereço inteiro sem distinção de maiúsculas.
- O formato de e-mail aceito é ASCII: parte local de até 64 caracteres, sem ponto inicial/final ou pontos consecutivos; domínio com pelo menos um ponto e rótulos de até 63 caracteres. Formatos com parte local entre aspas, domínio literal, endereços internacionalizados e validação por DNS ficam fora deste contrato.
- Rótulo obrigatório de até 80 caracteres e endereço obrigatório de até 255, removendo espaços nas extremidades.
- Latitude entre -90 e 90 e longitude entre -180 e 180, ambas obrigatórias e finitas. Não há geocodificação: coordenadas são informadas explicitamente.
- Textos exigem strings JSON; coordenadas exigem números. Coerções de números/booleanos para textos ou de strings/booleanos para coordenadas são rejeitadas.

A validação de formato não comprova existência do e-mail nem sua propriedade. Não são aplicadas regras particulares de provedores, como remover pontos ou sufixos com `+`. O UUID é a referência estável das credenciais e será usado no vínculo com recursos.

| Status | Situação |
| --- | --- |
| `400` | JSON, UUID, texto, e-mail, coordenadas ou paginação inválidos |
| `404` | Perfil/endereço ausente ou endereço fora do usuário informado |
| `409` | E-mail normalizado já cadastrado |
| `500` | Falha inesperada, sem detalhes internos na resposta |

Erros usam `application/problem+json`. Consultar ou atualizar um endereço usa o par `userId + addressId`, e atualizar um UUID ausente não cria recurso. Essa associação protege a organização dos dados; os endpoints de perfil/endereço ainda não exigem a identidade do Bearer token. A autenticação possui contrato separado e a autorização será a próxima feature, antes de tratar esse fluxo como acesso protegido.

## Organização e persistência

`UserProfile`, `EmailAddress` e `UserAddress` são valores de domínio sem dependências de Spring/JPA. `UserProfileService` e `UserAddressService` coordenam os casos de uso por `UserRepository` e `UserAddressRepository`. `UserConfiguration` fornece os beans. Controllers convertem DTOs, sem expor entidades JPA ou consultar outro serviço.

`V1__create_users_and_addresses.sql` cria `users` e `user_addresses`. A FK do endereço referencia apenas `users` no mesmo banco e exclui filhos em cascata se uma operação interna remover o perfil; não há exclusão pública. O índice por usuário, rótulo e UUID atende a paginação. A V2 de autenticação adiciona `user_credentials`, mantendo perfis/endereços anteriores sem criar senhas para eles. Perfis criados por `POST /api/users` também ficam sem credenciais; registro com senha para o mesmo e-mail retorna conflito, evitando apropriação de conta. Hibernate valida o schema e `open-in-view` fica desabilitado.

O banco impõe textos não vazios, normalização do e-mail, unicidade e intervalos geográficos. A constraint nomeada `users_email_unique` garante unicidade inclusive em cadastros simultâneos. O adaptador faz flush dentro da transação e traduz somente essa violação para conflito de e-mail; outras violações não são classificadas como duplicidade. A validação completa do formato fica no domínio.

Atualizações carregam o recurso existente e alteram somente seus campos editáveis na mesma transação. UUID, e-mail do perfil e usuário do endereço são preservados. Assim como no cardápio atual, não há contrato de versão/ETag: prevalece a última gravação confirmada. As operações de perfil/endereço não alteram os snapshots de destino já salvos em Orders, que ainda não referencia usuários.

## Executar e testar

Na raiz, complete as entradas `USER_DB_*` do seu `.env` a partir do `.env.example`, preservando os valores existentes. O `.env` não é versionado. Usuários usam banco `users`, volume `user_postgres_data` e porta local 5436; o serviço usa 8081. Se mudar a porta do banco, ajuste `USER_DB_PORT` e `USER_DB_URL`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/initialize-auth-secret.ps1
docker compose up -d --wait user-db
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml "-Dspring-boot.run.workingDirectory=$PWD" spring-boot:run
```

Com Gateway e User iniciados, o exemplo cria perfil e endereço:

```powershell
$baseUrl = 'http://localhost:8080'
$user = Invoke-RestMethod -Method Post "$baseUrl/api/users" `
    -ContentType 'application/json' -Body '{"name":"Cliente Demo","email":"demo@example.test"}'
$addresses = "$baseUrl/api/users/$($user.id)/addresses"
$address = Invoke-RestMethod -Method Post $addresses -ContentType 'application/json' `
    -Body '{"label":"Casa","address":"Destino sintético C","latitude":-23.561,"longitude":-46.656}'
Invoke-RestMethod "$baseUrl/api/users/$($user.id)"
Invoke-RestMethod "${addresses}?page=0&size=20"
```

O smoke verifica cadastro com senha, login, identidade JWT e recusas de credenciais/tokens, além de criação, normalização/duplicidade de e-mail, atualização do perfil, cadastro/alteração de endereço, paginação e rejeição de consulta/alteração pelo usuário errado:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -UsersOnly
```

Esse modo consulta somente User pelo Gateway. Os modos parciais `-UsersOnly`, `-CatalogOnly` e `-OrderOnly` são exclusivos e não aceitam `-CheckRecovery` ou `-CheckPersistence`. Cada execução cria dados fictícios no banco selecionado. O fluxo completo também verifica perfis/endereço e, com `-CheckPersistence`, sua preservação após recriar containers reutilizando os volumes. Veja a [demonstração integrada](route-intelligence-compose.md).

Com Docker disponível, os testes usam PostgreSQL 17 descartável via Testcontainers e não acessam volumes de desenvolvimento:

```powershell
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml clean verify
```

As validações cobrem domínio, casos de uso, API, ownership dos endereços, persistência, constraints e cadastros concorrentes. Docker ausente faz os testes de integração falharem, em vez de ignorá-los. O build gera `services/user-service/target/user-service-0.0.1-SNAPSHOT.jar`. `/api/users/ping`, `/actuator/info` e `/actuator/health` permanecem disponíveis; o health agora inclui a conexão com o banco.

Em 05/10/2026, `clean verify` de User passou com 276 testes, sem falhas, erros ou casos ignorados, e gerou o JAR executável. Os testes de integração aplicaram V1 em PostgreSQL 17 descartável, incluindo cadastros concorrentes com exatamente um sucesso e um conflito de e-mail. Os logs de testes incluem violações SQL e uma falha inesperada provocadas para conferir seus contratos de erro; elas não representam falhas na suíte.

A imagem de User também foi construída e os onze containers ficaram saudáveis em um projeto Compose isolado. Passaram `-UsersOnly`, `-OrderOnly` e o fluxo completo com `-CheckRecovery -CheckPersistence`. Perfil e endereço mantiveram os dados atualizados após recriar containers; pedidos, entrega, plano, observações/CSV e idempotência também foram preservados. A recuperação do Python passou. Os demais serviços reutilizaram suas imagens já validadas na feature anterior; suas suítes unitárias não foram reexecutadas nesta etapa. O projeto descartável e seus volumes próprios foram removidos ao final, preservando os bancos, volumes e `.env` de desenvolvimento.

## Continuação

A autenticação está implementada em [contrato próprio](authentication.md). A próxima etapa é autorização de recursos e vínculo com pedidos, conforme o [roadmap](roadmap.md). Senhas e credenciais usam o UUID do perfil sem incorporar detalhes de autenticação ao domínio de perfis.
