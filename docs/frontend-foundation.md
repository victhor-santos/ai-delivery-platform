# Base da interface web

A aplicação em [`frontend/`](../frontend) é a interface web da V1. Nesta etapa ela permite criar conta, entrar, consultar o perfil autenticado e sair. Restaurantes, cardápio, pedido e pagamento estão no [checkout](frontend-checkout.md), e o acompanhamento das entregas em `feature/frontend-deliveries`.

## Escolhas

| Tema | Decisão |
| --- | --- |
| Framework | React 19 com TypeScript, empacotado pelo Vite; rotas com React Router |
| Testes | Vitest com jsdom e Testing Library; `fetch` é substituído nos testes, sem servidor real |
| Lint | oxlint, com avisos tratados como erro |
| Comunicação | Somente pelo Gateway, sob `/api`, na mesma origem da página |

O navegador nunca chama o Gateway em outra origem. Em desenvolvimento, o servidor do Vite encaminha `/api` ao Gateway (`GATEWAY_URL`, padrão `http://localhost:8080`). No Compose, o nginx do container `web` serve o build e encaminha `/api` para `api-gateway:8080`. Com uma única origem, o Gateway não precisa de configuração CORS e os serviços continuam sem conhecer a interface.

## Autenticação

O cadastro usa `POST /api/users/auth/register` e, em seguida, entra automaticamente com as mesmas credenciais. O login usa `POST /api/users/auth/login`, e a página inicial consulta `GET /api/users/auth/me` com o Bearer token. Os contratos estão em [autenticação](authentication.md).

- O token e o instante de expiração (`expiresIn` do login) ficam no `sessionStorage`: sobrevivem ao recarregar a aba e somem ao fechá-la. Uma sessão salva já vencida ou malformada é descartada.
- Não há refresh token. A sessão termina no vencimento, por um temporizador, ou quando o servidor responde `401` a uma chamada autenticada. Nos dois casos, a página de login informa que a sessão expirou.
- Sair apaga o token localmente; no servidor, ele continua válido até expirar, como descrito na autenticação.
- Páginas protegidas redirecionam para o login e voltam ao destino original depois de entrar.
- A interface recusa senhas com menos de 12 caracteres antes de chamar o servidor. As demais regras, como o limite de 72 bytes, continuam no User Service.

Guardar o token no `sessionStorage` o deixa acessível a scripts da página. Isso é aceitável para esta demonstração local, que não carrega scripts de terceiros. Um cookie `HttpOnly` exigiria mudanças no contrato de autenticação e proteção CSRF.

## Tratamento de erros

`src/api/client.ts` centraliza as chamadas. Para erros `4xx` em `application/problem+json`, o `detail` do servidor é mostrado ao usuário (por exemplo, "E-mail já cadastrado."). Sem `detail`, ou com respostas que não são JSON, a interface usa uma mensagem própria por status. Erros `5xx` nunca exibem o detalhe do servidor, e falhas de rede viram "Não foi possível conectar ao servidor". Um erro de renderização mostra uma mensagem no lugar de uma página em branco. Caminhos desconhecidos levam a uma página "não encontrada".

## Execução

Com Node.js 22.22 ou superior e o Gateway em `http://localhost:8080` (nativo ou pelo Compose):

```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173
npm run lint
npm test
npm run build      # checagem de tipos e build em dist/
```

Na demonstração em containers, o perfil `demo` constrói a imagem `web` e a publica em `http://localhost:3000` (`WEB_PORT`). O container só inicia depois que o Gateway fica saudável e tem health check próprio em `/healthz`. A imagem roda o nginx sem root. Arquivos com hash no nome ficam em cache; `index.html` não, e qualquer caminho desconhecido devolve a SPA.

O smoke completo confere o fallback da SPA e uma chamada autenticada a `/api/users/auth/me` passando pelo nginx. Use `-WebUrl` se a porta publicada for outra. Os modos parciais (`-UsersOnly`, `-CatalogOnly`, `-OrderOnly`) não verificam a interface.

## CI

O job `Frontend (web)` executa `npm ci`, lint, testes e build com Node 22. Veja a [integração contínua](ci-validation.md).

## Validação

Em 08/10/2026, no Ubuntu:

- `npm run lint`, `npm test` (3 arquivos, 23 testes) e `npm run build` passaram, inclusive sobre `git archive HEAD` com apenas os arquivos versionados, como no checkout do CI;
- `actionlint` sem apontamentos no workflow;
- `docker compose --profile demo up -d --build --wait` iniciou os treze containers saudáveis;
- pela porta 3000: cadastro `201`, cadastro repetido `409` com `detail`, login, `/auth/me` com o token, login incorreto `401` e fallback da SPA;
- `scripts/smoke-route-demo.ps1` passou no fluxo completo e com `-CheckRecovery -CheckPersistence`, incluindo a nova verificação da interface e a recriação dos containers com o `web`. Com `-WebUrl` apontando para uma porta sem serviço, falhou como esperado.

Os serviços Java e Python não mudaram nesta etapa, e suas suítes não foram reexecutadas.
