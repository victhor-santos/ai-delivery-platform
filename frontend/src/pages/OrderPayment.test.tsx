import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Order } from '../api/orders'
import { json, mockFetch, problem } from '../test/http'
import { renderAt, signIn } from '../test/render'

const CREATED: Order = {
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
  items: [{ menuItemId: 'i1', name: 'Prato do dia', quantity: 2, unitPrice: 25.9, lineTotal: 51.8 }],
  total: 51.8,
  currency: 'BRL',
  paymentRequestedAt: null,
  paymentId: null,
}
const CONFIRMED: Order = { ...CREATED, status: 'CONFIRMED', confirmedAt: '2026-10-08T12:01:00Z', paymentId: 'p1' }
const PAYING: Order = { ...CREATED, paymentRequestedAt: '2026-10-08T12:01:00Z' }

function payment(status: 'APPROVED' | 'DECLINED', declineReason: string | null = null) {
  return {
    orderId: 'o1',
    paymentId: 'p1',
    status,
    declineReason,
    method: 'sim-card-approved',
    amount: 51.8,
    currency: 'BRL',
    requestedAt: '2026-10-08T12:01:00Z',
    completedAt: '2026-10-08T12:01:00Z',
  }
}

// O servidor guarda o estado do pedido; cada handler pode alterá-lo antes de responder.
function server(initial: Order, pay: (state: { order: Order }) => Response) {
  const state = { order: initial }
  const fetchMock = mockFetch({
    'GET /api/orders/o1': () => json(200, state.order),
    'GET /api/catalog/restaurants/r1': () =>
      json(200, { id: 'r1', name: 'Cantina', active: true, pickupLocation: null }),
    'POST /api/orders/o1/payment': () => pay(state),
    'POST /api/orders/o1/cancel': () => {
      state.order = { ...state.order, status: 'CANCELLED', cancelledAt: '2026-10-08T12:02:00Z' }
      return json(200, state.order)
    },
  })
  const paymentCalls = () =>
    fetchMock.mock.calls
      .filter(([url]) => url === '/api/orders/o1/payment')
      .map(([, init]) => ({
        key: (init!.headers as Record<string, string>)['Idempotency-Key'],
        body: JSON.parse(init!.body as string) as unknown,
      }))
  return { state, paymentCalls }
}

async function openOrder() {
  signIn()
  const user = userEvent.setup()
  renderAt('/orders/o1')
  await screen.findByRole('heading', { name: /Pedido/ })
  return user
}

describe('order payment', () => {
  it('confirms the order when the simulated payment is approved', async () => {
    const { paymentCalls } = server(CREATED, (state) => {
      state.order = CONFIRMED
      return json(200, payment('APPROVED'))
    })
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Pagar' }))

    expect(await screen.findByText('Confirmado')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Pagamento aprovado')
    expect(screen.queryByRole('button', { name: 'Cancelar pedido' })).not.toBeInTheDocument()
    expect(paymentCalls()).toEqual([{ key: expect.any(String), body: { method: 'sim-card-approved' } }])
    expect(paymentCalls()[0].key.length).toBeGreaterThanOrEqual(8)
    expect(localStorage.length).toBe(0)
  })

  it('reports a decline and uses a new key for the next attempt', async () => {
    const { paymentCalls } = server(CREATED, () => json(200, payment('DECLINED', 'INSUFFICIENT_FUNDS')))
    const user = await openOrder()

    await user.selectOptions(screen.getByLabelText('Método de pagamento'), 'Cartão simulado: saldo insuficiente')
    await user.click(screen.getByRole('button', { name: 'Pagar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Pagamento recusado: saldo insuficiente')
    expect(screen.getByText('Aguardando pagamento')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Pagar' }))

    await screen.findByRole('alert')
    const [first, second] = paymentCalls()
    expect(first.body).toEqual({ method: 'sim-card-insufficient-funds' })
    expect(second.key).not.toBe(first.key)
  })

  it('resumes an unknown outcome with the same key and method', async () => {
    let attempts = 0
    const { paymentCalls } = server(CREATED, (state) => {
      attempts++
      if (attempts === 1) {
        state.order = PAYING
        return problem(503, 'Pagamento indisponível.')
      }
      state.order = CONFIRMED
      return json(200, payment('APPROVED'))
    })
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Pagar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('sem nova cobrança')
    expect(screen.getByText('Pagamento em andamento')).toBeInTheDocument()
    expect(screen.getByLabelText('Método de pagamento')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Cancelar pedido' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Retomar pagamento' }))

    expect(await screen.findByText('Confirmado')).toBeInTheDocument()
    const [first, second] = paymentCalls()
    expect(second).toEqual(first)
  })

  it('resumes a pending payment after the page is reopened', async () => {
    localStorage.setItem('delivery.payment.o1', JSON.stringify({ key: 'chave-salva-1', method: 'sim-card-declined' }))
    const { paymentCalls } = server(PAYING, (state) => {
      state.order = CREATED
      return json(200, payment('DECLINED', 'CARD_DECLINED'))
    })
    const user = await openOrder()

    expect(screen.getByLabelText('Método de pagamento')).toHaveDisplayValue('Cartão simulado: recusado')
    await user.click(screen.getByRole('button', { name: 'Retomar pagamento' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('cartão recusado')
    expect(paymentCalls()).toEqual([{ key: 'chave-salva-1', body: { method: 'sim-card-declined' } }])
    expect(localStorage.length).toBe(0)
  })

  it('explains a payment in progress that this browser cannot resume', async () => {
    server(PAYING, () => json(200, payment('APPROVED')))
    await openOrder()

    expect(screen.getByRole('status')).toHaveTextContent('outra sessão ou navegador')
    expect(screen.queryByRole('form', { name: 'Pagamento' })).not.toBeInTheDocument()
  })

  it('shows a conflict and forgets the key', async () => {
    server(CREATED, (state) => {
      state.order = { ...CREATED, status: 'CANCELLED', cancelledAt: '2026-10-08T12:02:00Z' }
      return problem(409, 'A operação não é permitida no estado atual do pedido.')
    })
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Pagar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('não é permitida')
    expect(screen.getByText('Cancelado')).toBeInTheDocument()
    expect(localStorage.length).toBe(0)
  })

  it('cancels an unpaid order', async () => {
    server(CREATED, () => json(200, payment('APPROVED')))
    const user = await openOrder()

    await user.click(screen.getByRole('button', { name: 'Cancelar pedido' }))

    expect(await screen.findByText('Cancelado')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Pedido cancelado.')
    expect(screen.queryByRole('form', { name: 'Pagamento' })).not.toBeInTheDocument()
  })
})
