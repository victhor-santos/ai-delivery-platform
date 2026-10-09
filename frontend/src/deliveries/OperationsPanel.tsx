import { useState } from 'react'
import { ApiError } from '../api/client'
import {
  assignCourier,
  createCourier,
  runDeliveryCommand,
  type Delivery,
  type DeliveryCommand,
} from '../api/deliveries'
import { useFailureMessage } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'

type Action = { label: string; run: (token: string, delivery: Delivery) => Promise<Delivery> }

// Cada entrega recebe um entregador novo: o serviço não lista entregadores, e um ocupado recusaria a atribuição.
async function assignNewCourier(token: string, delivery: Delivery): Promise<Delivery> {
  const courier = await createCourier(token)
  return assignCourier(token, delivery.id, courier.id)
}

function command(label: string, name: DeliveryCommand): Action {
  return { label, run: (token, delivery) => runDeliveryCommand(token, delivery.id, name) }
}

function nextAction(delivery: Delivery): Action | null {
  switch (delivery.status) {
    case 'CREATED':
      return { label: 'Atribuir entregador', run: assignNewCourier }
    case 'ASSIGNED':
      return command('Registrar coleta', 'pick-up')
    case 'PICKED_UP':
      return command('Sair para entrega', 'start-transit')
    case 'IN_TRANSIT':
      return delivery.arrivedAt ? command('Concluir entrega', 'complete') : command('Registrar chegada', 'arrive')
    default:
      return null
  }
}

const CANCEL = command('Cancelar entrega', 'cancel')

type Props = {
  delivery: Delivery
  onChange: (delivery: Delivery) => void
  // Um conflito indica que a entrega mudou em outro lugar; a página consulta o estado atual.
  onConflict: () => void
}

// Exclusivo do operador: sem contas de entregador na V1, o operador avança a entrega pela demonstração.
export function OperationsPanel({ delivery, onChange, onConflict }: Props) {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const failureMessage = useFailureMessage()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const next = nextAction(delivery)
  const cancellable = delivery.status === 'CREATED' || delivery.status === 'ASSIGNED'

  if (!next && !cancellable) {
    return null
  }

  async function handle(action: Action) {
    setError(null)
    setBusy(true)
    try {
      onChange(await action.run(token, delivery))
    } catch (failure) {
      setError(failureMessage(failure, 'Não foi possível atualizar a entrega.'))
      if (failure instanceof ApiError && failure.status === 409) {
        onConflict()
      }
    }
    setBusy(false)
  }

  return (
    <div className="card nested operations" role="group" aria-label="Simulação operacional">
      <h3>Simulação operacional</h3>
      <small>Sem contas de entregador nesta versão, o operador registra cada etapa na demonstração.</small>
      {error && <Alert>{error}</Alert>}
      <div className="row">
        {next && (
          <button type="button" disabled={busy} onClick={() => handle(next)}>
            {next.label}
          </button>
        )}
        {cancellable && (
          <button type="button" className="secondary" disabled={busy} onClick={() => handle(CANCEL)}>
            {CANCEL.label}
          </button>
        )}
      </div>
    </div>
  )
}
