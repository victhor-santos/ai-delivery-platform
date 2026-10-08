import { describe, expect, it, vi } from 'vitest'
import { json, mockFetch, problem } from '../test/http'
import { ApiError, NETWORK_ERROR_STATUS, request } from './client'

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.catch((e: unknown) => e)
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('request', () => {
  it('sends JSON body and Bearer token', async () => {
    const fetchMock = mockFetch({ 'POST /api/x': () => json(201, { ok: true }) })

    await expect(request('/api/x', { method: 'POST', body: { a: 1 }, token: 'abc' })).resolves.toEqual({ ok: true })

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.body).toBe('{"a":1}')
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json', Authorization: 'Bearer abc' })
  })

  it('omits Authorization without token', async () => {
    const fetchMock = mockFetch({ 'GET /api/x': () => json(200, {}) })

    await request('/api/x')

    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).not.toHaveProperty('Authorization')
  })

  it('returns undefined for 204', async () => {
    mockFetch({ 'DELETE /api/x': () => new Response(null, { status: 204 }) })

    await expect(request('/api/x', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('uses the problem detail for client errors', async () => {
    mockFetch({ 'POST /api/x': () => problem(409, 'E-mail já cadastrado.') })

    const error = await failure(request('/api/x', { method: 'POST', body: {} }))

    expect(error.status).toBe(409)
    expect(error.message).toBe('E-mail já cadastrado.')
  })

  it('falls back when the client error has no usable detail', async () => {
    mockFetch({ 'GET /api/x': () => new Response('<html>', { status: 404, headers: { 'Content-Type': 'text/html' } }) })

    expect((await failure(request('/api/x'))).message).toBe('Recurso não encontrado.')
  })

  it('hides server error details', async () => {
    mockFetch({ 'GET /api/x': () => problem(500, 'stack trace') })

    const error = await failure(request('/api/x'))

    expect(error.status).toBe(500)
    expect(error.message).toBe('Erro inesperado no servidor. Tente novamente.')
  })

  it('reports network failures with status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await failure(request('/api/x'))

    expect(error.status).toBe(NETWORK_ERROR_STATUS)
    expect(error.message).toMatch(/conectar ao servidor/)
  })

  it('propagates aborts unchanged', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError')))

    await expect(request('/api/x')).rejects.toMatchObject({ name: 'AbortError' })
  })
})
