import { useCallback, useState } from 'react'
import { ApiError, NETWORK_ERROR_STATUS } from '../api/client'
import { canPlanRoute, getRoutePlan, planRoute, type Delivery, type RoutePlan } from '../api/deliveries'
import { useLoad } from '../api/useLoad'
import { describeRoute, isInSyntheticCity } from '../checkout/syntheticCity'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { RouteMap } from './RouteMap'

const timeFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
const distanceFormatter = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 })

const UNAVAILABLE = 'O serviço de rotas não respondeu. O plano anterior, se houver, foi preservado.'

function minutes(value: number): string {
  return `${Math.max(1, Math.round(value))} min`
}

const DATA_ORIGINS: Record<string, string> = {
  synthetic: 'Sintéticos: não representam trânsito real',
  simulated: 'Modelo treinado com entregas simuladas: não representa trânsito real',
}

// Rota prevista pelo modelo sobre a cidade sintética, desenhada no mapa com origem e destino da entrega.
// Somente o operador calcula a rota; o cliente vê o plano salvo.
export function RouteSection({ delivery, canPlan = false }: { delivery: Delivery; canPlan?: boolean }) {
  const { session } = useAuth()
  const token = session?.token ?? ''
  // O plano fica num objeto para distinguir "ainda carregando" (data nulo) de "sem plano" (plan nulo).
  const load = useCallback(
    async (signal: AbortSignal) => ({ plan: await getRoutePlan(token, delivery.id, signal) }),
    [token, delivery.id],
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
      setData({ plan: await planRoute(token, delivery.id, new Date()) })
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
        <>
          <dl className="route-stats">
            <div>
              <dt>Distância</dt>
              <dd>{distanceFormatter.format(plan.distanceKm)} km</dd>
            </div>
            <div>
              <dt>Tempo previsto</dt>
              <dd>{minutes(plan.predictedTravelTimeMinutes)}</dd>
            </div>
            <div>
              <dt>Percurso</dt>
              <dd>{describeRoute(plan.route)}</dd>
            </div>
          </dl>
          <dl className="facts">
            <dt>Partida considerada</dt>
            <dd>{timeFormatter.format(new Date(plan.departureAt))}</dd>
            <dt>Modelo</dt>
            <dd>
              <code>{plan.modelVersion}</code> sobre <code>{plan.graphVersion}</code>
            </dd>
            <dt>Dados</dt>
            <dd>{DATA_ORIGINS[plan.dataOrigin] ?? plan.dataOrigin}</dd>
          </dl>
        </>
      ) : (
        data && (
          <p className="muted">
            {canPlan ? 'Nenhuma rota foi calculada para esta entrega.' : 'A rota aparece quando a operação a calcular.'}
          </p>
        )
      )}
      {canPlan && canPlanRoute(delivery) && (
        <button type="button" className="secondary" disabled={busy} onClick={handlePlan}>
          {busy ? 'Calculando…' : plan ? 'Recalcular rota' : 'Calcular rota'}
        </button>
      )}
    </div>
  )
}
