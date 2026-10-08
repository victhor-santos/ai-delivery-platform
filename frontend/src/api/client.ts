// Cliente HTTP do Gateway. Todos os serviços respondem erros em application/problem+json.

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export const NETWORK_ERROR_STATUS = 0

const FALLBACK_MESSAGES: Record<number, string> = {
  [NETWORK_ERROR_STATUS]: 'Não foi possível conectar ao servidor. Tente novamente.',
  400: 'Dados inválidos.',
  401: 'Autenticação necessária.',
  404: 'Recurso não encontrado.',
  409: 'A operação conflita com o estado atual.',
}

const GENERIC_MESSAGE = 'Erro inesperado no servidor. Tente novamente.'

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  token?: string
  headers?: Record<string, string>
  signal?: AbortSignal
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (options.token) {
    headers.Authorization = `Bearer ${options.token}`
  }

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(NETWORK_ERROR_STATUS, FALLBACK_MESSAGES[NETWORK_ERROR_STATUS])
  }

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

// O detail do servidor só é exibido para erros do cliente; 5xx e respostas do proxy usam mensagens próprias.
async function problemMessage(response: Response): Promise<string> {
  const fallback = FALLBACK_MESSAGES[response.status] ?? GENERIC_MESSAGE
  if (response.status >= 500 || !response.headers.get('Content-Type')?.includes('json')) {
    return fallback
  }
  try {
    const problem = (await response.json()) as { detail?: unknown }
    return typeof problem.detail === 'string' && problem.detail.trim() ? problem.detail : fallback
  } catch {
    return fallback
  }
}
