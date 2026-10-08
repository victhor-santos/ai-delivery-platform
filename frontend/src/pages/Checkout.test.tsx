import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { json, mockFetch, problem, type Handler } from '../test/http'
import { renderAt, signIn } from '../test/render'

const USER = { id: 'u1', name: 'Cliente Demo', email: 'demo@example.test' }
const RESTAURANT = { id: 'r1', name: 'Cantina da Praça', active: true, pickupLocation: null }
const MENU = [
  {
    id: 'i1',
    restaurantId: 'r1',
    name: 'Prato do dia',
    description: null,
    price: 25.9,
    currency: 'BRL',
    available: true,
  },
  { id: 'i2', restaurantId: 'r1', name: 'Suco', description: null, price: 7.5, currency: 'BRL', available: true },
]
const HOME = {
  id: 'a1',
  userId: 'u1',
  label: 'Casa',
  address: 'Rua das Flores, 42',
  latitude: -23.562,
  longitude: -46.657,
}
const ORDER = {
  id: 'o1',
  customerId: 'u1',
  restaurantId: 'r1',
  destination: { address: 'Ponto sintético C', latitude: -23.561, longitude: -46.656 },
  status: 'CREATED',
  createdAt: '2026-10-08T12:00:00Z',
  updatedAt: '2026-10-08T12:00:00Z',
  confirmedAt: null,
  cancelledAt: null,
  deliveryRequestedAt: null,
  items: [
    { menuItemId: 'i1', name: 'Prato do dia', quantity: 2, unitPrice: 25.9, lineTotal: 51.8 },
    { menuItemId: 'i2', name: 'Suco', quantity: 1, unitPrice: 7.5, lineTotal: 7.5 },
  ],
  total: 59.3,
  currency: 'BRL',
  paymentRequestedAt: null,
  paymentId: null,
}

function restaurantRoutes(addresses: unknown[] = []): Record<string, Handler> {
  return {
    'GET /api/catalog/restaurants/r1': () => json(200, RESTAURANT),
    'GET /api/catalog/restaurants/r1/menu-items?page=0&size=100': () =>
      json(200, { content: MENU, page: 0, size: 100, totalElements: MENU.length, totalPages: 1 }),
    'GET /api/users/auth/me': () => json(200, USER),
    'GET /api/users/u1/addresses?page=0&size=100': () =>
      json(200, { items: addresses, page: 0, size: 100, totalElements: addresses.length, totalPages: 1 }),
  }
}

async function openMenu() {
  signIn()
  const user = userEvent.setup()
  renderAt('/restaurants/r1')
  await screen.findByRole('heading', { name: 'Cantina da Praça' })
  return user
}

describe('checkout', () => {
  it('places an order with the chosen quantities and destination', async () => {
    const fetchMock = mockFetch({
      ...restaurantRoutes(),
      'POST /api/orders': () => json(201, ORDER),
      'GET /api/orders/o1': () => json(200, ORDER),
    })
    const user = await openMenu()
    const order = screen.getByRole('button', { name: 'Fazer pedido' })
    expect(order).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Adicionar uma unidade de Prato do dia' }))
    await user.click(screen.getByRole('button', { name: 'Adicionar uma unidade de Prato do dia' }))
    await user.click(screen.getByRole('button', { name: 'Adicionar uma unidade de Suco' }))
    const summary = screen.getByRole('region', { name: 'Seu pedido' })
    expect(within(summary).getByText(/R\$\s59,30/)).toBeInTheDocument()
    expect(order).toBeDisabled()

    await user.selectOptions(await screen.findByLabelText('Destino'), 'Ponto sintético C')
    await user.click(order)

    expect(await screen.findByText('Aguardando pagamento')).toBeInTheDocument()
    const create = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(create[1]!.body as string)).toEqual({
      restaurantId: 'r1',
      destination: { address: 'Ponto sintético C', latitude: -23.561, longitude: -46.656 },
      items: [
        { menuItemId: 'i1', quantity: 2 },
        { menuItemId: 'i2', quantity: 1 },
      ],
    })
    expect(within(screen.getByRole('list', { name: 'Itens do pedido' })).getAllByRole('listitem')).toHaveLength(2)
  })

  it('warns when a saved address is outside the synthetic city', async () => {
    mockFetch(restaurantRoutes([HOME]))
    const user = await openMenu()

    await user.selectOptions(await screen.findByLabelText('Destino'), 'Casa: Rua das Flores, 42')

    expect(screen.getByText(/fora da cidade sintética/)).toHaveAttribute('role', 'status')
  })

  it('saves a new address and selects it', async () => {
    const saved = { ...HOME, id: 'a2', label: 'Trabalho', address: 'Ponto C', latitude: -23.561, longitude: -46.656 }
    const fetchMock = mockFetch({
      ...restaurantRoutes(),
      'POST /api/users/u1/addresses': () => json(201, saved),
    })
    const user = await openMenu()

    await user.click(await screen.findByRole('button', { name: 'Cadastrar novo endereço' }))
    const form = screen.getByRole('form', { name: 'Novo endereço' })
    await user.type(within(form).getByLabelText('Rótulo'), 'Trabalho')
    await user.type(within(form).getByLabelText('Endereço'), 'Ponto C')
    await user.type(within(form).getByLabelText('Latitude'), '-23.561')
    await user.type(within(form).getByLabelText('Longitude'), '-46.656')
    await user.click(within(form).getByRole('button', { name: 'Salvar endereço' }))

    expect(await screen.findByLabelText('Destino')).toHaveDisplayValue('Trabalho: Ponto C')
    expect(screen.queryByRole('form', { name: 'Novo endereço' })).not.toBeInTheDocument()
    const create = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(create[1]!.body as string)).toEqual({
      label: 'Trabalho',
      address: 'Ponto C',
      latitude: -23.561,
      longitude: -46.656,
    })
  })

  it('rejects coordinates out of range before calling the server', async () => {
    const fetchMock = mockFetch(restaurantRoutes())
    const user = await openMenu()

    await user.click(await screen.findByRole('button', { name: 'Cadastrar novo endereço' }))
    const form = screen.getByRole('form', { name: 'Novo endereço' })
    await user.type(within(form).getByLabelText('Rótulo'), 'Longe')
    await user.type(within(form).getByLabelText('Endereço'), 'Lugar nenhum')
    await user.type(within(form).getByLabelText('Latitude'), '91')
    await user.type(within(form).getByLabelText('Longitude'), '0')
    await user.click(within(form).getByRole('button', { name: 'Salvar endereço' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('latitude entre -90 e 90')
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('shows why the order was refused and allows another attempt', async () => {
    mockFetch({
      ...restaurantRoutes(),
      'POST /api/orders': () => problem(409, 'Item do cardápio indisponível.'),
    })
    const user = await openMenu()

    await user.click(screen.getByRole('button', { name: 'Adicionar uma unidade de Suco' }))
    await user.selectOptions(await screen.findByLabelText('Destino'), 'Ponto sintético A')
    await user.click(screen.getByRole('button', { name: 'Fazer pedido' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Item do cardápio indisponível.')
    expect(screen.getByRole('button', { name: 'Fazer pedido' })).toBeEnabled()
  })

  it('does not offer the cart for a closed restaurant', async () => {
    mockFetch({
      ...restaurantRoutes(),
      'GET /api/catalog/restaurants/r1': () => json(200, { ...RESTAURANT, active: false }),
    })
    await openMenu()

    expect(screen.queryByRole('button', { name: /Adicionar uma unidade/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Seu pedido' })).not.toBeInTheDocument()
  })
})

describe('OrderPage', () => {
  it('shows an older order without items as unknown value', async () => {
    signIn()
    mockFetch({
      'GET /api/orders/o1': () => json(200, { ...ORDER, items: [], total: null, currency: null }),
      'GET /api/catalog/restaurants/r1': () => json(200, RESTAURANT),
    })

    renderAt('/orders/o1')

    expect(await screen.findByText('Valor desconhecido')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cantina da Praça' })).toBeInTheDocument()
  })

  it('reports an order that does not belong to the account', async () => {
    signIn()
    mockFetch({ 'GET /api/orders/o1': () => problem(404, 'Pedido não encontrado.') })

    renderAt('/orders/o1')

    expect(await screen.findByRole('alert')).toHaveTextContent('Pedido não encontrado.')
  })
})
