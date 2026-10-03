# Demonstração de rotas com Docker Compose

O perfil `demo` executa as seis aplicações Java, Route Intelligence e os três PostgreSQL. O fluxo usa um restaurante fictício e os nós do grafo `synthetic-city-v1`: catálogo → pedido confirmado → entrega → plano de rota. Não há pagamento, interface web, ruas reais ou API de IA externa.

Sem o perfil, `docker compose up` continua iniciando somente os bancos. Nomes de serviços, volumes e diretórios de dados dos PostgreSQL foram preservados. As migrations continuam sob responsabilidade de cada aplicação.

## Preparar e iniciar

Execute na raiz, com Docker Desktop usando containers Linux e Compose v2 ou superior. Para esta demonstração não é necessário instalar JDK, Maven, Python ou `uv` na máquina. O primeiro build precisa de internet para baixar imagens e dependências.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-route-model.ps1
docker compose --profile demo config --quiet
docker compose --profile demo up -d --build --wait --wait-timeout 240
docker compose --profile demo ps
```

Nesta máquina, a política do PowerShell bloqueia arquivos `.ps1`. `-ExecutionPolicy Bypass` vale somente para o processo que executa o script; não altera a política permanente nem exige desativar o antivírus.

O script de preparação constrói a imagem Python e verifica um bundle existente. Se ele for compatível, encerra sem treinar. Se não houver bundle, gera o dataset quando necessário, treina e avalia no teste reservado, usando comandos offline na mesma imagem da API. Os diretórios de saída nunca são sobrescritos. Um bundle existente, incompleto ou incompatível causa erro; escolha um novo diretório para regenerar:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-route-model.ps1 `
    -ArtifactDirectory services/route-intelligence-service/artifacts/segment-model-v2-linux
```

Nesse caso, ajuste `ROUTE_INTELLIGENCE_MODEL_DIR` no `.env` para o novo diretório. O padrão é `./services/route-intelligence-service/artifacts/segment-model-v1`. Dataset, modelos, relatórios, `.env`, caches e builds locais ficam fora do contexto Docker; os dados gerados continuam ignorados pelo Git. O script também aceita `-DatasetDirectory` para uma nova geração independente.

O carregador exige plataforma, arquitetura, Python e bibliotecas iguais aos metadados. A referência validada é Linux x86_64, Python 3.12.14 e as versões do `uv.lock`. O bundle `segment-model-v1-windows` serve para execução nativa no Windows e é recusado pela imagem Linux. Em outra arquitetura, gere o bundle com a imagem usada naquela máquina. Consuma apenas artefatos de origem confiável: checksum não torna um arquivo `joblib` desconhecido seguro.

## Imagens, rede e prontidão

| Componente | Build e execução |
| --- | --- |
| Java | `Dockerfile.java` compartilhado, com `SERVICE_PATH`; Maven Wrapper e JDK 21 no build; JRE 21 no runtime |
| Python | Dockerfile próprio, Python 3.12.14 e `uv` 0.12.11; instalação com `uv sync --locked --no-dev --no-editable` |
| Modelos | Bind mount em `/models`, somente leitura; diretório ausente não é criado automaticamente |
| Usuário | As sete aplicações executam com UID 10001; ferramentas de build e dependências de testes não entram nos runtimes |
| Saúde Java | `curl` em `/actuator/health`, usando `SERVER_PORT`; os serviços com persistência verificam seu banco |
| Saúde Python | `/health`; somente `200 UP` quando grafo, modelo e tráfego estão prontos |

O build Java compila e empacota com `-DskipTests`. Ele não tenta iniciar Testcontainers durante a construção da imagem. Execute os testes separadamente, conforme o README. Treinamento não acontece durante o build, startup ou chamadas HTTP.

Gateway recebe `USER_SERVICE_URL`, `CATALOG_SERVICE_URL`, `ORDER_SERVICE_URL`, `PAYMENT_SERVICE_URL` e `DELIVERY_SERVICE_URL`. Os padrões continuam `localhost` para execução nativa; no Compose são hostnames dos containers. Order consulta `catalog-service` e `delivery-service`; Delivery consulta `route-intelligence-service`. As URLs JDBC apontam para o banco próprio na porta interna 5432, independentemente das portas publicadas na máquina.

O perfil publica somente Gateway, Python e bancos em `127.0.0.1`. As portas 8081–8085 das aplicações Java são internas à rede Docker. Gateway atende em `API_GATEWAY_PORT` (8080 por padrão); Python em `ROUTE_INTELLIGENCE_PORT` (8000). As variáveis de portas dos bancos continuam as mesmas do README. O Compose fornece as URLs internas explicitamente, sem reutilizar URLs `localhost` do `.env`.

Os serviços com banco aguardam `service_healthy`. Order aguarda catálogo e Delivery, e Gateway aguarda os cinco backends Java. Delivery não depende da prontidão do Python: consultas e operações do ciclo continuam disponíveis durante uma falha de roteamento. `up --wait` aguarda a saúde de todos os serviços selecionados, inclusive Python. Um modelo inválido faz a demonstração falhar na prontidão; não há fallback silencioso.

As senhas dos três bancos são obrigatórias. Preserve as credenciais correspondentes aos volumes existentes; mudar o `.env` não altera um banco já inicializado. O profile usa os mesmos volumes de desenvolvimento quando executado com o mesmo nome de projeto Compose.

## Verificar o fluxo

Com todos os serviços saudáveis:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1
```

O script cria um restaurante, confirma um pedido, solicita entrega duas vezes e verifica a idempotência. Planeja a rota e compara a consulta persistida com a resposta original, incluindo a identificação dos dados sintéticos. Ele usa apenas HTTP pelo Gateway e deixa os registros de demonstração no banco. Cada execução cria novos registros identificados pelo nome `Compose Demo`.

Para verificar indisponibilidade e persistência:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 `
    -CheckRecovery -CheckPersistence
```

`-CheckRecovery` interrompe Python, espera `503 ROUTE_SERVICE_UNAVAILABLE` e confirma que entrega e plano anterior não mudaram. Restaura Python em `finally`, aguarda sua prontidão e verifica um novo planejamento. `-CheckPersistence` recria os containers com `--force-recreate`, reutilizando os volumes; depois consulta restaurante, pedido, entrega e plano e repete a solicitação idempotente. Essas opções interrompem temporariamente os serviços da demonstração; use-as quando não houver outras operações em andamento.

Se alterar a porta do Gateway, informe `-GatewayUrl http://localhost:NOVA_PORTA`. Para um projeto Compose isolado, informe também `-ComposeProject` e `-EnvFile` com os mesmos valores usados ao iniciar o ambiente. Antes de criar dados ou interromper serviços, as verificações de recuperação/persistência exigem um Gateway local cuja porta corresponda à publicada pelo projeto selecionado. Só mudar o endereço HTTP não muda o projeto que os testes de recuperação operam.

Para inspecionar problemas e encerrar preservando dados:

```powershell
docker compose --profile demo logs --tail 100 route-intelligence-service delivery-service
docker compose --profile demo stop
```

`docker compose --profile demo down` remove containers e rede, mas preserva os volumes quando usado sem `--volumes`. Não remova volumes para preparar ou recuperar esta demonstração.

## Validação desta etapa

Em 03/10/2026, a demonstração foi executada em um projeto Compose isolado, com portas 18080/18000 para Gateway/Python e 15432/15434/15435 para bancos. O `.env` e os volumes de desenvolvimento não foram alterados. Foram conferidos:

- Construção das sete imagens e prontidão dos dez containers.
- Encaminhamento dos cinco `/ping` pelo Gateway e fluxo completo de criação e planejamento.
- Indisponibilidade de Python, preservação do plano e recuperação com novo planejamento.
- Recriação dos containers preservando restaurante, pedido, entrega, plano e idempotência.
- Endereço do Gateway incompatível com o projeto rejeitado antes de criar registros ou interromper serviços.
- Preparação offline em diretórios novos e reutilização do bundle Linux existente, sem sobrescrever artefatos anteriores.
- Bundle Windows recusado na imagem Linux, com `503` no health e na consulta válida de rota.
- Testes Python em Linux: 330 aprovados; Ruff e formatação aprovados nos 65 arquivos existentes.

As suítes Java somam 474 testes: Gateway 6, Catalog 131, Order 92, Delivery 243, User 1 e Payment 1. A asserção de timeout do Order foi corrigida para aceitar timeout durante a conexão; nenhum comportamento de produção foi alterado. Os acessores obsoletos de texto do Jackson foram substituídos pelos equivalentes atuais, e os testes Java passaram a configurar o agente Mockito explicitamente, seguindo o padrão já existente no Delivery.

Os containers e a rede do projeto isolado foram encerrados ao final, preservando seus volumes. Os três bancos de desenvolvimento permaneceram em execução.

O Gateway ainda registra `HV000271` para anotações de validação em classes do Spring Cloud. A JVM registra o aviso de compartilhamento de classes quando o agente de testes está ativo. São avisos de dependências/instrumentação; não foram ocultados nem houve atualização de frameworks nesta etapa.

A próxima etapa do roadmap é [registrar observações por trecho](roadmap.md), associando previsão e travessia observada. Interface web continua em uma etapa posterior.

Referências: [profiles do Compose](https://docs.docker.com/compose/how-tos/profiles/), [ordem e saúde das dependências](https://docs.docker.com/compose/how-tos/startup-order/), [montagens do Compose](https://docs.docker.com/reference/compose-file/services/) e [instalação com uv em Docker](https://docs.astral.sh/uv/guides/integration/docker/).
