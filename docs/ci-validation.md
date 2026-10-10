# Integração contínua

O workflow [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) executa no GitHub Actions os mesmos builds e testes usados localmente. Ele roda em todo pull request para `main`, em todo push na `main` (inclusive o squash merge) e manualmente pela aba **Actions** (`workflow_dispatch`). Um push novo na mesma branch cancela a execução anterior ainda em andamento.

## Jobs

| Job | O que executa |
| --- | --- |
| `Java (<aplicação>)` | `./mvnw -B -ntp verify` com Temurin 21, um job por aplicação: Gateway, User, Catalog, Order, Payment e Delivery |
| `Python (route-intelligence-service)` | `uv sync --locked`, `ruff check`, `ruff format --check` e `pytest` |
| `Frontend (web)` | `npm ci`, `npm run lint` (oxlint), `npm test` (Vitest) e `npm run build` com Node 22 |
| `End to end (Compose demo)` | Depois dos anteriores: `.env` descartável, treino do modelo, perfil `demo` do Compose, smoke com `-CheckRecovery -CheckPersistence` e testes do navegador com Playwright; veja a [validação da release V1](v1-release-validation.md) |

Os seis jobs Java rodam em paralelo e com `fail-fast: false`: a falha de um serviço não interrompe os outros, e o resumo mostra todos os resultados. As integrações com PostgreSQL 17 usam Testcontainers no Docker do runner `ubuntu-24.04`, sem Compose, `.env` ou segredos. O cache do Maven usa o `pom.xml` de cada aplicação como chave, o cache do uv usa o `uv.lock` e o do npm usa o `frontend/package-lock.json`.

O job de ponta a ponta gera chave e senha do operador com `scripts/initialize-auth-secret.sh` só para a execução; nada vem de Secrets. Em falha, publica os logs dos serviços e o relatório do Playwright (artefato `playwright-report`, 7 dias).

O workflow tem apenas permissão de leitura do repositório (`contents: read`). Ele não publica imagens, não faz deploy e não acessa segredos.

## Fora do escopo

- As imagens são construídas no job de ponta a ponta apenas para os testes; nenhuma é publicada.
- O bundle do modelo usado no CI é treinado a cada execução e descartado; o bundle local continua fora do Git.

## Validação

Antes do primeiro push, em 07/10/2026 no Ubuntu, os comandos dos jobs foram executados sobre `git archive HEAD`, com apenas os arquivos versionados, como no checkout do runner:

- `actionlint` sem apontamentos no workflow;
- Python 3.12 com uv 0.12.23, em container: Ruff sem apontamentos, 77 arquivos formatados e 542 testes aprovados;
- `./mvnw -B -ntp verify` nas seis aplicações Java, sem falhas, erros ou casos ignorados: Gateway 6, User 355, Catalog 262, Order 293, Payment 115 e Delivery 289 testes.

A primeira execução no GitHub aconteceu no PR #32. O job do frontend foi acrescentado em `feature/frontend-foundation` e validado localmente sobre `git archive HEAD` em 08/10/2026, com `actionlint` sem apontamentos. O job de ponta a ponta foi acrescentado em `feature/v1-release-validation`; em 10/10/2026 seus passos foram ensaiados localmente sobre `git archive HEAD`, em projeto Compose separado, e o `actionlint` não apontou problemas.
