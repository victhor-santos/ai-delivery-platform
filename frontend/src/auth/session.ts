// O token fica no sessionStorage: sobrevive ao recarregar a aba e some ao fechá-la.
// Não há refresh token; a sessão termina na expiração informada pelo login (15 minutos).

export type Role = 'CUSTOMER' | 'OPERATOR'

export type Session = {
  token: string
  expiresAt: number
  role: Role
}

// O papel só adapta a interface; cada serviço valida a assinatura e o papel do token a cada requisição.
export function roleFromToken(token: string): Role {
  try {
    const payload = token.split('.')[1]
    if (!payload) {
      return 'CUSTOMER'
    }
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const claims = JSON.parse(json) as { roles?: unknown }
    return Array.isArray(claims.roles) && claims.roles.includes('OPERATOR') ? 'OPERATOR' : 'CUSTOMER'
  } catch {
    return 'CUSTOMER'
  }
}

export function createSession(token: string, expiresAt: number): Session {
  return { token, expiresAt, role: roleFromToken(token) }
}

const STORAGE_KEY = 'delivery.session'

export function loadSession(now = Date.now()): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const session = JSON.parse(raw) as Partial<Session>
    if (typeof session.token !== 'string' || typeof session.expiresAt !== 'number' || session.expiresAt <= now) {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    return createSession(session.token, session.expiresAt)
  } catch {
    return null
  }
}

export function saveSession(session: Pick<Session, 'token' | 'expiresAt'>): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ token: session.token, expiresAt: session.expiresAt }))
  } catch {
    // Sem storage disponível, a sessão vale só enquanto a página estiver aberta.
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nada a remover.
  }
}
