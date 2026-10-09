import { useCallback } from 'react'
import { Link, useParams } from 'react-router'
import { getRestaurant } from '../api/catalog'
import { getOrder, type Order } from '../api/orders'
import { useLoad } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { formatAmount } from '../checkout/money'
import { OrderActions } from '../checkout/OrderActions'
import { clearPendingPayment } from '../checkout/pendingPayment'
import { Alert } from '../components/Alert'
import { DeliverySection } from '../deliveries/DeliverySection'

const dateFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

function statusLabel(order: Order): string {
  switch (order.status) {
    case 'CONFIRMED':
      return 'Confirmado'
    case 'CANCELLED':
      return 'Cancelado'
    default:
      return order.paymentRequestedAt ? 'Pagamento em andamento' : 'Aguardando pagamento'
  }
}

export function OrderPage() {
  const { orderId = '' } = useParams()
  const { session } = useAuth()
  const token = session?.token ?? ''
  const load = useCallback(
    async (signal: AbortSignal) => {
      const order = await getOrder(token, orderId, signal)
      if (order.status !== 'CREATED') {
        clearPendingPayment(order.id)
      }
      // O nome do restaurante é só informativo; sem ele o pedido continua utilizável.
      const restaurant = await getRestaurant(order.restaurantId, signal).catch(() => null)
      return { order, restaurantName: restaurant?.name ?? null }
    },
    [token, orderId],
  )
  const { data, error, setData } = useLoad(load, 'Não foi possível carregar o pedido.')

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
  const { order, restaurantName } = data
  return (
    <section className="card">
      <h1>
        Pedido <span className="badge">{statusLabel(order)}</span>
      </h1>
      <dl className="facts">
        <dt>Restaurante</dt>
        <dd>
          <Link to={`/restaurants/${order.restaurantId}`}>{restaurantName ?? 'Ver restaurante'}</Link>
        </dd>
        <dt>Destino</dt>
        <dd>{order.destination.address}</dd>
        <dt>Criado em</dt>
        <dd>{dateFormatter.format(new Date(order.createdAt))}</dd>
        <dt>Número</dt>
        <dd>
          <code>{order.id}</code>
        </dd>
      </dl>
      {order.items.length > 0 && (
        <ul className="lines" aria-label="Itens do pedido">
          {order.items.map((item) => (
            <li key={item.menuItemId}>
              <span>
                {item.quantity} × {item.name} <small>({formatAmount(item.unitPrice)} cada)</small>
              </span>
              <span>{formatAmount(item.lineTotal)}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="total">
        <span>Total</span>
        <strong>{order.total === null ? 'Valor desconhecido' : formatAmount(order.total)}</strong>
      </p>
      <OrderActions order={order} onChange={(next) => setData({ order: next, restaurantName })} />
      <DeliverySection order={order} onOrderChange={(next) => setData({ order: next, restaurantName })} />
    </section>
  )
}
