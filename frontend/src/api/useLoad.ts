import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from './client'

// Converte uma falha em mensagem; um 401 encerra a sessão e não precisa de mensagem na página.
export function useFailureMessage(): (failure: unknown, fallback: string) => string | null {
  const { signOut } = useAuth()
  return useCallback(
    (failure: unknown, fallback: string) => {
      if (failure instanceof ApiError && failure.status === 401) {
        signOut('expired')
        return null
      }
      return failure instanceof ApiError ? failure.message : fallback
    },
    [signOut],
  )
}

export type Loaded<T> = {
  data: T | null
  error: string | null
  setData: (data: T) => void
}

type Loader<T> = (signal: AbortSignal) => Promise<T>

type Result<T> = { load: Loader<T>; data: T | null; error: string | null }

// Carrega dados ao montar e sempre que `load` mudar; a requisição anterior é abortada.
// O resultado guarda o loader que o produziu, então um loader novo começa sem dados nem erro.
export function useLoad<T>(load: Loader<T>, fallback: string): Loaded<T> {
  const failureMessage = useFailureMessage()
  const [result, setResult] = useState<Result<T> | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    load(controller.signal)
      .then((data) => setResult({ load, data, error: null }))
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) {
          setResult({ load, data: null, error: failureMessage(failure, fallback) })
        }
      })
    return () => controller.abort()
  }, [load, fallback, failureMessage])

  const setData = useCallback((data: T) => setResult({ load, data, error: null }), [load])
  const current = result?.load === load ? result : null
  return { data: current?.data ?? null, error: current?.error ?? null, setData }
}
