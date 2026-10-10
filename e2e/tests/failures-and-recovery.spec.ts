import { expect, test } from '@playwright/test'
import {
  accessToken,
  createOrder,
  createRestaurant,
  deliveryIdForOrder,
  operatorAccount,
  paymentAttempts,
  registerCustomer,
  type Restaurant,
} from '../support/api.ts'
import { signIn } from '../support/ui.ts'

let restaurant: Restaurant

test.beforeAll(async ({ request }) => {
  const operatorToken = await accessToken(request, operatorAccount())
  restaurant = await createRestaurant(request, operatorToken, [{ name: 'Prato do teste', price: 21.9 }])
})

test('login com senha errada é recusado e a pessoa continua na tela de entrada', async ({ page, request }) => {
  const customer = await registerCustomer(request, 'Senha Errada')
  await page.goto('/login')
  await page.getByLabel('E-mail').fill(customer.email)
  await page.getByLabel('Senha').fill('not-the-right-password')
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)

  await page.goto('/restaurants')
  await expect(page).toHaveURL(/\/login$/)
})

test('cliente não vê o pedido de outra pessoa nem a área da operação', async ({ page, request }) => {
  const owner = await registerCustomer(request, 'Dona do Pedido')
  const orderId = await createOrder(request, await accessToken(request, owner), restaurant)
  const stranger = await registerCustomer(request, 'Outra Pessoa')

  await signIn(page, stranger)
  await page.goto(`/orders/${orderId}`)
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('form', { name: 'Pagamento' })).toHaveCount(0)

  await page.goto('/operations')
  await expect(page.getByRole('alert')).toHaveText('Esta área é exclusiva da operação.')
})

test('pedido cancelado não pode mais ser pago', async ({ page, request }) => {
  const customer = await registerCustomer(request, 'Cancela Pedido')
  const orderId = await createOrder(request, await accessToken(request, customer), restaurant)

  await signIn(page, customer)
  await page.goto(`/orders/${orderId}`)
  await page.getByRole('button', { name: 'Cancelar pedido' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Pedido cancelado.' })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Pedido/ })).toContainText('Cancelado')
  await expect(page.getByRole('form', { name: 'Pagamento' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Entrega' })).toHaveCount(0)
})

test('resposta do pagamento perdida não gera segunda cobrança', async ({ page, request }) => {
  const customer = await registerCustomer(request, 'Rede Instável')
  const token = await accessToken(request, customer)
  const orderId = await createOrder(request, token, restaurant)

  await page.route(`**/api/orders/${orderId}/payment`, async (route) => {
    await route.fetch()
    await route.abort('connectionreset')
  })
  await signIn(page, customer)
  await page.goto(`/orders/${orderId}`)
  await page.getByRole('button', { name: 'Pagar' }).click()

  await expect(page.getByRole('alert').filter({ hasText: 'sem nova cobrança' })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Pedido/ })).toContainText('Confirmado')
  await page.unrouteAll()
  await page.reload()
  await expect(page.getByRole('form', { name: 'Pagamento' })).toHaveCount(0)
  expect((await paymentAttempts(request, token, orderId)).map((attempt) => attempt.status)).toEqual(['APPROVED'])
})

test('falha no cálculo da rota é informada e uma nova tentativa funciona', async ({ page, request }) => {
  const operator = operatorAccount()
  const customer = await registerCustomer(request, 'Rota Indisponível')
  const token = await accessToken(request, customer)
  const orderId = await createOrder(request, token, restaurant)
  const pay = await request.post(`/api/orders/${orderId}/payment`, {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': `e2e-${orderId}` },
    data: { method: 'sim-card-approved' },
  })
  expect(pay.status()).toBe(200)
  const requested = await request.post(`/api/orders/${orderId}/delivery`, { headers: { Authorization: `Bearer ${token}` } })
  expect(requested.status()).toBe(202)
  const deliveryId = await deliveryIdForOrder(request, token, orderId)

  await page.route(`**/api/deliveries/${deliveryId}/route`, (route) =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 503, contentType: 'application/problem+json', body: '{"status":503}' })
      : route.fallback(),
  )
  await signIn(page, operator)
  await page.goto(`/operations/deliveries/${deliveryId}`)
  await expect(page.getByText('Nenhuma rota foi calculada para esta entrega.')).toBeVisible()
  await page.getByRole('button', { name: 'Calcular rota' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    'O serviço de rotas não respondeu. O plano anterior, se houver, foi preservado.',
  )

  await page.unrouteAll()
  await page.getByRole('button', { name: 'Calcular rota' }).click()
  await expect(page.locator('.route-stats').getByText('Tempo previsto')).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})
