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
