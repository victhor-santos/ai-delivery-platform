import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { App } from '../App'
import { saveSession } from '../auth/session'

export function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

export function signIn(token = 'token-123') {
  saveSession({ token, expiresAt: Date.now() + 60_000 })
}

// Só o payload importa para a interface; a assinatura é validada pelos serviços.
export const OPERATOR_TOKEN = `e30.${btoa(JSON.stringify({ roles: ['OPERATOR'] })).replace(/=+$/, '')}.signature`

export function signInAsOperator() {
  signIn(OPERATOR_TOKEN)
}
