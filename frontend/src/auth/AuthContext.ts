import { createContext, useContext } from 'react'
import type { Session } from './session'

export type SignOutReason = 'user' | 'expired'

export type AuthState = {
  session: Session | null
  notice: string | null
  signIn: (email: string, password: string) => Promise<void>
  signOut: (reason?: SignOutReason) => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const auth = useContext(AuthContext)
  if (!auth) {
    throw new Error('useAuth deve ser usado dentro de AuthProvider')
  }
  return auth
}
