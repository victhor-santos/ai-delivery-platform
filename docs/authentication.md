# Cadastro com senha e autenticação

User mantém credenciais no seu próprio PostgreSQL. A API permite criar um perfil com senha, fazer login e consultar a identidade do Bearer token. Os endpoints atendem diretamente em 8081 ou pelo Gateway em 8080, que encaminha o cabeçalho `Authorization` sem validar tokens.

| Operação | Contrato |
| --- | --- |
| `POST /api/users/auth/register` | `{name,email,password}` → `201`, perfil e `Location: /api/users/{id}` |
| `POST /api/users/auth/login` | `{email,password}` → `200`, `accessToken`, `tokenType: "Bearer"`, `expiresIn: 900` |
| `GET /api/users/auth/me` | `Authorization: Bearer <token>` → `200`, perfil correspondente ao subject validado |

Cadastro/login são públicos. `/auth/me` exige autenticação e deriva o UUID do token, sem aceitar identidade informada pelo cliente. Erros usam `application/problem+json`: entrada inválida `400`, e-mail já utilizado `409`, login incorreto/token ausente ou inválido `401`. Uma identidade de token válido sem perfil existente recebe `404`. Respostas de token/identidade usam `Cache-Control: no-store`; não há cookie de sessão, HTTP Basic ou formulário de login. `401` inclui `WWW-Authenticate: Bearer` sem explicar se um e-mail existe.

A [autorização dos recursos](resource-authorization.md) usa este token: perfis, endereços e pedidos só atendem o próprio dono. Catálogo, entregas e operações administrativas continuam sem papéis nem proteção.

## Senhas e cadastro atômico

Senhas exigem pelo menos 12 caracteres Unicode e no máximo 72 bytes em UTF-8. Não são removidos espaços nem normalizadas letras/acentos. Texto em branco é rejeitado. O limite de bytes impede truncamento pelo BCrypt; uma senha multibyte pode alcançar o limite antes de 72 caracteres. Nome/e-mail mantêm as regras de [perfis](user-profiles.md); campos extras como UUID ou papel são ignorados.

`PasswordPolicy` define o contrato sem Spring. `AuthenticationService` usa as portas `PasswordHasher`, `AuthAccountRepository` e `AccessTokenIssuer`. `BCryptPasswordHasher` usa Spring Security, custo 12 e salt independente por hash. Senhas e tokens são omitidos dos `toString()` dos DTOs e resultados que os transportam e nunca são retornados no perfil. A migração `V2__add_user_credentials.sql` cria `user_credentials`, com hash e FK para o UUID do perfil; não duplica o e-mail.

`JpaAuthAccountRepository.register` envolve perfil e credencial na mesma transação. Reutiliza a tradução específica da constraint de e-mail existente e faz flush da credencial. Uma falha depois da gravação do perfil desfaz ambas as alterações. A unicidade continua protegida pelo banco em cadastros concorrentes. Cadastro repetido não substitui a senha anterior.

O login consulta e-mail normalizado e verifica o hash. E-mail desconhecido e perfil sem credencial também executam uma verificação BCrypt com hash fictício, retornando o mesmo erro genérico. Isso reduz a diferença de trabalho nesses casos; não promete tempos idênticos nem substitui limitação de tentativas.

Perfis anteriores à V2 e os criados pelo antigo `POST /api/users`, removido na autorização, ficam sem credenciais; seus dados e endereços são preservados. Não recebem senha padrão e não podem ser assumidos por cadastro público com o mesmo e-mail. Para a demonstração, cadastre uma conta nova por `/auth/register`. Conversão de perfis antigos, confirmação de e-mail e recuperação de senha precisam de prova de propriedade e não foram implementadas.

## Tokens e configuração

`JwtAccessTokens` emite JWT HS256 com UUID em `sub`, `jti` único, `iss`, `aud`, `iat`, `nbf` e `exp`. O emissor é `https://delivery-order-system.local` e a audiência é `delivery-order-system`. O emissor é um identificador, sem descoberta OIDC nem consulta a esse domínio. O token dura 15 minutos. O decoder exige assinatura HS256, emissor, audiência, subject UUID canônico, emissão/vencimento e duração permitida; valida datas com tolerância de 30 segundos. O `Clock` é injetado para testar essas regras.

O Spring Security Resource Server processa o Bearer header; não há filtro JWT escrito manualmente. A configuração de segurança é stateless. CSRF está desativado para este contrato sem autenticação por cookies. A política dos endpoints está descrita na [autorização](resource-authorization.md). Esta API de login não é uma implementação completa de servidor OAuth/OIDC.

User exige `USER_AUTH_SECRET`, Base64 de pelo menos 32 bytes aleatórios, sem valor padrão no runtime. A chave local não entra no Git. A fixture em `src/test/resources/application-test.properties` é pública e carregada somente pelo perfil `test`; nunca deve ser usada fora dos testes. Order também recebe a chave para validar tokens, sem emiti-los.

Na raiz, crie/complete o `.env` a partir do `.env.example` e gere a chave:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/initialize-auth-secret.ps1
docker compose --profile demo up -d --build --wait --wait-timeout 240
```

O script gera 32 bytes com `RandomNumberGenerator`, preenche somente uma entrada ausente/vazia, preserva uma chave já configurada e não mostra seu valor. Entradas duplicadas causam erro sem gravação. `-EnvFile` permite um arquivo de ambiente isolado. Preserve a mesma chave entre reinícios para aceitar tokens ainda válidos; substituí-la invalida tokens anteriores. A chave HS256 é usada para assinar e validar: quem tiver acesso a ela pode emitir tokens. A distribuição/rotação de chaves será decidida junto à proteção dos demais serviços.

Não há refresh token, revogação imediata, logout no servidor ou troca de senha. O cliente pode remover o token localmente; sua validade no servidor termina na expiração ou troca da chave. Limitação de tentativas, confirmação de e-mail e recuperação de senha permanecem pendentes. A demonstração usa HTTP local; transporte fora desse ambiente exige HTTPS.

## Exemplo e validação

```powershell
$baseUrl = 'http://localhost:8080'
$email = "demo-$([guid]::NewGuid().ToString('N'))@example.test"
$password = 'demonstration-password-123'
$registration = @{ name = 'Cliente Demo'; email = $email; password = $password } | ConvertTo-Json
$profile = Invoke-RestMethod -Method Post "$baseUrl/api/users/auth/register" `
    -ContentType 'application/json' -Body $registration
$credentials = @{ email = $email; password = $password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post "$baseUrl/api/users/auth/login" `
    -ContentType 'application/json' -Body $credentials
Invoke-RestMethod "$baseUrl/api/users/auth/me" -Headers @{ Authorization = "Bearer $($login.accessToken)" }
```

Não publique senhas, chave ou token em logs/screenshots. O smoke usa uma senha fictícia e não mostra tokens:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -UsersOnly
.\services\user-service\mvnw.cmd -f .\services\user-service\pom.xml clean verify
```

O smoke confere registro/login, recusa de senha/token incorretos e identidade autenticada antes das operações de perfil/endereço. O fluxo completo com `-CheckPersistence` confere login após recriar containers e validade do token anterior com a mesma chave. Os testes cobrem política de senha, salt, limite multibyte, verificação fictícia, claims/algoritmo/assinatura/expiração, contrato HTTP, unicidade concorrente, rollback e migração V1→V2 sem criar credenciais para legados. Bancos e chaves de teste são isolados dos dados de desenvolvimento.

Em 06/10/2026, `clean verify` de User passou com 329 testes, sem falhas, erros ou casos ignorados, e gerou o JAR executável. Foram acrescentados 53 casos sobre a base de perfis/endereços. Um cenário de teste de expiração foi corrigido para emitir um token válido no passado, já vencido no momento da consulta. A suíte completa passou sem ampliar seus timeouts.

A imagem de User foi reconstruída. No projeto Compose descartável, passaram `-UsersOnly`, `-OrderOnly` e o fluxo completo com `-CheckRecovery -CheckPersistence`. Os onze containers ficaram saudáveis; perfil, endereço e credencial persistiram, o login funcionou após recriar os containers e o token anterior continuou válido com a mesma chave. Pedido com itens, entrega, rota, observações/CSV e recuperação do Python também passaram. A execução final usou o Compose do repositório, sem override externo de recursos.

Os outros serviços reutilizaram as imagens já validadas anteriormente; suas suítes Java/Python não foram reexecutadas nesta feature. Algumas tentativas foram interrompidas pela espera moderna do Windows, confirmada pelos eventos do sistema. A validação final inibiu suspensão por ociosidade somente durante o processo de teste, sem alterar o plano de energia ou a segurança do Windows. O ambiente descartável e seus volumes foram removidos, preservando os três bancos existentes e o `.env` de desenvolvimento.

Referências: [armazenamento de senhas no Spring Security](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) e [JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
