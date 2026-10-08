import { request } from './client'

export type Destination = {
  address: string
  latitude: number
  longitude: number
}

export type OrderStatus = 'CREATED' | 'CONFIRMED' | 'CANCELLED'

export type OrderItem = {
  menuItemId: string
  name: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

// Pedidos anteriores aos itens não têm composição nem valor: items vazio, total e currency nulos.
export type Order = {
  id: string
  customerId: string
  restaurantId: string
  destination: Destination
  status: OrderStatus
  createdAt: string
  updatedAt: string
  confirmedAt: string | null
  cancelledAt: string | null
  deliveryRequestedAt: string | null
  items: OrderItem[]
  total: number | null
  currency: 'BRL' | null
  paymentRequestedAt: string | null
  paymentId: string | null
}

export type NewOrder = {
  restaurantId: string
  destination: Destination
  items: { menuItemId: string; quantity: number }[]
}

// O cadastro não é idempotente: repetir a chamada cria outro pedido.
export function createOrder(token: string, order: NewOrder): Promise<Order> {
  return request('/api/orders', { method: 'POST', body: order, token })
}

export function getOrder(token: string, id: string, signal?: AbortSignal): Promise<Order> {
  return request(`/api/orders/${encodeURIComponent(id)}`, { token, signal })
}

export type PaymentMethod = 'sim-card-approved' | 'sim-card-declined' | 'sim-card-insufficient-funds'

// Recusa é um resultado registrado (200), não um erro HTTP.
export type OrderPayment = {
  orderId: string
  paymentId: string
  status: 'APPROVED' | 'DECLINED'
  declineReason: string | null
  method: PaymentMethod
  amount: number
  currency: 'BRL'
  requestedAt: string
  completedAt: string
}

// Repetir com a mesma chave retoma a mesma intenção, sem nova cobrança.
export function payOrder(
  token: string,
  orderId: string,
  method: PaymentMethod,
  idempotencyKey: string,
): Promise<OrderPayment> {
  return request(`/api/orders/${encodeURIComponent(orderId)}/payment`, {
    method: 'POST',
    body: { method },
    token,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function cancelOrder(token: string, orderId: string): Promise<Order> {
  return request(`/api/orders/${encodeURIComponent(orderId)}/cancel`, { method: 'POST', token })
}
