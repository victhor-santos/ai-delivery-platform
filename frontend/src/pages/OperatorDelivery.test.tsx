import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Delivery, RoutePlan } from '../api/deliveries'
import { json, mockFetch, problem } from '../test/http'
import { OPERATOR_TOKEN, renderAt, signIn, signInAsOperator } from '../test/render'

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

type State = { delivery: Delivery; plan: RoutePlan | null }

const STATUSES = ['CREATED', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'] as const

function listRoutes(current: () => Delivery) {
  const pageOf = (items: Delivery[], size: number) =>
    json(200, { items: items.slice(0, size), page: 0, size, totalElements: items.length })
  const routes: Record<string, () => Response> = {
    'GET /api/deliveries?page=0&size=20': () => pageOf([current()], 20),
    'GET /api/deliveries?page=0&size=1': () => pageOf([current()], 1),
  }
  for (const status of STATUSES) {
    const matching = () => (current().status === status ? [current()] : [])
    routes[`GET /api/deliveries?page=0&size=20&status=${status}`] = () => pageOf(matching(), 20)
    routes[`GET /api/deliveries?page=0&size=1&status=${status}`] = () => pageOf(matching(), 1)
  }
  return routes
}

function server(delivery: Delivery, plan: (state: State) => Response = (state) => {
  state.plan = PLAN
  return json(200, PLAN)
}, initialPlan: RoutePlan | null = null) {
  const state: State = { delivery, plan: initialPlan }
  const fetchMock = mockFetch({
    ...listRoutes(() => state.delivery),
    'GET /api/deliveries/d1': () => json(200, state.delivery),
    'GET /api/deliveries/d1/route': () =>
      state.plan ? json(200, state.plan) : problem(404, 'A entrega ainda não possui um plano de rota.'),
    'POST /api/deliveries/d1/route': () => plan(state),
    'POST /api/deliveries/couriers': () => json(201, { id: 'c1', active: true }),
    'POST /api/deliveries/d1/assign': () =>
      state.delivery.status === 'CREATED'
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
  state.delivery = { ...state.delivery, ...changes, updatedAt: at(9) }
  return json(200, state.delivery)
}

async function openDelivery() {
  signInAsOperator()
  const user = userEvent.setup()
  renderAt('/operations/deliveries/d1')
  await screen.findByText('Cantina')
  return user
}

describe('operations console', () => {
  it('lists the deliveries for the operator with counts and filters them by status', async () => {
    const { calls } = server(DELIVERY)
    signInAsOperator()
    const user = userEvent.setup()
    renderAt('/operations')

    const list = await screen.findByRole('list', { name: 'Entregas' })
    expect(within(list).getByRole('link', { name: 'Ponto sintético C' })).toHaveAttribute(
      'href',
      '/operations/deliveries/d1',
    )
    expect(calls('/api/deliveries?page=0&size=20')[0][1]?.headers).toMatchObject({
      Authorization: `Bearer ${OPERATOR_TOKEN}`,
    })
    const filters = screen.getByRole('list', { name: 'Situação' })
    expect(within(filters).getByRole('button', { name: /^Todas,\s*1$/ })).toHaveAttribute('aria-pressed', 'true')
    expect(within(filters).getByRole('button', { name: /^Aguardando entregador,\s*1$/ })).toBeInTheDocument()

    await user.click(within(filters).getByRole('button', { name: /^Canceladas,\s*0$/ }))

    expect(await screen.findByText('Nenhuma entrega nesta situação.')).toBeInTheDocument()
    expect(within(filters).getByRole('button', { name: /^Canceladas,\s*0$/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('link', { name: 'Operação' })).toBeInTheDocument()
  })

  it('keeps customers out of the operations area', async () => {
    server(DELIVERY)
    signIn()
    renderAt('/operations')

    expect(await screen.findByRole('alert')).toHaveTextContent('Esta área é exclusiva da operação.')
    expect(screen.queryByRole('link', { name: 'Operação' })).not.toBeInTheDocument()
  })
})

describe('delivery operations', () => {
  it('drives the simulated delivery from assignment to completion', async () => {
    const { calls } = server(DELIVERY)
    const user = await openDelivery()
    const panel = await screen.findByRole('group', { name: 'Simulação operacional' })

    const steps = [
      ['Atribuir entregador', 'Entregador a caminho do restaurante'],
      ['Registrar coleta', 'Pedido coletado'],
      ['Sair para entrega', 'A caminho'],
      ['Registrar chegada', 'Entregador no destino'],
      ['Concluir entrega', 'Entregue'],
    ]
    for (const [button, status] of steps) {
      await user.click(within(panel).getByRole('button', { name: button }))
      expect(await screen.findByText(status, { selector: '.badge' })).toBeInTheDocument()
    }

    expect(screen.queryByRole('group', { name: 'Simulação operacional' })).not.toBeInTheDocument()
    const timeline = screen.getByRole('list', { name: 'Andamento da entrega' })
    expect(within(timeline).getAllByRole('listitem').every((step) => step.classList.contains('done'))).toBe(true)
    const assignment = calls('/api/deliveries/d1/assign')[0][1]!
    expect(JSON.parse(assignment.body as string)).toEqual({ courierId: 'c1' })
    expect(assignment.headers).toMatchObject({ Authorization: `Bearer ${OPERATOR_TOKEN}` })
  })

  it('cancels a delivery before pickup', async () => {
    server(DELIVERY)
    const user = await openDelivery()

    await user.click(await screen.findByRole('button', { name: 'Cancelar entrega' }))

    expect(await screen.findByText('Entrega cancelada', { selector: '.badge' })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Simulação operacional' })).not.toBeInTheDocument()
  })

  it('reloads the delivery after a conflicting command', async () => {
    const { state, calls } = server(DELIVERY)
    const user = await openDelivery()
    await screen.findByRole('group', { name: 'Simulação operacional' })
    const lookups = calls('/api/deliveries/d1').length

    // Outra aba já atribuiu um entregador; o comando desta página fica fora de ordem.
    state.delivery = { ...DELIVERY, status: 'ASSIGNED', courierId: 'c0', assignedAt: at(3), updatedAt: at(3) }
    await user.click(screen.getByRole('button', { name: 'Atribuir entregador' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('A entrega já tem entregador.')
    expect(await screen.findByText('Entregador a caminho do restaurante', { selector: '.badge' })).toBeInTheDocument()
    expect(calls('/api/deliveries/d1').length).toBeGreaterThan(lookups)
  })
})

describe('delivery route planned by the operator', () => {
  it('plans the route and draws it on the synthetic city', async () => {
    const { calls } = server(DELIVERY)
    const user = await openDelivery()

    expect(await screen.findByText('Nenhuma rota foi calculada para esta entrega.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Calcular rota' }))

    expect(await screen.findByText('A → B → C')).toBeInTheDocument()
    expect(screen.getByText('2,9 km')).toBeInTheDocument()
    expect(screen.getByText('16 min')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /rota A → B → C/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Recalcular rota' })).toBeInTheDocument()
    const planning = calls('/api/deliveries/d1/route')[1][1]!
    const body = JSON.parse(planning.body as string) as { departureAt: string }
    expect(Number.isNaN(Date.parse(body.departureAt))).toBe(false)
    expect(planning.headers).toMatchObject({ Authorization: `Bearer ${OPERATOR_TOKEN}` })
  })

  it('keeps the saved plan when the route service is unavailable', async () => {
    server(DELIVERY, () => problem(503, 'indisponível'), PLAN)
    const user = await openDelivery()

    await user.click(await screen.findByRole('button', { name: 'Recalcular rota' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('O serviço de rotas não respondeu.')
    expect(screen.getByText('A → B → C')).toBeInTheDocument()
  })

  it('shows why a route cannot be found', async () => {
    server(DELIVERY, () => problem(422, 'Não há caminho entre a coleta e o destino.'))
    const user = await openDelivery()

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
    server(departed, undefined, PLAN)
    await openDelivery()

    expect(await screen.findByText('A → B → C')).toBeInTheDocument()
    expect(screen.getByText('A caminho', { selector: '.badge' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rota/ })).not.toBeInTheDocument()
  })
})
