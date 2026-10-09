import { useCallback } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listDeliveries, type DeliveryStatus } from '../api/deliveries'
import { useLoad } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { deliveryStatusLabel } from '../deliveries/deliveryStatus'

const PAGE_SIZE = 20
const dateFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

const FILTERS: { value: DeliveryStatus | ''; label: string }[] = [
  { value: '', label: 'Todas' },
  { value: 'CREATED', label: 'Aguardando entregador' },
  { value: 'ASSIGNED', label: 'Entregador atribuído' },
  { value: 'PICKED_UP', label: 'Coletadas' },
  { value: 'IN_TRANSIT', label: 'A caminho' },
  { value: 'DELIVERED', label: 'Entregues' },
  { value: 'CANCELLED', label: 'Canceladas' },
]

function statusFrom(params: URLSearchParams): DeliveryStatus | null {
  const status = params.get('status')
  return FILTERS.some((filter) => filter.value !== '' && filter.value === status) ? (status as DeliveryStatus) : null
}

function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page') ?? '0')
  return Number.isInteger(page) && page >= 0 ? page : 0
}

// Console do operador: todas as entregas, das mais recentes para as mais antigas.
export function OperationsPage() {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const [params, setParams] = useSearchParams()
  const status = statusFrom(params)
  const page = pageFrom(params)
  const load = useCallback(
    (signal: AbortSignal) => listDeliveries(token, status, page, signal),
    [token, status, page],
  )
  const { data, error } = useLoad(load, 'Não foi possível carregar as entregas.')
  const totalPages = data ? Math.max(1, Math.ceil(data.totalElements / PAGE_SIZE)) : 1

  function show(next: { status: DeliveryStatus | null; page: number }) {
    const query: Record<string, string> = {}
    if (next.status) query.status = next.status
    if (next.page > 0) query.page = String(next.page)
    setParams(query)
  }

  return (
    <section>
      <h1>Operação</h1>
      <label>
        Situação
        <select
          value={status ?? ''}
          onChange={(event) => show({ status: (event.target.value || null) as DeliveryStatus | null, page: 0 })}
        >
          {FILTERS.map((filter) => (
            <option key={filter.value} value={filter.value}>
              {filter.label}
            </option>
          ))}
        </select>
      </label>
      {error && <Alert>{error}</Alert>}
      {!error && !data && <p aria-busy="true">Carregando…</p>}
      {data && data.items.length === 0 && <p>Nenhuma entrega nesta situação.</p>}
      {data && data.items.length > 0 && (
        <ul className="list" aria-label="Entregas">
          {data.items.map((delivery) => (
            <li key={delivery.id} className="card">
              <Link to={`/operations/deliveries/${delivery.id}`}>{delivery.destination.description}</Link>
              <span className="badge">{deliveryStatusLabel(delivery)}</span>
              <small>Criada em {dateFormatter.format(new Date(delivery.createdAt))}</small>
            </li>
          ))}
        </ul>
      )}
      {data && totalPages > 1 && (
        <nav className="pager" aria-label="Páginas">
          <button type="button" disabled={page === 0} onClick={() => show({ status, page: page - 1 })}>
            Anterior
          </button>
          <span>
            Página {page + 1} de {totalPages}
          </span>
          <button type="button" disabled={page + 1 >= totalPages} onClick={() => show({ status, page: page + 1 })}>
            Próxima
          </button>
        </nav>
      )}
    </section>
  )
}
