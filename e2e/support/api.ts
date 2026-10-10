import { existsSync, readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { expect, type APIRequestContext } from '@playwright/test'

const PASSWORD = 'e2e-password-123456'

export const POINT_A = { latitude: -23.5505, longitude: -46.6333 }
export const POINT_C = { address: 'Ponto sintético C', latitude: -23.561, longitude: -46.656 }

export type Account = { email: string; password: string }
export type MenuItem = { id: string; name: string; price: number }
export type Restaurant = { id: string; name: string; items: MenuItem[] }

function setting(name: string): string {
  const fromEnvironment = process.env[name]
  if (fromEnvironment) {
    return fromEnvironment
  }
  const file = process.env.E2E_ENV_FILE ?? fileURLToPath(new URL('../../.env', import.meta.url))
  if (existsSync(file)) {
    const line = readFileSync(file, 'utf8')
      .split(/\r?\n/)
      .find((candidate) => candidate.startsWith(`${name}=`))
    const value = line?.slice(name.length + 1).trim()
    if (value) {
      return value
    }
  }
  throw new Error(`${name} is not configured. Run scripts/initialize-auth-secret.sh and restart the demo.`)
}

export function operatorAccount(): Account {
  return { email: setting('USER_OPERATOR_EMAIL'), password: setting('USER_OPERATOR_PASSWORD') }
}

export function uniqueSuffix(): string {
  return randomUUID().replaceAll('-', '').slice(0, 10)
}

export function newCustomerAccount(): Account {
  return { email: `e2e-${uniqueSuffix()}@example.test`, password: PASSWORD }
}

async function json<T>(response: Awaited<ReturnType<APIRequestContext['get']>>, status: number): Promise<T> {
  expect(response.status(), await response.text()).toBe(status)
  return (await response.json()) as T
}

export async function registerCustomer(request: APIRequestContext, name: string): Promise<Account> {
  const account = newCustomerAccount()
  await json(await request.post('/api/users/auth/register', { data: { name, ...account } }), 201)
  return account
}

export async function accessToken(request: APIRequestContext, account: Account): Promise<string> {
  const response = await request.post('/api/users/auth/login', { data: account })
  return (await json<{ accessToken: string }>(response, 200)).accessToken
}

function bearer(token: string) {
  return { Authorization: `Bearer ${token}` }
}

export async function createRestaurant(
  request: APIRequestContext,
  operatorToken: string,
  items: { name: string; price: number }[],
): Promise<Restaurant> {
  const restaurant = await json<{ id: string; name: string }>(
    await request.post('/api/catalog/restaurants', {
      headers: bearer(operatorToken),
      data: { name: `E2E ${uniqueSuffix()}`, pickupLocation: POINT_A },
    }),
    201,
  )
  const created: MenuItem[] = []
  for (const item of items) {
    const menuItem = await json<MenuItem>(
      await request.post(`/api/catalog/restaurants/${restaurant.id}/menu-items`, {
        headers: bearer(operatorToken),
        data: { ...item, description: 'Item criado pelo teste E2E' },
      }),
      201,
    )
    created.push(menuItem)
  }
  return { ...restaurant, items: created }
}

export async function createOrder(request: APIRequestContext, token: string, restaurant: Restaurant): Promise<string> {
  const order = await json<{ id: string }>(
    await request.post('/api/orders', {
      headers: bearer(token),
      data: {
        restaurantId: restaurant.id,
        items: [{ menuItemId: restaurant.items[0].id, quantity: 1 }],
        destination: POINT_C,
      },
    }),
    201,
  )
  return order.id
}

export async function paymentAttempts(request: APIRequestContext, token: string, orderId: string) {
  const page = await json<{ totalElements: number; items: { status: string }[] }>(
    await request.get(`/api/payments?orderId=${orderId}`, { headers: bearer(token) }),
    200,
  )
  return page.items
}

export async function deliveryIdForOrder(request: APIRequestContext, token: string, orderId: string): Promise<string> {
  let deliveryId = ''
  await expect
    .poll(
      async () => {
        const response = await request.get(`/api/deliveries/by-order/${orderId}`, { headers: bearer(token) })
        if (response.status() === 200) {
          deliveryId = ((await response.json()) as { id: string }).id
        }
        return response.status()
      },
      { timeout: 30_000 },
    )
    .toBe(200)
  return deliveryId
}
