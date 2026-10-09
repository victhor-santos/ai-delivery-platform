import { useCallback } from 'react'
import { Link } from 'react-router'
import { currentUser } from '../api/auth'
import { useLoad } from '../api/useLoad'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'

export function HomePage() {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const load = useCallback((signal: AbortSignal) => currentUser(token, signal), [token])
  const { data: profile, error } = useLoad(load, 'Não foi possível carregar o perfil.')

  if (error) {
    return <Alert>{error}</Alert>
  }
  if (!profile) {
    return <p aria-busy="true">Carregando…</p>
  }
  return (
    <section className="card">
      <h1>Olá, {profile.name}</h1>
      <p>
        Conectado como <strong>{profile.email}</strong>.
      </p>
      <p>
        {profile.role === 'OPERATOR' ? (
          <Link to="/operations">Acompanhar as entregas</Link>
        ) : (
          <Link to="/restaurants">Escolher um restaurante</Link>
        )}
      </p>
    </section>
  )
}
