# Base do serviço Route Intelligence

`services/route-intelligence-service` executa uma API FastAPI independente. Esta etapa acrescentou configuração, dependências reproduzíveis, health check e testes. Grafo e Dijkstra foram acrescentados depois, com [demonstração por terminal](road-graph.md). Dados, modelo e consulta HTTP de rotas seguem o [roadmap](roadmap.md).

## Organização

| Arquivo | Responsabilidade |
| --- | --- |
| `app/main.py` | Factory da aplicação, metadados e registro de routers |
| `app/__main__.py` | Inicialização do Uvicorn com configuração validada |
| `app/config.py` | Leitura e validação das variáveis de ambiente com Pydantic Settings |
| `app/api/health.py` | Contrato HTTP de disponibilidade |
| `tests/` | Configuração e contrato HTTP com pytest e TestClient |
| `pyproject.toml` | Metadados, dependências, grupo de desenvolvimento e configuração de ferramentas |
| `uv.lock` | Versões e hashes das dependências resolvidas |
| `.python-version` | Python 3.12 como versão de referência |

Não há diretórios vazios para funcionalidades futuras. O serviço não usa banco, não exige Docker e não é encaminhado pelo Gateway. Os serviços Java continuam executando independentemente dele.

## Preparar e executar

Instale [uv](https://docs.astral.sh/uv/getting-started/installation/). Os comandos abaixo usam PowerShell e partem da raiz do repositório. A instalação do Python e das dependências precisa de internet na primeira execução:

```powershell
uv --version
uv python install 3.12
# Criar o ambiente com a biblioteca padrão evita o launcher nativo do uv,
# que pode ser bloqueado pelo Controle de Aplicativo do Windows.
& (uv python find --system 3.12) -m venv .\services\route-intelligence-service\.venv
uv sync --project .\services\route-intelligence-service --locked
uv run --project .\services\route-intelligence-service --locked python -m app
```

Use `Ctrl+C` para parar. O comando executa Uvicorn em `127.0.0.1:8000`, sem treinamento, downloads de artefatos ou conexões com outros serviços. `python -m app` também evita executáveis de console gerados pelo `uv`, que podem ser bloqueados pela mesma política do Windows. Não é necessário alterar essa política.

O ambiente `.venv`, caches e arquivos de build são ignorados pelo Git. `uv sync --locked` verifica se o lock corresponde ao `pyproject.toml` e instala suas versões; para atualizar dependências, altere o projeto explicitamente, regenere o lock e valide antes de commitar. O grupo `dev` inclui pytest, o cliente HTTP de testes e Ruff. Use `uv sync --project .\services\route-intelligence-service --no-dev --locked` para instalar apenas as dependências de execução.

## Configuração

| Variável | Padrão | Validação |
| --- | --- | --- |
| `ROUTE_INTELLIGENCE_HOST` | `127.0.0.1` | Texto não vazio após remover espaços nas extremidades |
| `ROUTE_INTELLIGENCE_PORT` | `8000` | Inteiro entre 1 e 65535 |
| `ROUTE_INTELLIGENCE_LOG_LEVEL` | `info` | `critical`, `error`, `warning`, `info`, `debug` ou `trace` |

As variáveis são lidas do ambiente do processo; o `.env` dos serviços Java não é carregado pelo Python. Configuração inválida gera erro antes de iniciar o servidor. Endereço inválido ou porta ocupada também impedem o startup.

Exemplo para escolher outra porta:

```powershell
$env:ROUTE_INTELLIGENCE_PORT = '8100'
uv run --project .\services\route-intelligence-service --locked python -m app
# Depois de encerrar, restaure o padrão neste terminal:
Remove-Item Env:ROUTE_INTELLIGENCE_PORT
```

## Contrato disponível

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/openapi.json
```

`GET /health` retorna HTTP `200`, `application/json` e `{"status":"UP"}`. Ele mede apenas a disponibilidade da aplicação. A documentação interativa está em `http://127.0.0.1:8000/docs`.

`POST /api/routes/fastest` ainda não existe. A verificação de prontidão de grafo, schema e modelo será acrescentada com a inferência, conforme o [contrato Java ↔ Python](route-intelligence-contract.md). Portanto, `UP` nesta etapa não significa que seja possível planejar uma entrega.

## Validação

Da raiz:

```powershell
uv run --project .\services\route-intelligence-service --locked python -m pytest .\services\route-intelligence-service\tests
uv run --project .\services\route-intelligence-service --locked python -m ruff check .\services\route-intelligence-service
uv run --project .\services\route-intelligence-service --locked python -m ruff format --check .\services\route-intelligence-service
uv build --project .\services\route-intelligence-service
```

O build gera wheel e distribuição de fontes no `dist/` do serviço. Os testes tratam warnings como erros. TestClient inicia e encerra a aplicação por contexto, preparado para os recursos de startup que serão adicionados depois.

Verificado em 2026-10-02, com Python 3.12.14 e uv 0.12.11:

- 14 testes passaram: padrões, variáveis de ambiente, portas inválidas e limites, host vazio, nível de log inválido, health HTTP e schema OpenAPI.
- Ruff passou para imports, análise estática e formatação.
- Wheel e distribuição de fontes foram gerados; o wheel foi instalado em ambiente separado e importado fora do repositório, sem depender da instalação editável.
- O servidor real foi iniciado pelo comando acima, respondeu ao health e ao OpenAPI em uma porta temporária e foi encerrado. Porta `0` foi rejeitada antes de iniciar.

Esta etapa não altera Java, migrations, Gateway ou Compose. As validações acima cobrem o novo serviço; as suítes Maven não foram repetidas nesta feature.
