import { useCallback, useState } from 'react'
import { ApiError, NETWORK_ERROR_STATUS } from '../api/client'
import { canPlanRoute, getRoutePlan, planRoute, type Delivery, type RoutePlan } from '../api/deliveries'
import { useLoad } from '../api/useLoad'
import { describeRoute, isInSyntheticCity } from '../checkout/syntheticCity'
import { Alert } from '../components/Alert'
import { RouteMap } from './RouteMap'

const timeFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
const distanceFormatter = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 })

const UNAVAILABLE = 'O serviço de rotas não respondeu. O plano anterior, se houver, foi preservado.'

function minutes(value: number): string {
  return `${Math.max(1, Math.round(value))} min`
}

// Rota prevista pelo modelo sobre a cidade sintética, desenhada no mapa com origem e destino da entrega.
export function RouteSection({ delivery }: { delivery: Delivery }) {
  // O plano fica num objeto para distinguir "ainda carregando" (data nulo) de "sem plano" (plan nulo).
  const load = useCallback(
    async (signal: AbortSignal) => ({ plan: await getRoutePlan(delivery.id, signal) }),
    [delivery.id],
  )
  const { data, error, setData } = useLoad<{ plan: RoutePlan | null }>(load, 'Não foi possível carregar a rota.')
  const plan = data?.plan ?? null
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const covered =
    isInSyntheticCity(delivery.origin.latitude, delivery.origin.longitude) &&
    isInSyntheticCity(delivery.destination.latitude, delivery.destination.longitude)
  if (!covered) {
    return (
      <Alert kind="info">
        A coleta ou o destino fica fora da cidade sintética, então esta entrega não tem rota prevista.
      </Alert>
    )
  }

  // A partida é o instante atual: o modelo usa o horário como contexto de tráfego simulado.
  async function handlePlan() {
    setNotice(null)
    setBusy(true)
    try {
      setData({ plan: await planRoute(delivery.id, new Date()) })
    } catch (failure) {
      const unavailable =
        failure instanceof ApiError && (failure.status === NETWORK_ERROR_STATUS || failure.status >= 500)
      setNotice(unavailable || !(failure instanceof ApiError) ? UNAVAILABLE : failure.message)
    }
    setBusy(false)
  }

  return (
    <div className="route-section">
      <h3>Rota</h3>
      {error && <Alert>{error}</Alert>}
      {notice && <Alert>{notice}</Alert>}
      <RouteMap origin={delivery.origin} destination={delivery.destination} route={plan?.route ?? null} />
      {plan ? (
        <dl className="facts">
          <dt>Percurso</dt>
          <dd>{describeRoute(plan.route)}</dd>
          <dt>Distância</dt>
          <dd>{distanceFormatter.format(plan.distanceKm)} km</dd>
          <dt>Tempo previsto</dt>
          <dd>{minutes(plan.predictedTravelTimeMinutes)}</dd>
          <dt>Partida considerada</dt>
          <dd>{timeFormatter.format(new Date(plan.departureAt))}</dd>
          <dt>Modelo</dt>
          <dd>
            <code>{plan.modelVersion}</code> sobre <code>{plan.graphVersion}</code>
          </dd>
          <dt>Dados</dt>
          <dd>{plan.dataOrigin === 'synthetic' ? 'Sintéticos: não representam trânsito real' : plan.dataOrigin}</dd>
        </dl>
      ) : (
        data && <p className="muted">Nenhuma rota foi calculada para esta entrega.</p>
      )}
      {canPlanRoute(delivery) && (
        <button type="button" className="secondary" disabled={busy} onClick={handlePlan}>
          {busy ? 'Calculando…' : plan ? 'Recalcular rota' : 'Calcular rota'}
        </button>
      )}
    </div>
  )
}
