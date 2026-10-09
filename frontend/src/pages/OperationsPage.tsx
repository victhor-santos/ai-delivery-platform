import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listDeliveries, type DeliveryPage, type DeliveryStatus } from '../api/deliveries'
import { useFailureMessage } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { deliveryStatusLabel, deliveryStatusTone } from '../deliveries/deliveryStatus'

const PAGE_SIZE = 20
export const REFRESH_INTERVAL_MS = 10_000
const timeFormatter = new Intl.DateTimeFormat('pt-BR', { timeStyle: 'medium' })
const dateFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

const FILTERS: { value: DeliveryStatus | null; label: string }[] = [
  { value: null, label: 'Todas' },
  { value: 'CREATED', label: 'Aguardando entregador' },
  { value: 'ASSIGNED', label: 'Entregador atribuído' },
  { value: 'PICKED_UP', label: 'Coletadas' },
  { value: 'IN_TRANSIT', label: 'A caminho' },
  { value: 'DELIVERED', label: 'Entregues' },
  { value: 'CANCELLED', label: 'Canceladas' },
]

type Snapshot = {
  page: DeliveryPage
  counts: Map<DeliveryStatus | null, number>
  loadedAt: Date
}

function statusFrom(params: URLSearchParams): DeliveryStatus | null {
  const status = params.get('status')
  return FILTERS.find((filter) => filter.value !== null && filter.value === status)?.value ?? null
}

function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page') ?? '0')
  return Number.isInteger(page) && page >= 0 ? page : 0
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

export function OperationsPage() {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const failureMessage = useFailureMessage()
  const [params, setParams] = useSearchParams()
  const status = statusFrom(params)
  const page = pageFrom(params)
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    async function refresh() {
      try {
        const [current, ...totals] = await Promise.all([
          listDeliveries(token, status, page, controller.signal),
          ...FILTERS.map((filter) => listDeliveries(token, filter.value, 0, controller.signal, 1)),
        ])
        const counts = new Map(FILTERS.map((filter, index) => [filter.value, totals[index].totalElements]))
        setSnapshot({ page: current, counts, loadedAt: new Date() })
        setError(null)
      } catch (failure) {
        if (controller.signal.aborted) {
          return
        }
        setError(failureMessage(failure, 'Não foi possível carregar as entregas.'))
      }
      timer = setTimeout(refresh, REFRESH_INTERVAL_MS)
    }

    void refresh()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [token, status, page, failureMessage])

  const data = snapshot?.page ?? null
  const totalPages = data ? Math.max(1, Math.ceil(data.totalElements / PAGE_SIZE)) : 1

  function show(next: { status: DeliveryStatus | null; page: number }) {
    const query: Record<string, string> = {}
    if (next.status) query.status = next.status
    if (next.page > 0) query.page = String(next.page)
    setSnapshot(null)
    setParams(query)
  }

  return (
    <section>
      <header className="page-header">
        <div>
          <h1>Operação</h1>
          <p>Todas as entregas, das mais recentes para as mais antigas.</p>
        </div>
      </header>
      <ul className="status-filters" aria-label="Situação">
        {FILTERS.map((filter) => (
          <li key={filter.label}>
            <button
              type="button"
              aria-pressed={filter.value === status}
              onClick={() => show({ status: filter.value, page: 0 })}
            >
              {filter.label}
              {snapshot && (
                <>
                  <span className="visually-hidden">, </span>
                  <span className="count">{snapshot.counts.get(filter.value) ?? 0}</span>
                </>
              )}
            </button>
          </li>
        ))}
      </ul>
      {error && <Alert>{error}</Alert>}
      {!error && !data && (
        <p className="loading" aria-busy="true">
          Carregando…
        </p>
      )}
      {snapshot && (
        <div className="toolbar">
          <span>
            {data && data.totalElements === 1 ? '1 entrega' : `${data?.totalElements ?? 0} entregas`}
          </span>
          <span>Atualizado às {timeFormatter.format(snapshot.loadedAt)}</span>
        </div>
      )}
      {data && data.items.length === 0 && <p className="empty">Nenhuma entrega nesta situação.</p>}
      {data && data.items.length > 0 && (
        <ul className="list" aria-label="Entregas">
          {data.items.map((delivery) => (
            <li key={delivery.id} className="card delivery-row">
              <Link to={`/operations/deliveries/${delivery.id}`}>{delivery.destination.description}</Link>
              <span className={`badge tone-${deliveryStatusTone(delivery)}`}>{deliveryStatusLabel(delivery)}</span>
              <small>
                Coleta em {delivery.origin.description} · pedido {shortId(delivery.orderId)} · criada em{' '}
                {dateFormatter.format(new Date(delivery.createdAt))}
              </small>
            </li>
          ))}
        </ul>
      )}
      {data && totalPages > 1 && (
        <nav className="pager" aria-label="Páginas">
          <button
            type="button"
            className="secondary"
            disabled={page === 0}
            onClick={() => show({ status, page: page - 1 })}
          >
            Anterior
          </button>
          <span>
            Página {page + 1} de {totalPages}
          </span>
          <button
            type="button"
            className="secondary"
            disabled={page + 1 >= totalPages}
            onClick={() => show({ status, page: page + 1 })}
          >
            Próxima
          </button>
        </nav>
      )}
    </section>
  )
}
