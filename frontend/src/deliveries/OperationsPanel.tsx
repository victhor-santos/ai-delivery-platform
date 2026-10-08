import { useState } from 'react'
import { ApiError } from '../api/client'
import {
  assignCourier,
  createCourier,
  runDeliveryCommand,
  type Delivery,
  type DeliveryCommand,
} from '../api/deliveries'
import { Alert } from '../components/Alert'

type Action = { label: string; run: (delivery: Delivery) => Promise<Delivery> }

// Cada entrega recebe um entregador novo: o serviço não lista entregadores, e um ocupado recusaria a atribuição.
async function assignNewCourier(delivery: Delivery): Promise<Delivery> {
  const courier = await createCourier()
  return assignCourier(delivery.id, courier.id)
}

function command(label: string, name: DeliveryCommand): Action {
  return { label, run: (delivery) => runDeliveryCommand(delivery.id, name) }
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

// Na V1 não há contas de entregador ou operador. O painel avança a entrega para a demonstração.
export function OperationsPanel({ delivery, onChange, onConflict }: Props) {
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
      onChange(await action.run(delivery))
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Não foi possível atualizar a entrega.')
      if (failure instanceof ApiError && failure.status === 409) {
        onConflict()
      }
    }
    setBusy(false)
  }

  return (
    <div className="card nested operations" role="group" aria-label="Simulação operacional">
      <h3>Simulação operacional</h3>
      <small>
        Sem contas de entregador ou operador nesta versão, estes comandos fazem o papel deles na demonstração.
      </small>
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
