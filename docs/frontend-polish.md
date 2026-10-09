# Acabamento da interface web

Esta etapa melhora a aparência e a usabilidade da [interface web](frontend-foundation.md) sobre o fluxo final, sem mudar contratos dos serviços.

| Área | O que mudou |
| --- | --- |
| Base visual | Paletas clara e escura, espaçamento e raios consistentes, foco visível em todos os controles, estados de hover/desabilitado, cabeçalho fixo com marca e selo **Operador**, aviso de dados simulados no rodapé |
| Entrar, criar conta e início | Cartões com subtítulo; o início leva o cliente aos restaurantes e o operador à operação |
| Restaurantes | Grade de cartões com iniciais, ponto de coleta na cidade sintética e selo de fechado |
| Cardápio e carrinho | Itens escolhidos destacados, contagem de itens no carrinho e preços em evidência |
| Destino | O ponto da cidade sintética onde fica a coleta do restaurante não é oferecido como destino |
| Pedido | Etapas (pedido feito, pagamento aprovado, entrega solicitada) e selos coloridos por situação |
| Entrega | Linha do tempo contínua, distância, tempo previsto e percurso em destaque; coleta e destino no mesmo ponto aparecem num marcador único |
| Operação | Filtros por situação com contagem, atualização automática a cada 10 segundos e linhas com coleta, pedido e horário |

A cor do selo segue a situação: aguardando em amarelo, em andamento em azul, concluído em verde e cancelado em vermelho.

## Validação

- `npm run lint`, `npm run build` e `npm test` (79 testes), incluindo a exclusão do ponto de coleta, o marcador combinado, as etapas do pedido e os filtros com contagem.
- Conferência visual no navegador, tema escuro, como cliente (restaurantes, cardápio, pedido com entrega) e como operador (lista, entrega e cálculo da rota).

## Limites

Os restaurantes criados pelo smoke ("Compose Demo …") continuam aparecendo na lista, porque o catálogo não tem como desativar restaurantes. O tema claro e telas estreitas foram revisados pelo CSS, mas não conferidos no navegador.
