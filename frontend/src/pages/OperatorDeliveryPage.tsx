import { useCallback } from 'react'
import { Link, useParams } from 'react-router'
import { getDelivery } from '../api/deliveries'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { DeliveryTimeline } from '../deliveries/DeliveryTimeline'
import { deliveryStatusLabel } from '../deliveries/deliveryStatus'
import { OperationsPanel } from '../deliveries/OperationsPanel'
import { RouteSection } from '../deliveries/RouteSection'
import { useDeliveryTracking } from '../deliveries/useDeliveryTracking'

// Uma entrega vista pelo operador: andamento, comandos da simulação e cálculo da rota.
export function OperatorDeliveryPage() {
  const { deliveryId = '' } = useParams()
  const { session } = useAuth()
  const token = session?.token ?? ''
  const load = useCallback((signal: AbortSignal) => getDelivery(token, deliveryId, signal), [token, deliveryId])
  const tracking = useDeliveryTracking(load, true)
  const delivery = tracking.delivery

  return (
    <section className="card delivery" aria-label="Entrega">
      <p>
        <Link to="/operations">Voltar às entregas</Link>
      </p>
      <h1>Entrega {delivery && <span className="badge">{deliveryStatusLabel(delivery)}</span>}</h1>
      {tracking.pending && <Alert>Entrega não encontrada.</Alert>}
      {tracking.error && <Alert>{tracking.error}</Alert>}
      {!delivery && !tracking.pending && !tracking.error && <p aria-busy="true">Carregando entrega…</p>}
      {delivery && (
        <>
          <dl className="facts">
            <dt>Coleta</dt>
            <dd>{delivery.origin.description}</dd>
            <dt>Destino</dt>
            <dd>{delivery.destination.description}</dd>
            <dt>Pedido</dt>
            <dd>
              <code>{delivery.orderId}</code>
            </dd>
          </dl>
          <DeliveryTimeline delivery={delivery} />
          <OperationsPanel delivery={delivery} onChange={tracking.setDelivery} onConflict={tracking.reload} />
          <RouteSection delivery={delivery} canPlan />
        </>
      )}
    </section>
  )
}
