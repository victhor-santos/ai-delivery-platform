import { expect, test } from '@playwright/test'
import {
  accessToken,
  createRestaurant,
  deliveryIdForOrder,
  newCustomerAccount,
  operatorAccount,
  paymentAttempts,
} from '../support/api.ts'
import { brl, orderIdFrom, signIn, signUp } from '../support/ui.ts'

test('cliente pede, paga e acompanha a entrega conduzida pela operação', async ({ page, browser, request }) => {
  const operator = operatorAccount()
  const operatorToken = await accessToken(request, operator)
  const restaurant = await createRestaurant(request, operatorToken, [
    { name: 'Pastel de feira', price: 12.5 },
    { name: 'Caldo de cana', price: 8 },
  ])
  const customer = newCustomerAccount()

  await test.step('cliente cria a conta', async () => {
    await signUp(page, 'Cliente E2E', customer)
    await expect(page.getByRole('navigation', { name: 'Principal' }).getByText('Operação')).toHaveCount(0)
  })

  await test.step('monta o carrinho e faz o pedido', async () => {
    await page.goto(`/restaurants/${restaurant.id}`)
    await expect(page.getByRole('heading', { name: restaurant.name })).toBeVisible()
    await page.getByRole('button', { name: 'Adicionar uma unidade de Pastel de feira' }).click()
    await page.getByRole('button', { name: 'Adicionar uma unidade de Pastel de feira' }).click()
    await page.getByRole('button', { name: 'Adicionar uma unidade de Caldo de cana' }).click()
    const summary = page.getByRole('region', { name: 'Seu pedido' })
    await expect(summary.getByText('3 itens')).toBeVisible()
    await expect(summary.locator('.total')).toContainText(brl('33,00'))

    await expect(summary.getByRole('button', { name: 'Fazer pedido' })).toBeDisabled()
    await summary.getByLabel('Destino').selectOption({ label: 'Ponto sintético C' })
    await summary.getByRole('button', { name: 'Fazer pedido' }).click()
    await expect(page).toHaveURL(/\/orders\//)
    await expect(page.getByRole('heading', { name: /Pedido/ })).toContainText('Aguardando pagamento')
    await expect(page.locator('.total')).toContainText(brl('33,00'))
  })
  const orderId = orderIdFrom(page)

  await test.step('pagamento recusado mantém o pedido aberto e outro método confirma', async () => {
    const payment = page.getByRole('form', { name: 'Pagamento' })
    await payment.getByLabel('Método de pagamento').selectOption('sim-card-insufficient-funds')
    await payment.getByRole('button', { name: 'Pagar' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Pagamento recusado: saldo insuficiente' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /Pedido/ })).toContainText('Aguardando pagamento')

    await payment.getByLabel('Método de pagamento').selectOption('sim-card-approved')
    await payment.getByRole('button', { name: 'Pagar' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Pagamento aprovado' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /Pedido/ })).toContainText('Confirmado')
    await expect(page.getByRole('button', { name: 'Cancelar pedido' })).toHaveCount(0)
  })

  await test.step('cliente solicita a entrega e a vê criada pelo serviço de entregas', async () => {
    const delivery = page.getByRole('region', { name: 'Entrega' })
    await delivery.getByRole('button', { name: 'Solicitar entrega' }).click()
    await expect(delivery.getByRole('heading', { name: /Entrega/ })).toContainText('Aguardando entregador')
    await expect(delivery.getByText('A rota aparece quando a operação a calcular.')).toBeVisible()
  })
  const deliveryId = await deliveryIdForOrder(request, operatorToken, orderId)

  const operatorContext = await browser.newContext()
  try {
    const operations = await operatorContext.newPage()
    await test.step('operador encontra a entrega na fila', async () => {
      await signIn(operations, operator)
      await operations.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Operação' }).click()
      await operations.getByRole('button', { name: /^Aguardando entregador/ }).click()
      const row = operations.getByRole('listitem').filter({ hasText: `pedido ${orderId.slice(0, 8)}` })
      await row.getByRole('link').click()
      await expect(operations).toHaveURL(`/operations/deliveries/${deliveryId}`)
    })

    await test.step('operador calcula a rota no grafo sintético', async () => {
      await operations.getByRole('button', { name: 'Calcular rota' }).click()
      const stats = operations.locator('.route-stats')
      await expect(stats.getByText('Tempo previsto')).toBeVisible()
      await expect(stats.getByText(/^A → .+ → C$/)).toBeVisible()
      await expect(operations.getByText(/não representa(m)? trânsito real/)).toBeVisible()
    })

    await test.step('operador conduz a entrega até o fim', async () => {
      const panel = operations.getByRole('group', { name: 'Simulação operacional' })
      const heading = operations.getByRole('heading', { level: 1 })
      for (const [action, status] of [
        ['Atribuir entregador', 'Entregador a caminho do restaurante'],
        ['Registrar coleta', 'Pedido coletado'],
        ['Sair para entrega', 'A caminho'],
        ['Registrar chegada', 'Entregador no destino'],
        ['Concluir entrega', 'Entregue'],
      ]) {
        await panel.getByRole('button', { name: action }).click()
        await expect(heading).toContainText(status)
      }
      await expect(panel).toHaveCount(0)
    })
  } finally {
    await operatorContext.close()
  }

  await test.step('cliente vê a entrega concluída e a rota salva', async () => {
    await page.reload()
    const delivery = page.getByRole('region', { name: 'Entrega' })
    await expect(delivery.getByRole('heading', { name: /Entrega/ })).toContainText('Entregue')
    await expect(delivery.getByRole('list', { name: 'Andamento da entrega' }).getByRole('listitem')).toHaveCount(6)
    await expect(delivery.locator('.route-stats').getByText('Tempo previsto')).toBeVisible()
    await expect(delivery.getByRole('button', { name: /Calcular rota|Recalcular rota/ })).toHaveCount(0)
  })

  const attempts = await paymentAttempts(request, await accessToken(request, customer), orderId)
  expect(attempts.map((attempt) => attempt.status)).toEqual(['DECLINED', 'APPROVED'])
})
