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
    return (
      <p className="loading" aria-busy="true">
        Carregando…
      </p>
    )
  }
  const operator = profile.role === 'OPERATOR'
  return (
    <section className="card welcome">
      <h1>Olá, {profile.name}</h1>
      <p>
        Conectado como <strong>{profile.email}</strong>.
      </p>
      <p className="muted">
        {operator
          ? 'Acompanhe as entregas, atribua entregadores e calcule as rotas.'
          : 'Escolha um restaurante, monte o pedido e acompanhe a entrega até a sua porta.'}
      </p>
      <div className="actions">
        {operator ? (
          <Link className="button-link" to="/operations">
            Acompanhar as entregas
          </Link>
        ) : (
          <Link className="button-link" to="/restaurants">
            Escolher um restaurante
          </Link>
        )}
      </div>
    </section>
  )
}
