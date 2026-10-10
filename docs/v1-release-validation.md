# Validação da release V1

A V1 cobre o fluxo cadastro → cardápio → pedido com valores preservados → pagamento simulado → entrega → acompanhamento da rota na interface web. Este documento registra como esse fluxo é validado de ponta a ponta, como repetir a validação, quais falhas estão cobertas, os limites conhecidos e o checklist de release.

## Camadas de validação

| Camada | Onde roda | O que garante |
| --- | --- | --- |
| Testes de cada aplicação | Jobs `Java`, `Python` e `Frontend` do [CI](ci-validation.md) | Domínio, persistência com PostgreSQL (Testcontainers), contratos HTTP, segurança, mensageria e componentes React, cada aplicação isolada |
| Smoke da API | [`scripts/smoke-route-demo.ps1`](../scripts/smoke-route-demo.ps1) contra o Compose | Fluxo completo pelo Gateway, autorização entre contas, idempotência, queda do broker e do Python, recriação dos containers |
| Testes no navegador | [`e2e/`](../e2e), Playwright contra o Compose | O mesmo fluxo pela interface web, como cliente e como operador, e as mensagens de falha e retomada |

O job `End to end (Compose demo)` do CI executa as duas últimas camadas sobre um checkout limpo: gera um `.env` descartável a partir do `.env.example`, treina o modelo, sobe o perfil `demo`, roda o smoke com `-CheckRecovery -CheckPersistence` e depois o Playwright. Ele só começa quando os jobs das aplicações passam.

## Testes no navegador

Os testes usam Chromium, entram pela interface em `http://localhost:3000` e não acessam bancos nem serviços diretamente. A preparação (restaurante, contas, pedidos de apoio) e as conferências que a tela não mostra, como o número de cobranças, usam a API do Gateway pelo `/api` da própria interface.

| Cenário | O que prova |
| --- | --- |
| Cliente pede, paga e acompanha a entrega conduzida pela operação | Cadastro pela tela, carrinho com total de R$ 33,00, destino na cidade sintética, pagamento recusado seguido de aprovado, solicitação de entrega consumida pelo RabbitMQ, operador encontrando a entrega na fila, rota calculada de A até C, ciclo completo até **Entregue** e o cliente vendo a entrega concluída com a rota salva; no fim, exatamente uma recusa e uma aprovação |
| Login com senha errada | A tela mostra o erro, a pessoa continua em **Entrar** e as páginas protegidas seguem redirecionando |
| Pedido de outra pessoa e área da operação | O pedido alheio não aparece nem pode ser pago; um cliente não acessa **Operação** |
| Pedido cancelado | Depois do cancelamento não há pagamento nem entrega |
| Resposta do pagamento perdida | O servidor aprova, mas a resposta não chega ao navegador; a tela avisa que a tentativa pode ser retomada sem nova cobrança, mostra o pedido confirmado e existe uma única cobrança |
| Falha no cálculo da rota | A tela informa que o serviço de rotas não respondeu e uma nova tentativa calcula a rota |

Os dois últimos cenários simulam a falha no próprio navegador (`page.route`): o objetivo é conferir a reação da interface. As quedas reais do RabbitMQ e do serviço Python são provocadas pelo smoke, que para e religa os containers.

Cada teste cria o próprio restaurante (`E2E <sufixo>`, coleta no ponto A) e contas `e2e-*@example.test`, então os testes não dependem do seed nem uns dos outros e podem rodar em paralelo. Esses registros ficam no banco, como os do smoke.

## Como executar

Com Docker, Node.js 22 e PowerShell 7 (`pwsh`), na raiz do repositório. No Windows, use os scripts `.ps1` equivalentes, como no [README](../README.md#demonstração-em-containers).

```bash
cp -n .env.example .env
scripts/initialize-auth-secret.sh
pwsh -NoProfile -File scripts/prepare-route-model.ps1
docker compose --profile demo up -d --build --wait --wait-timeout 600

pwsh -NoProfile -File scripts/smoke-route-demo.ps1 -CheckRecovery -CheckPersistence

cd e2e
npm ci
npx playwright install chromium   # no Linux sem as bibliotecas do navegador: --with-deps
npm test
```

O smoke com `-CheckRecovery` para e religa o RabbitMQ e o serviço Python, e `-CheckPersistence` recria todos os containers sem remover volumes. Para validar só a interface, pule o smoke.

| Variável | Uso |
| --- | --- |
| `E2E_WEB_URL` | Endereço da interface (padrão `http://localhost:3000`) |
| `E2E_ENV_FILE` | `.env` de onde ler a conta do operador (padrão: o da raiz) |
| `USER_OPERATOR_EMAIL`, `USER_OPERATOR_PASSWORD` | Conta do operador, se definidas no ambiente; têm precedência sobre o arquivo e nunca são impressas |

Em falha, `e2e/test-results/` guarda captura de tela e trace de cada teste (`npx playwright show-trace <trace.zip>`). No CI, o relatório HTML do Playwright e os logs dos serviços ficam no artefato `playwright-report` e no log do job.

Os testes não repetem casos que falham (`retries: 0`): numa validação de release, instabilidade precisa aparecer.

## Falhas e retomada cobertas

| Situação | Comportamento esperado | Verificado em |
| --- | --- | --- |
| RabbitMQ fora do ar | O pedido aceita a solicitação de entrega; o outbox a publica quando o broker volta | Smoke `-CheckRecovery` |
| Serviço Python fora do ar | `503` com `ROUTE_SERVICE_UNAVAILABLE`; plano anterior e entrega preservados; replanejar funciona depois | Smoke `-CheckRecovery`; mensagem e nova tentativa na interface pelo Playwright |
| Containers recriados | Contas, tokens válidos, pedidos, pagamentos, entregas, planos, observações e CSV iguais; nova solicitação não duplica a entrega | Smoke `-CheckPersistence` |
| Pagamento recusado | Pedido continua aberto; outro método confirma | Smoke e Playwright |
| Resposta do pagamento perdida | A interface consulta o pedido e não cobra de novo | Playwright |
| Mesma chave de pagamento repetida ou reutilizada | Mesma tentativa; `422` com outro método; `409` numa segunda aprovação | Smoke |
| Solicitação de entrega repetida | Mesmo recibo e uma única entrega | Smoke |
| Recurso de outra conta | `404` na API; nada aparece na interface | Smoke e Playwright |
| Cliente em área ou comando do operador | `403` na API; aviso na interface | Smoke e Playwright |
| Senha errada e excesso de tentativas | `401` e, a partir da sexta tentativa em 15 minutos, `429` | Playwright (senha errada); testes do User Service (limite), ver [reforço de segurança](security-hardening.md) |

## Limites conhecidos da V1

- Pagamentos, ruas, trânsito e travessias são simulados. O modelo em uso foi treinado com dados sintéticos e não mede qualidade no mundo real. O modelo observacional não é servido pela API sem a [decisão de promoção](model-promotion-validation.md).
- A cidade tem sete pontos sintéticos. Destinos fora deles criam o pedido, mas a entrega fica sem rota prevista; não há geocodificação nem mapas reais.
- Há um único operador, provisionado pelo `.env`, e não há contas de entregador nem de restaurante: o operador conduz cada etapa da entrega na demonstração.
- A sessão dura 15 minutos, sem refresh token. O limite de tentativas de login fica em memória, por instância do User Service, e recomeça quando ele reinicia.
- O catálogo não desativa nem remove restaurantes, então os criados pelo smoke e pelo Playwright continuam na lista.
- Só a solicitação de entrega passa pelo RabbitMQ. O estado da entrega não volta ao pedido por evento, o outbox não é limpo e não há reprocessamento da fila de mensagens mortas.
- É uma demonstração local: um nó de cada componente, portas só em `127.0.0.1`, sem TLS, sem backup e com credenciais locais no `.env`. Não é um ambiente de produção.
- No Linux, os scripts de preparação do modelo e do smoke exigem PowerShell 7.

## Checklist de release

- [ ] CI verde no PR: seis jobs Java, Python, Frontend e End to end.
- [ ] Smoke completo com `-CheckRecovery -CheckPersistence` no Compose local.
- [ ] Testes do navegador no Compose local.
- [ ] Ensaio de clone novo: `git archive HEAD` em diretório vazio, `.env` gerado do template, modelo treinado do zero, Compose em projeto separado, smoke e Playwright.
- [ ] Nenhum segredo versionado: `.env` ignorado e só valores de exemplo no `.env.example`.
- [ ] README, roadmap e este documento descrevem o estado integrado.
- [ ] Depois do squash, CI verde também no push da `main`.

## Validação

Em 10/10/2026, no Ubuntu, com Docker via snap, Node 22.22 e PowerShell 7.6:

- Playwright no Compose local: 6 testes aprovados; com `--repeat-each=3`, 18 aprovados.
- Smoke com `-CheckRecovery -CheckPersistence` no Compose local: todas as etapas aprovadas.
- Ensaio de clone novo, em projeto Compose separado e depois removido com seus volumes: modelo treinado do zero, perfil `demo` saudável em 57 s, smoke completo aprovado em 1 min 40 s e Playwright com 6 aprovados.
- `actionlint` sem apontamentos no workflow.

O ensaio encontrou um defeito: no Linux, `prepare-route-model.ps1` falhava em diretórios novos, porque o container (UID 10001) não gravava nos bind mounts do usuário; dar permissão de escrita deixaria arquivos que o usuário não consegue apagar. O script agora executa os containers de geração, treino e avaliação com o UID e o GID de quem o chama no Linux. No Windows nada muda.

A primeira execução do job `End to end (Compose demo)` no GitHub falhou ao subir o Compose: com o cache de build vazio, os seis builds Java baixavam o Maven Wrapper ao mesmo tempo no mesmo cache mount de `/root/.m2` e colidiam (`mv: inter-device move failed`). O ensaio local não pegou o problema porque o cache de build já estava aquecido. A falha foi reproduzida localmente depois de limpar os cache mounts do BuildKit, e o `Dockerfile.java` passou a usar o cache com `sharing=locked`: os builds continuam em paralelo, e só o passo do Maven espera a vez. Com o cache vazio, as seis imagens foram construídas em 68 s, e o Playwright passou com elas (6 aprovados).
