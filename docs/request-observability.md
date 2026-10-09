# Observabilidade das requisições

Cada requisição recebe um identificador que acompanha a chamada do Gateway até o último serviço envolvido. Com ele, uma falha vista na interface pode ser localizada nos logs de todos os containers. As métricas do Actuator cobrem o fluxo HTTP de cada serviço e o tempo das rotas calculadas pelo Route Intelligence.

## Identificador da requisição

O header é `X-Request-Id`. O Gateway mantém o valor enviado pelo cliente somente quando ele tem de 8 a 64 caracteres entre letras, dígitos e hífens; caso contrário, gera um UUID. Esse limite impede que um valor do cliente insira quebras de linha ou outros conteúdos nos logs. O Gateway repassa o identificador ao serviço de destino e o devolve na resposta, inclusive em `404` de rotas inexistentes.

Os serviços Java aplicam a mesma regra, o que também cobre chamadas diretas às suas portas. O filtro roda antes do Spring Security, então recusas `401` também são correlacionadas. As chamadas entre serviços enviam o identificador da requisição em andamento:

| Origem | Destino |
| --- | --- |
| Order | Catalog e Payment, por HTTP; Delivery, no header `X-Request-Id` do evento de solicitação de entrega |
| Payment | Order |
| Delivery | Route Intelligence |

No Route Intelligence, o identificador vale para toda a requisição, inclusive respostas `500` de erros não tratados.

## Logs

Cada aplicação registra uma linha por requisição, com método, caminho, status e duração. Nos serviços Java e no Python, o identificador entra em todas as linhas registradas durante a requisição, e não só na linha de acesso:

```text
... [delivery-service] [nio-8085-exec-2] [a5501cdf-92ad-479b-8ab7-f48739366de5] c.v.d.d.observability.RequestIdFilter : method=POST path=/api/deliveries/c1c2.../route status=200 durationMs=207
... INFO [a5501cdf-92ad-479b-8ab7-f48739366de5] app.requests: method=POST path=/api/routes/fastest status=200 durationMs=30
```

Headers, query strings e corpos não são registrados, então tokens, senhas e dados do pedido não aparecem nos logs. Chamadas a `/actuator` (Java) e `/health` (Python) não geram linha de acesso, para que os healthchecks do Compose não encham o log. O Route Intelligence substitui o access log do uvicorn pela própria linha, que inclui o identificador.

Para seguir uma requisição na demonstração, copie o `X-Request-Id` da resposta (na aba de rede do navegador, por exemplo) e procure nos logs:

```bash
docker compose logs | grep a5501cdf-92ad-479b-8ab7-f48739366de5
```

## Métricas

Os cinco serviços expõem `/actuator/metrics`, além de `health` e `info`. O Gateway continua expondo só `health` e `info`, porque é a única porta Java publicada no perfil `demo`. As portas dos serviços são internas nesse perfil; em execução nativa, elas atendem em `localhost`.

| Métrica | Onde | O que mede |
| --- | --- | --- |
| `http.server.requests` | Todos os serviços Java | Contagem e duração por `uri` (modelo da rota, como `/api/orders/{id}/payment`), `method`, `status` e `outcome` |
| `order.outbox.published`, `order.outbox.failed`, `order.outbox.pending` | Order | Publicação dos eventos do outbox; veja a [solicitação por RabbitMQ](delivery-messaging.md) |
| `delivery.requests.consumed` | Delivery | Eventos de solicitação consumidos, por `outcome` |
| `delivery.route.optimization` | Delivery | Duração de cada cálculo pedido ao Route Intelligence, por `outcome`: `success`, `outside_coverage`, `route_not_found`, `unavailable` ou `error` |

Na rede da demonstração:

```bash
docker run --rm --network ai-delivery-platform_default curlimages/curl -s \
  http://delivery-service:8085/actuator/metrics/delivery.route.optimization
```

## Limites

- Não há tracing distribuído (spans, OpenTelemetry) nem coletor de métricas como Prometheus; as métricas ficam na memória de cada processo e reiniciam com ele.
- Os logs continuam em texto no console dos containers, sem formato JSON nem agregação.
- O Route Intelligence não publica métricas próprias; a latência do roteamento é medida do lado do Delivery.

## Validação

Em 08/10/2026, no Ubuntu:

- `mvnw verify` passou no Gateway e nos cinco serviços, com os testes novos de identificador, propagação e métricas de rota;
- `ruff check`, `ruff format --check` e `pytest` passaram no Route Intelligence (em container `uv` com Python 3.12);
- `docker compose --profile demo up -d --build --wait` iniciou os treze containers saudáveis, e `scripts/smoke-route-demo.ps1` passou no fluxo completo;
- nos logs do smoke, um cálculo de rota apareceu com o mesmo identificador no Gateway, no Delivery e no Route Intelligence, e um pagamento apareceu no Gateway, no Order e no Payment, inclusive na consulta do pedido feita pelo Payment. Nenhuma linha continha `Bearer` ou um JWT;
- `delivery.route.optimization` registrou o cálculo do smoke com `outcome=success`, `/actuator/metrics` do Order respondeu sem token pela rede interna, e `http://localhost:8080/actuator/metrics` respondeu `404`.
