import { request } from './client'

export type UserProfile = {
  id: string
  name: string
  email: string
}

export type AccessToken = {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
}

export function register(name: string, email: string, password: string): Promise<UserProfile> {
  return request('/api/users/auth/register', { method: 'POST', body: { name, email, password } })
}

export function login(email: string, password: string): Promise<AccessToken> {
  return request('/api/users/auth/login', { method: 'POST', body: { email, password } })
}

export function currentUser(token: string, signal?: AbortSignal): Promise<UserProfile> {
  return request('/api/users/auth/me', { token, signal })
}
