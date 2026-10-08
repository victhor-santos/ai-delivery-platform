import type { PaymentMethod } from '../api/orders'

// Uma intenção de pagamento sem resultado conhecido (503, falha de rede) só avança quando o cliente repete
// a requisição com a mesma chave. A chave fica no localStorage para sobreviver a recarregar ou fechar a aba.

export type PendingPayment = {
  key: string
  method: PaymentMethod
}

const PREFIX = 'delivery.payment.'

export function loadPendingPayment(orderId: string): PendingPayment | null {
  try {
    const raw = localStorage.getItem(PREFIX + orderId)
    if (!raw) {
      return null
    }
    const pending = JSON.parse(raw) as Partial<PendingPayment>
    return typeof pending.key === 'string' && typeof pending.method === 'string'
      ? { key: pending.key, method: pending.method }
      : null
  } catch {
    return null
  }
}

export function savePendingPayment(orderId: string, pending: PendingPayment): void {
  try {
    localStorage.setItem(PREFIX + orderId, JSON.stringify(pending))
  } catch {
    // Sem storage, a retomada só funciona enquanto a página estiver aberta.
  }
}

export function clearPendingPayment(orderId: string): void {
  try {
    localStorage.removeItem(PREFIX + orderId)
  } catch {
    // Nada a remover.
  }
}

// crypto.randomUUID só existe em contextos seguros (HTTPS ou localhost); fora deles a chave usa getRandomValues.
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}
