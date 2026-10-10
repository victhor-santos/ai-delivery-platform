import type { Delivery } from '../api/deliveries'

export function deliveryStatusLabel(delivery: Delivery): string {
  switch (delivery.status) {
    case 'CREATED':
      return 'Aguardando entregador'
    case 'ASSIGNED':
      return 'Entregador a caminho do restaurante'
    case 'PICKED_UP':
      return 'Pedido coletado'
    case 'IN_TRANSIT':
      return delivery.arrivedAt ? 'Entregador no destino' : 'A caminho'
    case 'DELIVERED':
      return 'Entregue'
    case 'CANCELLED':
      return 'Entrega cancelada'
  }
}

export type Tone = 'success' | 'warning' | 'info' | 'danger'

export function deliveryStatusTone(delivery: Delivery): Tone {
  switch (delivery.status) {
    case 'CREATED':
      return 'warning'
    case 'DELIVERED':
      return 'success'
    case 'CANCELLED':
      return 'danger'
    default:
      return 'info'
  }
}

export type Step = { label: string; at: string | null }

// Uma entrega cancelada encerra a linha do tempo no cancelamento; os passos seguintes não acontecerão.
export function timelineSteps(delivery: Delivery): Step[] {
  const all: Step[] = [
    { label: 'Entrega criada', at: delivery.createdAt },
    { label: 'Entregador atribuído', at: delivery.assignedAt },
    { label: 'Pedido coletado', at: delivery.pickedUpAt },
    { label: 'Saiu para entrega', at: delivery.departedAt },
    { label: 'Chegou ao destino', at: delivery.arrivedAt },
    { label: 'Entregue', at: delivery.deliveredAt },
  ]
  if (delivery.cancelledAt === null) {
    return all
  }
  return [...all.filter((step) => step.at !== null), { label: 'Entrega cancelada', at: delivery.cancelledAt }]
}
