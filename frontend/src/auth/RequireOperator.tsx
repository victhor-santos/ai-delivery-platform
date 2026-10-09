import type { ReactNode } from 'react'
import { Alert } from '../components/Alert'
import { useAuth } from './AuthContext'
import { RequireAuth } from './RequireAuth'

// Esconde a operação de quem não é operador; o servidor recusa os comandos de qualquer forma.
export function RequireOperator({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  return (
    <RequireAuth>
      {session?.role === 'OPERATOR' ? children : <Alert>Esta área é exclusiva da operação.</Alert>}
    </RequireAuth>
  )
}
