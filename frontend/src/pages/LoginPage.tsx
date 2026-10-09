import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'

export function LoginPage() {
  const { session, notice, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const from = (location.state as { from?: string } | null)?.from ?? '/'

  if (session && !submitting) {
    return <Navigate to={from} replace />
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await signIn(email, password)
      navigate(from, { replace: true })
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Não foi possível entrar.')
      setSubmitting(false)
    }
  }

  return (
    <section className="card narrow auth-card">
      <h1>Entrar</h1>
      <p>Acesse para fazer pedidos e acompanhar entregas.</p>
      {notice && <Alert kind="info">{notice}</Alert>}
      {error && <Alert>{error}</Alert>}
      <form onSubmit={handleSubmit}>
        <label>
          E-mail
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Senha
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
      <p>
        Não tem conta? <Link to="/register">Criar conta</Link>
      </p>
    </section>
  )
}
