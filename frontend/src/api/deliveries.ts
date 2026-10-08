import { request } from './client'

export type DeliveryStatus = 'CREATED' | 'ASSIGNED' | 'PICKED_UP' | 'IN_TRANSIT' | 'DELIVERED' | 'CANCELLED'

export type DeliveryLocation = {
  description: string
  latitude: number
  longitude: number
}

// Horários de eventos ainda não ocorridos e o entregador ainda não atribuído são nulos.
export type Delivery = {
  id: string
  orderId: string
  origin: DeliveryLocation
  destination: DeliveryLocation
  courierId: string | null
  status: DeliveryStatus
  createdAt: string
  updatedAt: string
  assignedAt: string | null
  pickedUpAt: string | null
  departedAt: string | null
  arrivedAt: string | null
  deliveredAt: string | null
  cancelledAt: string | null
}

export type DeliveryReceipt = {
  orderId: string
  deliveryId: string
  status: DeliveryStatus
}

// Idempotente: repetir recupera a mesma entrega, inclusive depois de uma resposta perdida (503).
export function requestDelivery(token: string, orderId: string): Promise<DeliveryReceipt> {
  return request(`/api/orders/${encodeURIComponent(orderId)}/delivery`, { method: 'POST', token })
}

// Entregas ainda são públicas no servidor; o pedido continua protegido pelo token do dono.
export function getDeliveryByOrder(orderId: string, signal?: AbortSignal): Promise<Delivery> {
  return request(`/api/deliveries/by-order/${encodeURIComponent(orderId)}`, { signal })
}

export function isFinished(delivery: Delivery): boolean {
  return delivery.status === 'DELIVERED' || delivery.status === 'CANCELLED'
}
