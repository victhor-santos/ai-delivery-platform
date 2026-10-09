import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { login } from '../api/auth'
import { AuthContext, type AuthState, type SignOutReason } from './AuthContext'
import { clearSession, createSession, loadSession, saveSession, type Session } from './session'

const EXPIRED_NOTICE = 'Sua sessão expirou. Entre novamente.'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession())
  const [notice, setNotice] = useState<string | null>(null)

  const signIn = useCallback(async (email: string, password: string) => {
    const token = await login(email, password)
    const next = createSession(token.accessToken, Date.now() + token.expiresIn * 1000)
    saveSession(next)
    setNotice(null)
    setSession(next)
  }, [])

  const signOut = useCallback((reason: SignOutReason = 'user') => {
    clearSession()
    setNotice(reason === 'expired' ? EXPIRED_NOTICE : null)
    setSession(null)
  }, [])

  useEffect(() => {
    if (!session) {
      return
    }
    const timer = setTimeout(() => signOut('expired'), Math.max(session.expiresAt - Date.now(), 0))
    return () => clearTimeout(timer)
  }, [session, signOut])

  const value = useMemo<AuthState>(() => ({ session, notice, signIn, signOut }), [session, notice, signIn, signOut])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
