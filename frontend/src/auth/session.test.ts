import { describe, expect, it } from 'vitest'
import { clearSession, loadSession, saveSession } from './session'

describe('session storage', () => {
  it('restores a valid session', () => {
    saveSession({ token: 't', expiresAt: 2_000 })

    expect(loadSession(1_000)).toEqual({ token: 't', expiresAt: 2_000 })
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
})
