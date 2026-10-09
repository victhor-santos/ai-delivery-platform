import { describe, expect, it } from 'vitest'
import { clearSession, loadSession, roleFromToken, saveSession } from './session'

describe('session storage', () => {
  it('restores a valid session', () => {
    saveSession({ token: 't', expiresAt: 2_000 })

    expect(loadSession(1_000)).toEqual({ token: 't', expiresAt: 2_000, role: 'CUSTOMER' })
  })

  it('discards an expired session', () => {
    saveSession({ token: 't', expiresAt: 1_000 })

    expect(loadSession(1_000)).toBeNull()
    expect(sessionStorage.length).toBe(0)
  })

  it('ignores malformed data', () => {
    sessionStorage.setItem('delivery.session', '{"token":1}')
    expect(loadSession()).toBeNull()

    sessionStorage.setItem('delivery.session', 'not json')
    expect(loadSession()).toBeNull()
  })

  it('clears the session', () => {
    saveSession({ token: 't', expiresAt: Date.now() + 60_000 })

    clearSession()

    expect(loadSession()).toBeNull()
  })

  it('reads the role from the token payload and stores only the token', () => {
    const payload = (claims: object) => btoa(JSON.stringify(claims)).replace(/=+$/, '').replace(/\+/g, '-')
    const operator = `e30.${payload({ sub: 'u1', roles: ['OPERATOR'] })}.sig`

    expect(roleFromToken(operator)).toBe('OPERATOR')
    expect(roleFromToken(`e30.${payload({ roles: ['CUSTOMER'] })}.sig`)).toBe('CUSTOMER')
    expect(roleFromToken(`e30.${payload({ roles: 'OPERATOR' })}.sig`)).toBe('CUSTOMER')
    expect(roleFromToken('e30.not-json.sig')).toBe('CUSTOMER')
    expect(roleFromToken('opaque')).toBe('CUSTOMER')

    saveSession({ token: operator, expiresAt: 2_000 })
    expect(JSON.parse(sessionStorage.getItem('delivery.session')!)).toEqual({ token: operator, expiresAt: 2_000 })
    expect(loadSession(1_000)?.role).toBe('OPERATOR')
  })
})
