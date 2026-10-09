# Papéis, chaves assimétricas e limite de login

Esta etapa fecha as lacunas deixadas pela [autenticação](authentication.md) e pela [autorização dos recursos](resource-authorization.md). Antes, todos os serviços dividiam a mesma chave HMAC, então qualquer um deles podia emitir tokens; catálogo e entregas aceitavam comandos sem token; e o login não limitava tentativas. Agora só o User assina tokens, cada token carrega um papel, e catálogo e entregas exigem o papel certo para alterar dados.

## Tokens RS256 e JWKS

O User assina os tokens com RS256 e uma chave RSA privada de pelo menos 2048 bits (`USER_AUTH_PRIVATE_KEY`, PKCS#8 em Base64). A chave pública sai em `GET /.well-known/jwks.json` no próprio User, com `kid` igual ao thumbprint SHA-256 da chave, `use: sig` e `alg: RS256`. O endpoint é público, não passa pelo Gateway e nunca inclui partes privadas.

Order, Payment, Catalog e Delivery buscam esse JWKS em `USER_AUTH_JWK_SET_URI` (`http://user-service:8081/.well-known/jwks.json` no Compose). A busca acontece no primeiro token, com timeout de 2 segundos; o Nimbus guarda as chaves em cache e busca de novo quando aparece um `kid` desconhecido. Nenhum desses serviços tem material para assinar. Se o User estiver fora do ar antes da primeira busca, os tokens recebem `401` até ele voltar.

Os verificadores aceitam somente RS256 com a chave publicada: tokens HS256, `alg: none`, chave estrangeira e assinatura adulterada são recusados. As demais regras não mudaram: emissor, audiência, subject UUID, duração de até 15 minutos e tolerância de 30 segundos. Para testes, uma chave pública fixa (`*.auth.public-key`) substitui o JWKS; os testes usam fixtures públicas que não servem fora deles.

Trocar `USER_AUTH_PRIVATE_KEY` invalida os tokens emitidos com a chave anterior. Não há rotação com duas chaves publicadas ao mesmo tempo.

## Papéis

| Papel | Como surge | O que pode fazer além do cliente |
| --- | --- | --- |
| `CUSTOMER` | Todo cadastro em `/api/users/auth/register` | Pedidos, pagamentos, perfil e endereços próprios; consulta da própria entrega |
| `OPERATOR` | Somente a conta provisionada no startup do User | Alterar o catálogo, listar entregas, despachar, simular o ciclo e planejar rotas |

O papel fica em `user_credentials.role` (V3 do User; contas existentes viram `CUSTOMER`) e vai no claim `roles` do token, como lista. Os verificadores exigem uma lista não vazia só com papéis conhecidos e a convertem em `ROLE_CUSTOMER`/`ROLE_OPERATOR`. `GET /api/users/auth/me` passa a devolver `role`, lido do token apresentado.

O operador é criado pelo User ao iniciar quando `USER_OPERATOR_EMAIL` e `USER_OPERATOR_PASSWORD` estão definidos (as duas ou nenhuma). Se a conta não existe, é criada com nome "Operador"; se já é de operador, a senha é alinhada com a configuração; se o e-mail pertence a um cliente, o startup falha em vez de promover essa conta. O script `scripts/initialize-auth-secret.ps1` (ou `.sh`) gera a chave privada, o e-mail `operator@delivery.local` e uma senha aleatória no `.env`, sem exibi-los.

## Regras por serviço

| Serviço | Sem token | Cliente | Operador |
| --- | --- | --- | --- |
| Catalog `GET` | Liberado | Liberado | Liberado |
| Catalog `POST`/`PUT` | `401` | `403` | Liberado |
| Delivery `GET /{id}`, `/by-order/{orderId}`, `/{id}/route`, `/{id}/segments` | `401` | Só a própria entrega; outra responde `404`, igual a uma inexistente | Qualquer entrega |
| Delivery `GET /api/deliveries` (lista), entregadores, exportação CSV, planejamento de rota, observações e comandos do ciclo | `401` | `403` | Liberado |
| Order, Payment, User | Sem mudança: só o dono do recurso | | |

O dono da entrega é o `customerId` que chega no [evento de solicitação](delivery-messaging.md). Entregas criadas pelo operador em `POST /api/deliveries` não têm cliente e só aparecem para o operador. `GET /api/deliveries?status=&page=&size=` lista as mais recentes primeiro, até 100 por página.

`403` traz `application/problem+json` com "Operação permitida apenas a operadores." A diferença entre `403` e `404` é proposital: as regras por papel são públicas, mas a existência de uma entrega alheia não é.

## Limite de tentativas de login

Cada conta (e-mail normalizado) aceita 5 tentativas de login em 15 minutos. A sexta recebe `429` com `Retry-After` em segundos, mesmo com a senha certa, até a janela terminar; um login bem-sucedido zera a contagem. A tentativa é contada antes de qualquer verificação BCrypt, então uma conta bloqueada não gasta hashing e tentativas simultâneas não passam do limite. E-mails inexistentes contam do mesmo jeito.

O estado fica em memória, por instância, com no máximo 10.000 contas acompanhadas. Com mais de uma réplica do User, o limite vale por réplica. A contagem por conta permite que alguém bloqueie temporariamente o login de outra pessoa sabendo o e-mail dela; o limite por IP não foi adotado porque, atrás do Gateway, todas as requisições chegam com o mesmo endereço.

## Interface web

A sessão lê o papel do payload do token apenas para adaptar a tela; quem decide é o serviço. O cliente acompanha a entrega e a rota salva sem botões de operação. O operador ganha o menu **Operação** (`/operations`), com a lista de entregas filtrável por situação, e a página de cada entrega, com a simulação do ciclo e o cálculo da rota. O acabamento visual desse console fica para `feature/frontend-polish`.

## Configuração

| Variável | Onde | Descrição |
| --- | --- | --- |
| `USER_AUTH_PRIVATE_KEY` | User | RSA PKCS#8 em Base64, mínimo de 2048 bits; obrigatória |
| `USER_OPERATOR_EMAIL`, `USER_OPERATOR_PASSWORD` | User | Conta do operador; obrigatórias no Compose |
| `USER_AUTH_JWK_SET_URI` | Order, Payment, Catalog, Delivery | URL do JWKS; padrão `http://localhost:8081/.well-known/jwks.json` |

`USER_AUTH_SECRET` não é mais usada e pode ser removida do `.env`. Para um `.env` existente, rode o script de inicialização: ele acrescenta só as entradas ausentes. O seed `scripts/seed-demo-catalog.ps1` e o smoke entram como operador com as credenciais do `.env`.

## Validação

- Testes do User: emissão RS256 com `kid`, JWKS sem parte privada, recusa de HS256 com a chave pública, de chave estrangeira, de chave fraca ou EC; claims `roles` inválidos; provisionamento do operador, recusa de promover cliente; V3 sobre conta existente; limite de tentativas com relógio controlado, concorrência e limite de memória; `429` com `Retry-After` pela API.
- Order, Payment, Catalog e Delivery: busca e cache do JWKS num servidor HTTP local, recusa de token sem papel ou com papel desconhecido, mapeamento para authorities.
- Catalog e Delivery: `401`, `403` e `404` por papel e dono com PostgreSQL via Testcontainers; listagem paginada e filtro por situação.
- Frontend: papel lido do token, área de operação restrita, comandos e planejamento com o token do operador, cliente sem comandos.
- Smoke do Compose com `-CheckRecovery -CheckPersistence`: catálogo pelo operador e `403` para o cliente, entrega visível só ao dono e ao operador, comandos com o token do operador, reinício preservando login e dados.
