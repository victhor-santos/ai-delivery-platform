import { useState } from 'react'
import { ApiError, NETWORK_ERROR_STATUS } from '../api/client'
import { requestDelivery } from '../api/deliveries'
import { getOrder, type Order } from '../api/orders'
import { useFailureMessage } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { DeliveryTimeline } from './DeliveryTimeline'
import { deliveryStatusLabel } from './deliveryStatus'
import { useDeliveryTracking } from './useDeliveryTracking'

const UNKNOWN_RESULT =
  'O serviço de entregas não respondeu. A solicitação ficou registrada; tente novamente para concluí-la.'

// Pedido confirmado: solicita a entrega e acompanha seu andamento.
export function DeliverySection({ order, onOrderChange }: { order: Order; onOrderChange: (order: Order) => void }) {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const failureMessage = useFailureMessage()
  const requested = order.deliveryRequestedAt !== null
  const tracking = useDeliveryTracking(order.id, requested)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (order.status !== 'CONFIRMED') {
    return null
  }

  // A mesma chamada cria a entrega ou recupera a já existente.
  async function handleRequest() {
    setNotice(null)
    setBusy(true)
    try {
      await requestDelivery(token, order.id)
    } catch (failure) {
      const unknown =
        failure instanceof ApiError && (failure.status === NETWORK_ERROR_STATUS || failure.status >= 500)
      setNotice(unknown ? UNKNOWN_RESULT : failureMessage(failure, 'Não foi possível solicitar a entrega.'))
    }
    try {
      const next = await getOrder(token, order.id)
      onOrderChange(next)
    } catch {
      // A mensagem da solicitação já explica o resultado; o pedido anterior continua visível.
    }
    // Na primeira solicitação, a consulta começa sozinha quando o pedido passa a ter deliveryRequestedAt.
    if (requested) {
      tracking.reload()
    }
    setBusy(false)
  }

  return (
    <section className="delivery" aria-label="Entrega">
      <h2>
        Entrega {tracking.delivery && <span className="badge">{deliveryStatusLabel(tracking.delivery)}</span>}
      </h2>
      {notice && <Alert>{notice}</Alert>}
      {!requested && (
        <>
          <p className="muted">O pedido está pago. Solicite a entrega para acompanhar o entregador.</p>
          <button type="button" disabled={busy} onClick={handleRequest}>
            {busy ? 'Solicitando…' : 'Solicitar entrega'}
          </button>
        </>
      )}
      {requested && tracking.missing && (
        <>
          <Alert kind="info">A entrega foi solicitada, mas o serviço de entregas ainda não a confirmou.</Alert>
          <button type="button" disabled={busy} onClick={handleRequest}>
            {busy ? 'Solicitando…' : 'Tentar novamente'}
          </button>
        </>
      )}
      {requested && tracking.error && <Alert>{tracking.error}</Alert>}
      {requested && !tracking.delivery && !tracking.missing && !tracking.error && (
        <p aria-busy="true">Carregando entrega…</p>
      )}
      {tracking.delivery && <DeliveryTimeline delivery={tracking.delivery} />}
    </section>
  )
}
