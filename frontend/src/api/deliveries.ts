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
export async function getRoutePlan(deliveryId: string, signal?: AbortSignal): Promise<RoutePlan | null> {
  try {
    return await request<RoutePlan>(`/api/deliveries/${encodeURIComponent(deliveryId)}/route`, { signal })
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) {
      return null
    }
    throw failure
  }
}

// Cada chamada consulta o modelo e substitui o plano anterior; uma falha preserva o plano salvo.
export function planRoute(deliveryId: string, departureAt: Date): Promise<RoutePlan> {
  return request(`/api/deliveries/${encodeURIComponent(deliveryId)}/route`, {
    method: 'POST',
    body: { departureAt: departureAt.toISOString() },
  })
}

export function canPlanRoute(delivery: Delivery): boolean {
  return delivery.status === 'CREATED' || delivery.status === 'ASSIGNED' || delivery.status === 'PICKED_UP'
}
