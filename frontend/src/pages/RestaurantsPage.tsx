import { useCallback } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listRestaurants } from '../api/catalog'
import { useLoad } from '../api/useLoad'
import { Alert } from '../components/Alert'

function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page') ?? '0')
  return Number.isInteger(page) && page >= 0 ? page : 0
}

export function RestaurantsPage() {
  const [params, setParams] = useSearchParams()
  const page = pageFrom(params)
  const load = useCallback((signal: AbortSignal) => listRestaurants(page, signal), [page])
  const { data, error } = useLoad(load, 'Não foi possível carregar os restaurantes.')

  function goTo(next: number) {
    setParams(next === 0 ? {} : { page: String(next) })
  }

  return (
    <section>
      <h1>Restaurantes</h1>
      {error && <Alert>{error}</Alert>}
      {!error && !data && <p aria-busy="true">Carregando…</p>}
      {data && data.content.length === 0 && <p>Nenhum restaurante cadastrado.</p>}
      {data && data.content.length > 0 && (
        <ul className="list">
          {data.content.map((restaurant) => (
            <li key={restaurant.id} className="card">
              <Link to={`/restaurants/${restaurant.id}`}>{restaurant.name}</Link>
              {!restaurant.active && <span className="badge">Fechado</span>}
            </li>
          ))}
        </ul>
      )}
      {data && data.totalPages > 1 && (
        <nav className="pager" aria-label="Páginas">
          <button type="button" disabled={page === 0} onClick={() => goTo(page - 1)}>
            Anterior
          </button>
          <span>
            Página {page + 1} de {data.totalPages}
          </span>
          <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => goTo(page + 1)}>
            Próxima
          </button>
        </nav>
      )}
    </section>
  )
}
