import type { Delivery } from '../api/deliveries'
import { timelineSteps } from './deliveryStatus'

const timeFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'medium' })

export function DeliveryTimeline({ delivery }: { delivery: Delivery }) {
  return (
    <ol className="timeline" aria-label="Andamento da entrega">
      {timelineSteps(delivery).map((step) => (
        <li key={step.label} className={step.at ? 'done' : undefined}>
          <span>{step.label}</span>
          <small>{step.at ? timeFormatter.format(new Date(step.at)) : 'Pendente'}</small>
        </li>
      ))}
    </ol>
  )
}
