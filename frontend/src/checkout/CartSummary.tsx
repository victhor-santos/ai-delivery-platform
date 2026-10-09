import { useState } from 'react'
import { useNavigate } from 'react-router'
import type { Coordinates } from '../api/catalog'
import { createOrder, type Destination } from '../api/orders'
import { useFailureMessage } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { totalCents, type CartLine } from './cart'
import { DestinationPicker } from './DestinationPicker'
import { formatCents } from './money'

type Props = {
  restaurantId: string
  pickup: Coordinates | null
  lines: readonly CartLine[]
}

function itemCount(lines: readonly CartLine[]): number {
  return lines.reduce((count, line) => count + line.quantity, 0)
}

export function CartSummary({ restaurantId, pickup, lines }: Props) {
  const { session } = useAuth()
  const navigate = useNavigate()
  const failureMessage = useFailureMessage()
  const [destination, setDestination] = useState<Destination | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function placeOrder() {
    if (!destination) {
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const order = await createOrder(session?.token ?? '', {
        restaurantId,
        destination,
        items: lines.map((line) => ({ menuItemId: line.item.id, quantity: line.quantity })),
      })
      navigate(`/orders/${order.id}`)
    } catch (failure) {
      setError(failureMessage(failure, 'Não foi possível criar o pedido.'))
      setSubmitting(false)
    }
  }

  return (
    <section className="card summary" aria-labelledby="summary-title">
      <h2 id="summary-title">Seu pedido</h2>
      {lines.length > 0 && (
        <span className="summary-count">
          {itemCount(lines) === 1 ? '1 item' : `${itemCount(lines)} itens`}
        </span>
      )}
      {lines.length === 0 ? (
        <p className="muted">Escolha itens do cardápio.</p>
      ) : (
        <>
          <ul className="lines">
            {lines.map((line) => (
              <li key={line.item.id}>
                <span>
                  {line.quantity} × {line.item.name}
                </span>
                <span>{formatCents(line.lineCents)}</span>
              </li>
            ))}
          </ul>
          <p className="total">
            <span>Total</span>
            <strong>{formatCents(totalCents(lines))}</strong>
          </p>
          <small>O valor final é calculado pelo servidor com os preços do cardápio no momento do pedido.</small>
        </>
      )}
      <DestinationPicker pickup={pickup} onChange={setDestination} />
      {error && <Alert>{error}</Alert>}
      <button type="button" disabled={lines.length === 0 || !destination || submitting} onClick={placeOrder}>
        {submitting ? 'Enviando…' : 'Fazer pedido'}
      </button>
    </section>
  )
}
