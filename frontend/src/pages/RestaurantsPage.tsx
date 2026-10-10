import { useCallback } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listRestaurants, type Restaurant } from '../api/catalog'
import { useLoad } from '../api/useLoad'
import { syntheticPointAt } from '../checkout/syntheticCity'
import { Alert } from '../components/Alert'

function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter((word) => word.length > 2)
  const first = words[0] ?? name.trim()
  const last = words.length > 1 ? words[words.length - 1] : ''
  return ((first[0] ?? '?') + (last[0] ?? first[1] ?? '')).toUpperCase()
}

function pickupLabel(restaurant: Restaurant): string {
  const location = restaurant.pickupLocation
  if (!location) {
    return 'Sem local de coleta'
  }
  const point = syntheticPointAt(location.latitude, location.longitude)
  return point ? `Coleta no ponto ${point.id}` : 'Coleta fora da cidade sintética'
}

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
      <header className="page-header">
        <div>
          <h1>Restaurantes</h1>
          <p>Escolha onde pedir. Os preços e a coleta vêm do catálogo.</p>
        </div>
      </header>
      {error && <Alert>{error}</Alert>}
      {!error && !data && (
        <p className="loading" aria-busy="true">
          Carregando…
        </p>
      )}
      {data && data.content.length === 0 && <p className="empty">Nenhum restaurante cadastrado.</p>}
      {data && data.content.length > 0 && (
        <ul className="restaurant-grid">
          {data.content.map((restaurant) => (
            <li key={restaurant.id} className={`card restaurant-card${restaurant.active ? '' : ' closed'}`}>
              <span className="avatar" aria-hidden="true">
                {initials(restaurant.name)}
              </span>
              <div className="details">
                <Link to={`/restaurants/${restaurant.id}`}>{restaurant.name}</Link>
                <small>{pickupLabel(restaurant)}</small>
              </div>
              {!restaurant.active && <span className="badge tone-danger">Fechado</span>}
            </li>
          ))}
        </ul>
      )}
      {data && data.totalPages > 1 && (
        <nav className="pager" aria-label="Páginas">
          <button type="button" className="secondary" disabled={page === 0} onClick={() => goTo(page - 1)}>
            Anterior
          </button>
          <span>
            Página {page + 1} de {data.totalPages}
          </span>
          <button
            type="button"
            className="secondary"
            disabled={page + 1 >= data.totalPages}
            onClick={() => goTo(page + 1)}
          >
            Próxima
          </button>
        </nav>
      )}
    </section>
  )
}
