import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router'
import { getRestaurant, listMenuItems } from '../api/catalog'
import { useLoad } from '../api/useLoad'
import { cartLines, MAX_DISTINCT_ITEMS, MAX_QUANTITY, setQuantity, type Cart } from '../checkout/cart'
import { CartSummary } from '../checkout/CartSummary'
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
  const [cart, setCart] = useState<Cart>(new Map())

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
    return (
      <p className="loading" aria-busy="true">
        Carregando…
      </p>
    )
  }
  const { restaurant, menu } = data
  const lines = cartLines(cart, menu)
  return (
    <div className={restaurant.active ? 'checkout' : undefined}>
      <section>
        <Link className="back-link" to="/restaurants">
          ← Restaurantes
        </Link>
        <header className="page-header">
          <div>
            <h1>{restaurant.name}</h1>
            <p>{menu.length === 1 ? '1 item no cardápio' : `${menu.length} itens no cardápio`}</p>
          </div>
          {!restaurant.active && <span className="badge tone-danger">Fechado</span>}
        </header>
        {!restaurant.active && <Alert kind="info">Este restaurante está fechado e não aceita pedidos.</Alert>}
        {menu.length === 0 ? (
          <p className="empty">Nenhum item no cardápio.</p>
        ) : (
          <ul className="list" aria-label="Cardápio">
            {menu.map((item) => (
              <li
                key={item.id}
                className={`card menu-item${item.available ? '' : ' unavailable'}${cart.has(item.id) ? ' selected' : ''}`}
              >
                <div>
                  <strong>{item.name}</strong>
                  {item.description && <p className="muted">{item.description}</p>}
                </div>
                <div className="menu-item-side">
                  <span className="price">{formatAmount(item.price)}</span>
                  {!item.available && <span className="badge tone-warning">Indisponível</span>}
                  {restaurant.active && item.available && (
                    <Stepper
                      name={item.name}
                      quantity={cart.get(item.id) ?? 0}
                      canAdd={cart.has(item.id) || cart.size < MAX_DISTINCT_ITEMS}
                      onChange={(quantity) => setCart((current) => setQuantity(current, item.id, quantity))}
                    />
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
      {restaurant.active && (
        <CartSummary restaurantId={restaurant.id} pickup={restaurant.pickupLocation} lines={lines} />
      )}
    </div>
  )
}

function Stepper({
  name,
  quantity,
  canAdd,
  onChange,
}: {
  name: string
  quantity: number
  canAdd: boolean
  onChange: (quantity: number) => void
}) {
  return (
    <div className="stepper">
      <button
        type="button"
        className="secondary"
        aria-label={`Remover uma unidade de ${name}`}
        disabled={quantity === 0}
        onClick={() => onChange(quantity - 1)}
      >
        −
      </button>
      <output aria-label={`Quantidade de ${name}`}>{quantity}</output>
      <button
        type="button"
        className="secondary"
        aria-label={`Adicionar uma unidade de ${name}`}
        disabled={quantity >= MAX_QUANTITY || !canAdd}
        onClick={() => onChange(quantity + 1)}
      >
        +
      </button>
    </div>
  )
}
