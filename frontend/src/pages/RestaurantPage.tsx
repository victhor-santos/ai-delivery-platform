import { useCallback } from 'react'
import { Link, useParams } from 'react-router'
import { getRestaurant, listMenuItems } from '../api/catalog'
import { useLoad } from '../api/useLoad'
import { formatAmount } from '../checkout/money'
import { Alert } from '../components/Alert'

export function RestaurantPage() {
  const { restaurantId = '' } = useParams()
  const load = useCallback(
    async (signal: AbortSignal) => {
      const [restaurant, menu] = await Promise.all([
        getRestaurant(restaurantId, signal),
        listMenuItems(restaurantId, signal),
      ])
      return { restaurant, menu }
    },
    [restaurantId],
  )
  const { data, error } = useLoad(load, 'Não foi possível carregar o cardápio.')

  if (error) {
    return (
      <>
        <Alert>{error}</Alert>
        <p>
          <Link to="/restaurants">Voltar aos restaurantes</Link>
        </p>
      </>
    )
  }
  if (!data) {
    return <p aria-busy="true">Carregando…</p>
  }
  const { restaurant, menu } = data
  return (
    <section>
      <p>
        <Link to="/restaurants">← Restaurantes</Link>
      </p>
      <h1>{restaurant.name}</h1>
      {!restaurant.active && <Alert kind="info">Este restaurante está fechado e não aceita pedidos.</Alert>}
      {menu.length === 0 ? (
        <p>Nenhum item no cardápio.</p>
      ) : (
        <ul className="list" aria-label="Cardápio">
          {menu.map((item) => (
            <li key={item.id} className={`card menu-item${item.available ? '' : ' unavailable'}`}>
              <div>
                <strong>{item.name}</strong>
                {item.description && <p className="muted">{item.description}</p>}
              </div>
              <div className="menu-item-side">
                <span>{formatAmount(item.price)}</span>
                {!item.available && <span className="badge">Indisponível</span>}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
