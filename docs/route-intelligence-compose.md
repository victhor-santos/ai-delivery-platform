# Demonstração de rotas com Docker Compose

O perfil `demo` executa as seis aplicações Java, Route Intelligence, a interface web e os cinco PostgreSQL, totalizando treze containers. O smoke verifica perfis e endereços e executa o fluxo com um restaurante fictício, itens de cardápio com preços preservados no pedido e os nós do grafo `synthetic-city-v1`: catálogo → pedido confirmado → pagamento simulado → entrega → plano de rota. O smoke também verifica registro/login, identidade JWT e a [autorização dos recursos](resource-authorization.md); não há ruas reais nem API de IA externa. A interface web atende em `http://localhost:3000` (`WEB_PORT`) e encaminha `/api` ao Gateway; veja a [base da interface web](frontend-foundation.md).

Sem o perfil, `docker compose up` inicia somente os cinco bancos. Os volumes existentes foram preservados; `user-db` acrescenta banco `users`, volume `user_postgres_data` e porta padrão 5436, e `payment-db` acrescenta banco `payments`, volume `payment_postgres_data` e porta padrão 5437. As migrations continuam sob responsabilidade de cada aplicação.

## Preparar e iniciar

Execute na raiz, com Docker Desktop usando containers Linux e Compose v2 ou superior. Para esta demonstração não é necessário instalar JDK, Maven, Python ou `uv` na máquina. O primeiro build precisa de internet para baixar imagens e dependências.

Se já houver `.env`, complete as entradas `USER_DB_*` a partir do `.env.example` antes de iniciar, sem substituir os valores existentes. O Compose exige `USER_DB_PASSWORD` junto às senhas dos demais bancos para resolver a configuração, mesmo ao selecionar apenas um serviço. Na execução nativa, User lê o `.env` da raiz ou suas próprias variáveis de ambiente.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/initialize-auth-secret.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-route-model.ps1
docker compose --profile demo config --quiet
docker compose --profile demo up -d --build --wait --wait-timeout 240
docker compose --profile demo ps
```

Mantenha o computador ativo enquanto builds, testes e recriação de containers estiverem em andamento. Nesta máquina, eventos de espera moderna do Windows coincidiram com timeouts: uma suspensão de oito minutos excede o prazo de quatro minutos da demonstração, mesmo sem falha da aplicação. Não é necessário desativar recursos de segurança.

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

As seis aplicações Java recebem `JAVA_TOOL_OPTIONS` pelo mesmo bloco de ambiente do Compose. O padrão `-Xms64m -Xmx384m -XX:ActiveProcessorCount=2` limita o heap a 384 MB e dimensiona os pools da JVM para dois processadores. Isso reduz a pressão de memória/threads ao iniciar ou recriar todos os serviços juntos. Não é uma cota rígida de CPU nem um limite para toda a memória do processo. `DEMO_JAVA_TOOL_OPTIONS` no `.env` ou ambiente substitui essas opções para ajustar a demonstração; execução nativa e testes Maven não recebem esse valor automaticamente.

O build Java compila e empacota com `-DskipTests`. Ele não tenta iniciar Testcontainers durante a construção da imagem. Execute os testes separadamente, conforme o README. Treinamento não acontece durante o build, startup ou chamadas HTTP.

No build Python, as dependências usam cache, mas o wheel do próprio projeto é instalado com `--no-cache` após copiar `app` e `training`. Isso impede que uma alteração de código com a mesma versão no `pyproject.toml` reutilize um pacote antigo.

Gateway recebe `USER_SERVICE_URL`, `CATALOG_SERVICE_URL`, `ORDER_SERVICE_URL`, `PAYMENT_SERVICE_URL` e `DELIVERY_SERVICE_URL`. Os padrões continuam `localhost` para execução nativa; no Compose são hostnames dos containers. Order consulta `catalog-service`, `delivery-service` e `payment-service`; Payment consulta `order-service` (`ORDER_SERVICE_URL`, `PAYMENT_REMOTE_TIMEOUT_MS`); Delivery consulta `route-intelligence-service`. User não consulta outros serviços. As URLs JDBC apontam para o banco próprio na porta interna 5432, independentemente das portas publicadas na máquina; User usa `jdbc:postgresql://user-db:5432/users`.

O perfil publica somente Gateway, Python e bancos em `127.0.0.1`. As portas 8081–8085 das aplicações Java são internas à rede Docker. Gateway atende em `API_GATEWAY_PORT` (8080 por padrão); Python em `ROUTE_INTELLIGENCE_PORT` (8000). As variáveis de portas dos bancos continuam as mesmas do README. O Compose fornece as URLs internas explicitamente, sem reutilizar URLs `localhost` do `.env`.

Os serviços com banco aguardam `service_healthy`. Order aguarda catálogo e Delivery, e Gateway aguarda os cinco backends Java. Delivery não depende da prontidão do Python: consultas e operações do ciclo continuam disponíveis durante uma falha de roteamento. `up --wait` aguarda a saúde de todos os serviços selecionados, inclusive Python. Um modelo inválido faz a demonstração falhar na prontidão; não há fallback silencioso.

As senhas dos cinco bancos e `USER_AUTH_SECRET` são obrigatórias. O script de inicialização gera uma chave aleatória quando ausente/vazia, preserva uma chave configurada e não a mostra. User, Order e Payment recebem essa chave; conserve-a entre reinícios para manter tokens ainda válidos. Veja [autenticação](authentication.md). Preserve as credenciais correspondentes aos volumes existentes; mudar o `.env` não altera um banco já inicializado. O perfil usa os mesmos volumes de desenvolvimento quando executado com o mesmo nome de projeto Compose. O `compose.yaml` fixa esse nome como `ai-delivery-platform`, então os volumes não dependem do nome da pasta do clone; `--project-name` (`-p`) continua criando um projeto isolado, com volumes próprios.

## Verificar o fluxo

Com todos os serviços saudáveis:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1
```

O script começa cadastrando uma conta com senha fictícia, verificando login, recusas de senha/token e identidade JWT. Em seguida confere e-mail normalizado e conflito de duplicidade, atualizando o nome e cadastrando/substituindo um endereço. Confere paginação, recusa chamadas sem token e responde `404` quando uma segunda conta tenta consultar ou alterar o perfil/endereço da primeira. O pedido é criado com o token do primeiro usuário e registra seu `customerId`; a segunda conta recebe `404` ao consultar, confirmar, cancelar ou solicitar entrega dele. Depois da confirmação, o smoke registra uma recusa simulada, repete sua chave, recebe `422` ao reutilizá-la com outro método, aprova com nova chave e recebe `409` numa segunda aprovação; veja [pagamentos simulados](simulated-payments.md).

Em seguida cria um restaurante e verifica cadastro, atualização de preço/disponibilidade e consultas do cardápio. Ativa o item e cria um pedido com duas unidades a 29.90, totalizando 59.80 em BRL. Altera o cardápio após a compra e confirma que consulta e confirmação do pedido preservam os nomes e valores originais. Solicita entrega duas vezes e verifica a idempotência. Planeja a rota e compara a consulta persistida com a resposta original, incluindo a identificação dos dados sintéticos. Depois atribui entregador, registra partida, entrada/saída em cada trecho e conclusão da entrega, conferindo snapshots, idempotência e exportação CSV. As travessias são explicitamente simuladas; seus tempos curtos não representam medições reais. Ele usa apenas HTTP pelo Gateway e deixa os registros de demonstração no banco. Cada execução cria novos perfis com e-mails fictícios únicos e restaurantes identificados pelo nome `Compose Demo`.

Para conferir somente [perfis e endereços](user-profiles.md), com User, seu banco e Gateway disponíveis:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -UsersOnly
```

Esse modo verifica registro/login, identidade JWT e recusa de senha/token, além de atualização do perfil, normalização/duplicidade de e-mail, paginação e associação do endereço ao perfil, consultando somente User pelo Gateway.

Para conferir somente o [cardápio](restaurant-menu.md), com catálogo, seu banco e Gateway disponíveis:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -CatalogOnly
```

Esse modo não consulta pedidos, pagamentos, usuários, Delivery ou Python.

Para verificar também [itens, totais e preços preservados dos pedidos](order-items.md), com catálogo, Order, seus bancos e Gateway disponíveis:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 -OrderOnly
```

Esse modo executa o fluxo de cardápio e a criação, consulta e confirmação do pedido, incluindo a alteração posterior do preço e da disponibilidade do item. Não consulta Delivery, Python, usuários ou pagamentos. `-UsersOnly`, `-CatalogOnly` e `-OrderOnly` são mutuamente exclusivos e nenhum deles pode ser combinado com as opções de recuperação/persistência da demonstração completa. Em execução nativa, basta iniciar os processos usados por cada modo; o perfil completo do Compose mantém suas dependências de inicialização.

Para verificar indisponibilidade e persistência:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-route-demo.ps1 `
    -CheckRecovery -CheckPersistence
```

`-CheckRecovery` interrompe Python antes da partida, espera `503 ROUTE_SERVICE_UNAVAILABLE` e confirma que entrega e plano anterior não mudaram. Restaura Python em `finally`, aguarda sua prontidão e verifica um novo planejamento. `-CheckPersistence` recria os containers com `--force-recreate`, reutilizando os volumes; depois consulta perfil, endereço, restaurante, item de cardápio, pedido, entrega, plano, observações e CSV, e repete a solicitação idempotente. Confere login após reiniciar, identidade do token anterior com a mesma chave, os dados atualizados do perfil/endereço e a moeda, total e composição preservada do pedido, mesmo com os dados atuais do cardápio diferentes dos snapshots. Essas opções interrompem temporariamente os serviços da demonstração; use-as quando não houver outras operações em andamento.

Se alterar a porta do Gateway, informe `-GatewayUrl http://localhost:NOVA_PORTA`. Para um projeto Compose isolado, informe também `-ComposeProject` e `-EnvFile` com os mesmos valores usados ao iniciar o ambiente. Antes de criar dados ou interromper serviços, as verificações de recuperação/persistência exigem um Gateway local cuja porta corresponda à publicada pelo projeto selecionado. Só mudar o endereço HTTP não muda o projeto que os testes de recuperação operam.

Para inspecionar problemas e encerrar preservando dados:

```powershell
docker compose --profile demo logs --tail 100 route-intelligence-service delivery-service
docker compose --profile demo stop
```

`docker compose --profile demo down` remove containers e rede, mas preserva os volumes quando usado sem `--volumes`. Não remova volumes para preparar ou recuperar esta demonstração.

## Validação da introdução do Compose

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

O smoke também cobre as [observações por trecho](delivery-segment-observations.md), que associam previsão e travessia simulada. Interface web continua em uma etapa posterior.

## Validação de itens de pedidos em 05/10/2026

Após a integração dos itens e preços, Order passou em `clean verify` com 270 testes, sem falhas, erros ou casos ignorados, e gerou o JAR executável. Esse resultado se refere à suíte de Order; as contagens dos outros serviços acima são o registro histórico de 03/10/2026.

A demonstração foi executada novamente em um projeto Compose isolado, com construção das sete imagens e prontidão dos dez containers. Passaram o modo `-OrderOnly` e o fluxo completo com `-CheckRecovery -CheckPersistence`. Foram verificados:

- Pedido com duas unidades, total de 59.80 em BRL e preservação dos valores na consulta e confirmação após mudança do nome, preço e disponibilidade no catálogo.
- Entrega idempotente, planejamento de rota, travessias explicitamente simuladas e exportação CSV.
- Interrupção de Python com resposta `503`, preservação do estado e do plano e recuperação após seu reinício.
- Recriação dos containers mantendo pedido com itens, total e moeda, entrega, plano e observações/CSV nos volumes do projeto isolado.

O ambiente descartável foi encerrado e seus volumes próprios removidos ao final. Nenhum banco, volume ou arquivo `.env` de desenvolvimento foi alterado.

## Validação de perfis de usuários em 05/10/2026

User passou em `clean verify` com 276 testes, sem falhas, erros ou casos ignorados, e gerou o JAR executável. A suíte cobre cadastro concorrente com e-mail duplicado, validação, API, persistência, ownership dos endereços e constraints. Esse resultado se refere somente a User; as contagens dos outros serviços acima continuam sendo registros históricos.

A imagem de User foi construída e os demais serviços reutilizaram as imagens já validadas na feature anterior. O projeto Compose isolado iniciou onze containers saudáveis, com portas próprias, credenciais de teste e quatro volumes descartáveis. Passaram:

- `-UsersOnly`: perfil, e-mail normalizado/único, atualização efetiva do nome e dos dados do endereço, paginação e rejeição do endereço sob outro usuário.
- `-OrderOnly`: seleção do cardápio, total BRL e preservação dos preços do pedido.
- Fluxo completo: perfis e endereços, pedido com itens, entrega idempotente, rota, travessias simuladas e exportação CSV.
- Recuperação do Python: `503` durante a interrupção, estado/plano preservados e novo planejamento após recuperação.
- Recriação dos containers reutilizando volumes: perfil, endereço, cardápio, pedido, entrega, plano, observações/CSV e idempotência preservados.

Ao final, o ambiente descartável e seus volumes próprios foram removidos. Os três bancos de desenvolvimento existentes permaneceram saudáveis; nenhum volume ou `.env` de desenvolvimento foi alterado.

## Validação de autenticação em 06/10/2026

User passou em `clean verify` com 329 testes e JAR executável, incluindo cadastro atômico, BCrypt, JWT, rollback, concorrência e preservação dos legados na V2. Somente sua imagem foi reconstruída; os demais serviços reutilizaram imagens existentes, sem repetir suas suítes Java/Python.

O Compose final do repositório, sem override externo, iniciou onze containers saudáveis em projeto isolado, com portas, credenciais, chave aleatória e volumes próprios. Passaram `-UsersOnly`, `-OrderOnly` e o fluxo completo com recuperação/persistência: registro/login, recusas de senha/token, identidade JWT, perfis/endereços, snapshots de preço, entrega, rota e observações/CSV. Após recriar containers, o login e o token anterior continuaram resolvendo o mesmo perfil com a chave preservada. A recuperação do Python também passou.

Durante tentativas anteriores, eventos de espera moderna do Windows coincidiram com os timeouts de startup. A execução final manteve o computador ativo somente durante o teste, sem alterar o plano de energia nem ampliar os timeouts. O ambiente descartável e seus volumes foram removidos; os três bancos locais permaneceram saudáveis e o `.env` não foi alterado.

Referências: [profiles do Compose](https://docs.docker.com/compose/how-tos/profiles/), [ordem e saúde das dependências](https://docs.docker.com/compose/how-tos/startup-order/), [montagens do Compose](https://docs.docker.com/reference/compose-file/services/) e [instalação com uv em Docker](https://docs.astral.sh/uv/guides/integration/docker/).
