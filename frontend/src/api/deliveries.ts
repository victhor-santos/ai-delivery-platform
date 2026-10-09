import { ApiError, request } from './client'

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

// 202: o Order registrou a solicitação e a publica no RabbitMQ; a entrega aparece quando o Delivery a consome.
export type DeliveryRequestReceipt = {
  orderId: string
  status: 'REQUESTED'
}

// Idempotente: repetir devolve a mesma solicitação e nunca cria outra entrega.
export function requestDelivery(token: string, orderId: string): Promise<DeliveryRequestReceipt> {
  return request(`/api/orders/${encodeURIComponent(orderId)}/delivery`, { method: 'POST', token })
}

// O cliente só vê a entrega dos próprios pedidos; a de outra pessoa responde 404, como uma inexistente.
export function getDeliveryByOrder(token: string, orderId: string, signal?: AbortSignal): Promise<Delivery> {
  return request(`/api/deliveries/by-order/${encodeURIComponent(orderId)}`, { token, signal })
}

export function getDelivery(token: string, deliveryId: string, signal?: AbortSignal): Promise<Delivery> {
  return request(`/api/deliveries/${encodeURIComponent(deliveryId)}`, { token, signal })
}

export type DeliveryPage = {
  items: Delivery[]
  page: number
  size: number
  totalElements: number
}

// Somente o operador lista entregas; as mais recentes vêm primeiro.
export function listDeliveries(
  token: string,
  status: DeliveryStatus | null,
  page: number,
  signal?: AbortSignal,
  size = 20,
): Promise<DeliveryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) {
    query.set('status', status)
  }
  return request(`/api/deliveries?${query.toString()}`, { token, signal })
}

export function isFinished(delivery: Delivery): boolean {
  return delivery.status === 'DELIVERED' || delivery.status === 'CANCELLED'
}

export type RoutePoint = {
  latitude: number
  longitude: number
}

export type RouteSegment = {
  segmentId: string
  distanceKm: number
  predictedTravelTimeMinutes: number
}

// Último plano salvo pelo Delivery Service; tempos previstos pelo modelo sobre dados sintéticos.
export type RoutePlan = {
  id: string
  deliveryId: string
  departureAt: string
  plannedAt: string
  route: RoutePoint[]
  segments: RouteSegment[]
  distanceKm: number
  predictedTravelTimeMinutes: number
  predictedAt: string
  contextAsOf: string
  modelVersion: string
  graphVersion: string
  dataOrigin: string
}

// Sem plano salvo, o servidor responde 404; aqui isso vira null.
export async function getRoutePlan(token: string, deliveryId: string, signal?: AbortSignal): Promise<RoutePlan | null> {
  try {
    return await request<RoutePlan>(`/api/deliveries/${encodeURIComponent(deliveryId)}/route`, { token, signal })
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) {
      return null
    }
    throw failure
  }
}

// Somente o operador planeja. Cada chamada consulta o modelo e substitui o plano anterior; uma falha preserva o
// plano salvo.
export function planRoute(token: string, deliveryId: string, departureAt: Date): Promise<RoutePlan> {
  return request(`/api/deliveries/${encodeURIComponent(deliveryId)}/route`, {
    method: 'POST',
    token,
    body: { departureAt: departureAt.toISOString() },
  })
}

export function canPlanRoute(delivery: Delivery): boolean {
  return delivery.status === 'CREATED' || delivery.status === 'ASSIGNED' || delivery.status === 'PICKED_UP'
}

export type Courier = {
  id: string
  active: boolean
}

// Comandos operacionais do ciclo, exclusivos do operador. Repetir um comando já aplicado responde 409; consulte a
// entrega antes de tentar de novo.
export function createCourier(token: string): Promise<Courier> {
  return request('/api/deliveries/couriers', { method: 'POST', token })
}

export function assignCourier(token: string, deliveryId: string, courierId: string): Promise<Delivery> {
  return request(`/api/deliveries/${encodeURIComponent(deliveryId)}/assign`, {
    method: 'POST',
    body: { courierId },
    token,
  })
}

export type DeliveryCommand = 'pick-up' | 'start-transit' | 'arrive' | 'complete' | 'cancel'

export function runDeliveryCommand(token: string, deliveryId: string, command: DeliveryCommand): Promise<Delivery> {
  return request(`/api/deliveries/${encodeURIComponent(deliveryId)}/${command}`, { method: 'POST', token })
}
