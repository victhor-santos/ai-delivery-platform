# Integração contínua

O workflow [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) executa no GitHub Actions os mesmos builds e testes usados localmente. Ele roda em todo pull request para `main`, em todo push na `main` (inclusive o squash merge) e manualmente pela aba **Actions** (`workflow_dispatch`). Um push novo na mesma branch cancela a execução anterior ainda em andamento.

## Jobs

| Job | O que executa |
| --- | --- |
| `Java (<aplicação>)` | `./mvnw -B -ntp verify` com Temurin 21, um job por aplicação: Gateway, User, Catalog, Order, Payment e Delivery |
| `Python (route-intelligence-service)` | `uv sync --locked`, `ruff check`, `ruff format --check` e `pytest` |
| `Frontend (web)` | `npm ci`, `npm run lint` (oxlint), `npm test` (Vitest) e `npm run build` com Node 22 |

Os seis jobs Java rodam em paralelo e com `fail-fast: false`: a falha de um serviço não interrompe os outros, e o resumo mostra todos os resultados. As integrações com PostgreSQL 17 usam Testcontainers no Docker do runner `ubuntu-24.04`, sem Compose, `.env` ou segredos. O cache do Maven usa o `pom.xml` de cada aplicação como chave, o cache do uv usa o `uv.lock` e o do npm usa o `frontend/package-lock.json`.

O workflow tem apenas permissão de leitura do repositório (`contents: read`). Ele não publica imagens, não faz deploy e não acessa segredos.

## Fora do escopo

- O smoke do Compose (`scripts/smoke-route-demo.ps1`) não roda no CI. Ele depende do bundle do modelo, que é gerado localmente e fica fora do Git, e continua sendo validado antes de cada PR, como descrito no [guia do Compose](route-intelligence-compose.md).
- A imagem `web` do Compose não é construída no CI, assim como as imagens Java e Python. O job do frontend valida o mesmo build que ela empacota; veja a [base da interface web](frontend-foundation.md).

## Validação

Antes do primeiro push, em 07/10/2026 no Ubuntu, os comandos dos jobs foram executados sobre `git archive HEAD`, com apenas os arquivos versionados, como no checkout do runner:

- `actionlint` sem apontamentos no workflow;
- Python 3.12 com uv 0.12.23, em container: Ruff sem apontamentos, 77 arquivos formatados e 542 testes aprovados;
- `./mvnw -B -ntp verify` nas seis aplicações Java, sem falhas, erros ou casos ignorados: Gateway 6, User 355, Catalog 262, Order 293, Payment 115 e Delivery 289 testes.

A primeira execução no GitHub aconteceu no PR #32. O job do frontend foi acrescentado em `feature/frontend-foundation` e validado localmente sobre `git archive HEAD` em 08/10/2026, com `actionlint` sem apontamentos.
