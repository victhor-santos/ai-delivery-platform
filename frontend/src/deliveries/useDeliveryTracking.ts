import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { getDeliveryByOrder, isFinished, type Delivery } from '../api/deliveries'

export const POLL_INTERVAL_MS = 5_000

const FALLBACK = 'Não foi possível consultar a entrega.'

type Tracking = {
  delivery: Delivery | null
  // O pedido registrou a intenção, mas o Delivery Service ainda não tem a entrega (resposta perdida ou 503).
  missing: boolean
  error: string | null
}

const EMPTY: Tracking = { delivery: null, missing: false, error: null }

// Consulta a entrega do pedido e repete a consulta até ela terminar. Uma falha passageira mantém a última
// entrega visível e continua tentando; 404 para a consulta até o cliente repetir a solicitação.
export function useDeliveryTracking(orderId: string, enabled: boolean) {
  const [tracking, setTracking] = useState<Tracking>(EMPTY)
  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    if (!enabled) {
      return
    }
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    async function poll() {
      try {
        const delivery = await getDeliveryByOrder(orderId, controller.signal)
        // Uma consulta iniciada antes de um comando não pode desfazer o resultado dele na tela.
        setTracking((current) =>
          current.delivery && Date.parse(current.delivery.updatedAt) > Date.parse(delivery.updatedAt)
            ? { ...current, error: null }
            : { delivery, missing: false, error: null },
        )
        if (!isFinished(delivery)) {
          timer = setTimeout(poll, POLL_INTERVAL_MS)
        }
      } catch (failure) {
        if (controller.signal.aborted) {
          return
        }
        if (failure instanceof ApiError && failure.status === 404) {
          setTracking({ delivery: null, missing: true, error: null })
          return
        }
        const error = failure instanceof ApiError ? failure.message : FALLBACK
        setTracking((current) => ({ ...current, error }))
        timer = setTimeout(poll, POLL_INTERVAL_MS)
      }
    }

    void poll()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [orderId, enabled, reloads])

  const reload = useCallback(() => setReloads((count) => count + 1), [])
  const setDelivery = useCallback((delivery: Delivery) => setTracking({ delivery, missing: false, error: null }), [])
  return { ...tracking, reload, setDelivery }
}
