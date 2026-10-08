// O token fica no sessionStorage: sobrevive ao recarregar a aba e some ao fechá-la.
// Não há refresh token; a sessão termina na expiração informada pelo login (15 minutos).

export type Session = {
  token: string
  expiresAt: number
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
    return { token: session.token, expiresAt: session.expiresAt }
  } catch {
    return null
  }
}

export function saveSession(session: Session): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
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
