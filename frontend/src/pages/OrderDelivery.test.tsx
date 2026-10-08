import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Delivery, RoutePlan } from '../api/deliveries'
import type { Order } from '../api/orders'
import { json, mockFetch, problem } from '../test/http'
import { renderAt, signIn } from '../test/render'

const CONFIRMED: Order = {
  id: 'o1',
  customerId: 'u1',
  restaurantId: 'r1',
  destination: { address: 'Ponto sintético C', latitude: -23.561, longitude: -46.656 },
  status: 'CONFIRMED',
  createdAt: '2026-10-08T12:00:00Z',
  updatedAt: '2026-10-08T12:01:00Z',
  confirmedAt: '2026-10-08T12:01:00Z',
  cancelledAt: null,
  deliveryRequestedAt: null,
  items: [{ menuItemId: 'i1', name: 'Prato do dia', quantity: 1, unitPrice: 25.9, lineTotal: 25.9 }],
  total: 25.9,
  currency: 'BRL',
  paymentRequestedAt: '2026-10-08T12:01:00Z',
  paymentId: 'p1',
}
const REQUESTED: Order = { ...CONFIRMED, deliveryRequestedAt: '2026-10-08T12:02:00Z' }

const DELIVERY: Delivery = {
  id: 'd1',
  orderId: 'o1',
  origin: { description: 'Cantina', latitude: -23.5505, longitude: -46.6333 },
  destination: { description: 'Ponto sintético C', latitude: -23.561, longitude: -46.656 },
  courierId: null,
  status: 'CREATED',
  createdAt: '2026-10-08T12:02:00Z',
  updatedAt: '2026-10-08T12:02:00Z',
  assignedAt: null,
  pickedUpAt: null,
  departedAt: null,
  arrivedAt: null,
  deliveredAt: null,
  cancelledAt: null,
}

const PLAN: RoutePlan = {
  id: 'plan-1',
  deliveryId: 'd1',
  departureAt: '2026-10-08T12:05:00Z',
  plannedAt: '2026-10-08T12:05:00Z',
  route: [
    { latitude: -23.5505, longitude: -46.6333 },
    { latitude: -23.554, longitude: -46.64 },
    { latitude: -23.561, longitude: -46.656 },
  ],
  segments: [
    { segmentId: 'A-B', distanceKm: 0.9, predictedTravelTimeMinutes: 4.94 },
    { segmentId: 'B-C', distanceKm: 2.0, predictedTravelTimeMinutes: 11.13 },
  ],
  distanceKm: 2.9,
  predictedTravelTimeMinutes: 16.07,
  predictedAt: '2026-10-08T12:05:00Z',
  contextAsOf: '2026-10-08T12:00:00Z',
  modelVersion: 'segment-model-v1-test',
  graphVersion: 'synthetic-city-v1',
  dataOrigin: 'synthetic',
}

type State = { order: Order; delivery: Delivery | null; plan?: RoutePlan | null }

function server(
  initial: State,
  requestDelivery: (state: State) => Response,
  plan: (state: State) => Response = (state) => {
    state.plan = PLAN
    return json(200, PLAN)
  },
) {
  const state = { plan: null, ...initial }
  const fetchMock = mockFetch({
    'GET /api/orders/o1': () => json(200, state.order),
    'GET /api/catalog/restaurants/r1': () =>
      json(200, { id: 'r1', name: 'Cantina', active: true, pickupLocation: null }),
    'POST /api/orders/o1/delivery': () => requestDelivery(state),
    'GET /api/deliveries/by-order/o1': () =>
      state.delivery ? json(200, state.delivery) : problem(404, 'Entrega não encontrada.'),
    'GET /api/deliveries/d1/route': () =>
      state.plan ? json(200, state.plan) : problem(404, 'A entrega ainda não possui um plano de rota.'),
    'POST /api/deliveries/d1/route': () => plan(state),
  })
  const calls = (path: string) => fetchMock.mock.calls.filter(([url]) => url === path)
  return { state, calls }
}

function created(state: State) {
  state.order = REQUESTED
  state.delivery = DELIVERY
  return json(200, { orderId: 'o1', deliveryId: 'd1', status: 'CREATED' })
}

async function openOrder() {
  signIn()
  const user = userEvent.setup()
  renderAt('/orders/o1')
  await screen.findByRole('heading', { name: /Pedido/ })
  return user
}

describe('order delivery', () => {
  it('requests the delivery of a confirmed order and shows its progress', async () => {
    const { calls } = server({ order: CONFIRMED, delivery: null }, created)
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Solicitar entrega' }))

    expect(await screen.findByText('Aguardando entregador')).toBeInTheDocument()
    const timeline = screen.getByRole('list', { name: 'Andamento da entrega' })
    expect(within(timeline).getAllByRole('listitem')).toHaveLength(6)
    expect(within(timeline).getByText('Entregador atribuído').parentElement).not.toHaveClass('done')
    expect(screen.queryByRole('button', { name: 'Solicitar entrega' })).not.toBeInTheDocument()
    expect(calls('/api/orders/o1/delivery')[0][1]?.headers).toMatchObject({ Authorization: 'Bearer token-123' })
  })

  it('keeps the request retryable when the delivery service does not answer', async () => {
    const { state, calls } = server({ order: CONFIRMED, delivery: null }, (current) => {
      current.order = REQUESTED
      return problem(503, 'indisponível')
    })
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Solicitar entrega' }))

    expect(await screen.findByText(/não respondeu/)).toBeInTheDocument()
    expect(await screen.findByText(/ainda não a confirmou/)).toBeInTheDocument()

    state.delivery = DELIVERY
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))

    expect(await screen.findByText('Aguardando entregador')).toBeInTheDocument()
    expect(calls('/api/orders/o1/delivery')).toHaveLength(2)
  })

  it('shows the server reason when the delivery is refused', async () => {
    server({ order: CONFIRMED, delivery: null }, () => problem(409, 'O restaurante não está ativo.'))
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Solicitar entrega' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('O restaurante não está ativo.')
    expect(screen.getByRole('button', { name: 'Solicitar entrega' })).toBeEnabled()
  })

  it('ends the timeline at the cancellation', async () => {
    server(
      {
        order: REQUESTED,
        delivery: { ...DELIVERY, status: 'CANCELLED', cancelledAt: '2026-10-08T12:03:00Z' },
      },
      created,
    )
    await openOrder()

    const timeline = await screen.findByRole('list', { name: 'Andamento da entrega' })
    const steps = within(timeline).getAllByRole('listitem')
    expect(steps.map((step) => step.firstChild?.textContent)).toEqual(['Entrega criada', 'Entrega cancelada'])
    expect(screen.getByText('Entrega cancelada', { selector: '.badge' })).toBeInTheDocument()
  })

  it('does not offer delivery before the order is paid', async () => {
    const unpaid: Order = { ...CONFIRMED, status: 'CREATED', confirmedAt: null, paymentRequestedAt: null }
    server({ order: unpaid, delivery: null }, created)
    await openOrder()

    expect(screen.queryByRole('region', { name: 'Entrega' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Solicitar entrega' })).not.toBeInTheDocument()
  })
})

describe('delivery route', () => {
  it('plans the route and draws it on the synthetic city', async () => {
    const { calls } = server({ order: REQUESTED, delivery: DELIVERY }, created)
    const user = await openOrder()

    expect(await screen.findByText('Nenhuma rota foi calculada para esta entrega.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Calcular rota' }))

    expect(await screen.findByText('A → B → C')).toBeInTheDocument()
    expect(screen.getByText('2,9 km')).toBeInTheDocument()
    expect(screen.getByText('16 min')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /rota A → B → C/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Recalcular rota' })).toBeInTheDocument()
    const body = JSON.parse(calls('/api/deliveries/d1/route')[1][1]!.body as string) as { departureAt: string }
    expect(Number.isNaN(Date.parse(body.departureAt))).toBe(false)
  })

  it('keeps the saved plan when the route service is unavailable', async () => {
    server({ order: REQUESTED, delivery: DELIVERY, plan: PLAN }, created, () => problem(503, 'indisponível'))
    const user = await openOrder()

    await user.click(await screen.findByRole('button', { name: 'Recalcular rota' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('O serviço de rotas não respondeu.')
    expect(screen.getByText('A → B → C')).toBeInTheDocument()
  })

  it('shows why a route cannot be found', async () => {
    server({ order: REQUESTED, delivery: DELIVERY }, created, () =>
      problem(422, 'Não há caminho entre a coleta e o destino.'),
    )
    const user = await openOrder()

    await user.click(await screen.findByRole('button', { name: 'Calcular rota' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não há caminho entre a coleta e o destino.')
  })

  it('shows the saved plan without replanning after departure', async () => {
    const departed: Delivery = {
      ...DELIVERY,
      status: 'IN_TRANSIT',
      assignedAt: '2026-10-08T12:03:00Z',
      pickedUpAt: '2026-10-08T12:04:00Z',
      departedAt: '2026-10-08T12:06:00Z',
    }
    server({ order: REQUESTED, delivery: departed, plan: PLAN }, created)
    await openOrder()

    expect(await screen.findByText('A → B → C')).toBeInTheDocument()
    expect(screen.getByText('A caminho', { selector: '.badge' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rota/ })).not.toBeInTheDocument()
  })

  it('explains that destinations outside the synthetic city have no route', async () => {
    const outside: Delivery = {
      ...DELIVERY,
      destination: { description: 'Rua Central, 42', latitude: -23.56, longitude: -46.64 },
    }
    server({ order: REQUESTED, delivery: outside }, created)
    await openOrder()

    expect(await screen.findByText(/fora da cidade sintética/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rota/ })).not.toBeInTheDocument()
  })
})
