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
    'POST /api/deliveries/couriers': () => json(201, { id: 'c1', active: true }),
    'POST /api/deliveries/d1/assign': () =>
      state.delivery?.status === 'CREATED'
        ? advance(state, { status: 'ASSIGNED', courierId: 'c1', assignedAt: at(3) })
        : problem(409, 'A entrega já tem entregador.'),
    'POST /api/deliveries/d1/pick-up': () => advance(state, { status: 'PICKED_UP', pickedUpAt: at(4) }),
    'POST /api/deliveries/d1/start-transit': () => advance(state, { status: 'IN_TRANSIT', departedAt: at(5) }),
    'POST /api/deliveries/d1/arrive': () => advance(state, { arrivedAt: at(6) }),
    'POST /api/deliveries/d1/complete': () => advance(state, { status: 'DELIVERED', deliveredAt: at(7) }),
    'POST /api/deliveries/d1/cancel': () => advance(state, { status: 'CANCELLED', cancelledAt: at(3) }),
  })
  const calls = (path: string) => fetchMock.mock.calls.filter(([url]) => url === path)
  return { state, calls }
}

function at(minute: number): string {
  return `2026-10-08T12:0${minute}:00Z`
}

function advance(state: State, changes: Partial<Delivery>) {
  state.delivery = { ...state.delivery!, ...changes, updatedAt: at(9) }
  return json(200, state.delivery)
}

function created(state: State) {
  state.order = REQUESTED
  state.delivery = DELIVERY
  return json(202, { orderId: 'o1', status: 'REQUESTED' })
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

  it('waits for the delivery service to consume the request', async () => {
    const { state, calls } = server({ order: CONFIRMED, delivery: null }, (current) => {
      current.order = REQUESTED
      return json(202, { orderId: 'o1', status: 'REQUESTED' })
    })
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Solicitar entrega' }))

    expect(await screen.findByText(/Aguardando o serviço de entregas/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Solicitar entrega' })).not.toBeInTheDocument()

    state.delivery = DELIVERY

    expect(await screen.findByText('Aguardando entregador', {}, { timeout: 3_000 })).toBeInTheDocument()
    expect(calls('/api/orders/o1/delivery')).toHaveLength(1)
  })

  it('lets the customer repeat a request whose answer was lost', async () => {
    let attempts = 0
    const { calls } = server({ order: CONFIRMED, delivery: null }, (current) => {
      attempts += 1
      if (attempts === 1) {
        return problem(503, 'indisponível')
      }
      return created(current)
    })
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Solicitar entrega' }))
    expect(await screen.findByText(/repetir não cria outra entrega/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Solicitar entrega' }))

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

describe('delivery route seen by the customer', () => {
  it('shows the saved plan without offering to plan or operate the delivery', async () => {
    const { calls } = server({ order: REQUESTED, delivery: DELIVERY, plan: PLAN }, created)
    await openOrder()

    expect(await screen.findByText('A → B → C')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /rota A → B → C/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rota/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Simulação operacional' })).not.toBeInTheDocument()
    expect(calls('/api/deliveries/by-order/o1')[0][1]?.headers).toMatchObject({ Authorization: 'Bearer token-123' })
    expect(calls('/api/deliveries/d1/route')[0][1]?.headers).toMatchObject({ Authorization: 'Bearer token-123' })
  })

  it('explains that the operation calculates the route', async () => {
    server({ order: REQUESTED, delivery: DELIVERY }, created)
    await openOrder()

    expect(await screen.findByText('A rota aparece quando a operação a calcular.')).toBeInTheDocument()
  })

  it('explains when the route comes from a model trained on simulated deliveries', async () => {
    server({ order: REQUESTED, delivery: DELIVERY, plan: { ...PLAN, dataOrigin: 'simulated' } }, created)
    await openOrder()

    expect(
      await screen.findByText('Modelo treinado com entregas simuladas: não representa trânsito real'),
    ).toBeInTheDocument()
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
  })
})
