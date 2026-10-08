import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { saveSession } from './auth/session'
import { json, mockFetch, problem } from './test/http'

const PROFILE = { id: '7f1c7d2e-0000-4000-8000-000000000001', name: 'Cliente Demo', email: 'demo@example.test' }
const TOKEN = { accessToken: 'token-123', tokenType: 'Bearer', expiresIn: 900 }

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

async function fillLogin(email = PROFILE.email, password = 'senha-de-teste-123') {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('E-mail'), email)
  await user.type(screen.getByLabelText('Senha'), password)
  await user.click(screen.getByRole('button', { name: 'Entrar' }))
}

describe('App', () => {
  it('redirects anonymous visitors to the login page', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('signs in and shows the authenticated profile', async () => {
    const fetchMock = mockFetch({
      'POST /api/users/auth/login': () => json(200, TOKEN),
      'GET /api/users/auth/me': () => json(200, PROFILE),
    })
    renderAt('/')

    await fillLogin()

    expect(await screen.findByRole('heading', { name: 'Olá, Cliente Demo' })).toBeInTheDocument()
    const meCall = fetchMock.mock.calls.find(([url]) => url === '/api/users/auth/me')!
    expect((meCall[1] as RequestInit).headers).toMatchObject({ Authorization: 'Bearer token-123' })
  })

  it('shows the server message when the login is refused', async () => {
    mockFetch({ 'POST /api/users/auth/login': () => problem(401, 'E-mail ou senha inválidos.') })
    renderAt('/login')

    await fillLogin()

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha inválidos.')
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled()
  })

  it('registers and signs in the new account', async () => {
    const fetchMock = mockFetch({
      'POST /api/users/auth/register': () => json(201, PROFILE),
      'POST /api/users/auth/login': () => json(200, TOKEN),
      'GET /api/users/auth/me': () => json(200, PROFILE),
    })
    const user = userEvent.setup()
    renderAt('/register')

    await user.type(screen.getByLabelText('Nome'), PROFILE.name)
    await user.type(screen.getByLabelText('E-mail'), PROFILE.email)
    await user.type(screen.getByLabelText('Senha'), 'senha-de-teste-123')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Cliente Demo' })).toBeInTheDocument()
    expect(JSON.parse(fetchMock.mock.calls[0][1]!.body as string)).toEqual({
      name: PROFILE.name,
      email: PROFILE.email,
      password: 'senha-de-teste-123',
    })
  })

  it('rejects a short password before calling the server', async () => {
    const fetchMock = mockFetch({})
    const user = userEvent.setup()
    renderAt('/register')

    await user.type(screen.getByLabelText('Nome'), PROFILE.name)
    await user.type(screen.getByLabelText('E-mail'), PROFILE.email)
    await user.type(screen.getByLabelText('Senha'), 'curta')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(screen.getByRole('alert')).toHaveTextContent('pelo menos 12 caracteres')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows a conflict when the e-mail is taken', async () => {
    mockFetch({ 'POST /api/users/auth/register': () => problem(409, 'E-mail já cadastrado.') })
    const user = userEvent.setup()
    renderAt('/register')

    await user.type(screen.getByLabelText('Nome'), PROFILE.name)
    await user.type(screen.getByLabelText('E-mail'), PROFILE.email)
    await user.type(screen.getByLabelText('Senha'), 'senha-de-teste-123')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail já cadastrado.')
  })

  it('restores a stored session after reload', async () => {
    saveSession({ token: 'stored', expiresAt: Date.now() + 60_000 })
    mockFetch({ 'GET /api/users/auth/me': () => json(200, PROFILE) })

    renderAt('/')

    expect(await screen.findByRole('heading', { name: 'Olá, Cliente Demo' })).toBeInTheDocument()
  })

  it('returns to login when the server rejects the token', async () => {
    saveSession({ token: 'revoked', expiresAt: Date.now() + 60_000 })
    mockFetch({ 'GET /api/users/auth/me': () => problem(401, 'Token inválido.') })

    renderAt('/')

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Sua sessão expirou')
  })

  it('signs out when the token expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      saveSession({ token: 'short', expiresAt: Date.now() + 5_000 })
      mockFetch({ 'GET /api/users/auth/me': () => json(200, PROFILE) })
      renderAt('/')
      expect(await screen.findByRole('heading', { name: 'Olá, Cliente Demo' })).toBeInTheDocument()

      await act(() => vi.advanceTimersByTimeAsync(5_000))

      expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
      expect(sessionStorage.length).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('signs out on request', async () => {
    saveSession({ token: 'stored', expiresAt: Date.now() + 60_000 })
    mockFetch({ 'GET /api/users/auth/me': () => json(200, PROFILE) })
    const user = userEvent.setup()
    renderAt('/')
    await screen.findByRole('heading', { name: 'Olá, Cliente Demo' })

    await user.click(screen.getByRole('button', { name: 'Sair' }))

    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(sessionStorage.length).toBe(0)
  })

  it('shows a not-found page for unknown paths', () => {
    renderAt('/nada')

    expect(screen.getByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument()
  })
})
