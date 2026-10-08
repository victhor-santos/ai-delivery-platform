import { useState, type FormEvent } from 'react'
import { ApiError, NETWORK_ERROR_STATUS } from '../api/client'
import { cancelOrder, getOrder, payOrder, type Order, type OrderPayment, type PaymentMethod } from '../api/orders'
import { useFailureMessage } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { clearPendingPayment, loadPendingPayment, newIdempotencyKey, savePendingPayment } from './pendingPayment'

const METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'sim-card-approved', label: 'Cartão simulado: aprovado' },
  { value: 'sim-card-declined', label: 'Cartão simulado: recusado' },
  { value: 'sim-card-insufficient-funds', label: 'Cartão simulado: saldo insuficiente' },
]

const DECLINE_REASONS: Record<string, string> = {
  CARD_DECLINED: 'cartão recusado',
  INSUFFICIENT_FUNDS: 'saldo insuficiente',
}

const UNKNOWN_RESULT =
  'Não foi possível saber o resultado do pagamento. Tente novamente: a mesma tentativa será retomada, sem nova cobrança.'

type Notice = { kind: 'error' | 'info'; text: string }

// Sem resposta definitiva (rede, 5xx) a intenção pode estar registrada; a chave precisa ser mantida.
function outcomeUnknown(failure: unknown): boolean {
  return failure instanceof ApiError && (failure.status === NETWORK_ERROR_STATUS || failure.status >= 500)
}

function paymentNotice(payment: OrderPayment): Notice {
  if (payment.status === 'APPROVED') {
    return { kind: 'info', text: 'Pagamento aprovado. O pedido está confirmado.' }
  }
  const reason = DECLINE_REASONS[payment.declineReason ?? ''] ?? 'motivo não informado'
  return { kind: 'error', text: `Pagamento recusado: ${reason}. Escolha outro método ou cancele o pedido.` }
}

export function OrderActions({ order, onChange }: { order: Order; onChange: (order: Order) => void }) {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const failureMessage = useFailureMessage()
  const [method, setMethod] = useState<PaymentMethod>('sim-card-approved')
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)

  // Só uma intenção registrada no servidor pode ser retomada; sem ela, uma chave salva é descartada.
  const pending = order.paymentRequestedAt ? loadPendingPayment(order.id) : null

  async function refresh() {
    try {
      onChange(await getOrder(token, order.id))
    } catch {
      // A mensagem da operação já explica o que aconteceu; o estado anterior continua visível.
    }
  }

  async function handlePay(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const attempt = pending ?? { key: newIdempotencyKey(), method }
    savePendingPayment(order.id, attempt)
    setNotice(null)
    setBusy(true)
    try {
      const payment = await payOrder(token, order.id, attempt.method, attempt.key)
      clearPendingPayment(order.id)
      setNotice(paymentNotice(payment))
    } catch (failure) {
      if (outcomeUnknown(failure)) {
        setNotice({ kind: 'error', text: UNKNOWN_RESULT })
      } else {
        if (!(failure instanceof ApiError && failure.status === 401)) {
          clearPendingPayment(order.id)
        }
        const text = failureMessage(failure, 'Não foi possível pagar o pedido.')
        setNotice(text ? { kind: 'error', text } : null)
      }
    }
    await refresh()
    setBusy(false)
  }

  async function handleCancel() {
    setNotice(null)
    setBusy(true)
    try {
      onChange(await cancelOrder(token, order.id))
      setNotice({ kind: 'info', text: 'Pedido cancelado.' })
    } catch (failure) {
      const text = failureMessage(failure, 'Não foi possível cancelar o pedido.')
      setNotice(text ? { kind: 'error', text } : null)
      await refresh()
    }
    setBusy(false)
  }

  const payable = order.status === 'CREATED' && order.total !== null
  return (
    <>
      {notice && <Alert kind={notice.kind}>{notice.text}</Alert>}
      {payable && order.paymentRequestedAt && !pending && (
        <Alert kind="info">
          Há um pagamento em andamento iniciado em outra sessão ou navegador; ele só pode ser retomado de lá.
        </Alert>
      )}
      {payable && (!order.paymentRequestedAt || pending) && (
        <form className="payment" onSubmit={handlePay} aria-label="Pagamento">
          <label>
            Método de pagamento
            <select
              value={pending?.method ?? method}
              disabled={pending !== null || busy}
              onChange={(event) => setMethod(event.target.value as PaymentMethod)}
            >
              {METHODS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <small>Nenhum dinheiro é movimentado: o resultado depende só do método escolhido.</small>
          <button type="submit" disabled={busy}>
            {busy ? 'Processando…' : pending ? 'Retomar pagamento' : 'Pagar'}
          </button>
        </form>
      )}
      {order.status === 'CREATED' && !order.paymentRequestedAt && (
        <button type="button" className="secondary" disabled={busy} onClick={handleCancel}>
          Cancelar pedido
        </button>
      )}
    </>
  )
}
