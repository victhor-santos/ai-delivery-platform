import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { register } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'

// A política de senha completa (inclusive o limite de 72 bytes) é validada pelo User Service.
const MIN_PASSWORD_LENGTH = 12

export function RegisterPage() {
  const { session, signIn } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  if (session && !submitting) {
    return <Navigate to="/" replace />
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    if ([...password].length < MIN_PASSWORD_LENGTH) {
      setError(`A senha precisa ter pelo menos ${MIN_PASSWORD_LENGTH} caracteres.`)
      return
    }
    setSubmitting(true)
    try {
      await register(name, email, password)
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Não foi possível criar a conta.')
      setSubmitting(false)
      return
    }
    try {
      await signIn(email, password)
      navigate('/', { replace: true })
    } catch {
      // A conta existe; só o login automático falhou.
      navigate('/login', { replace: true })
    }
  }

  return (
    <section className="card narrow">
      <h1>Criar conta</h1>
      {error && <Alert>{error}</Alert>}
      <form onSubmit={handleSubmit}>
        <label>
          Nome
          <input autoComplete="name" required value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          E-mail
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Senha
          <input
            type="password"
            autoComplete="new-password"
            required
            aria-describedby="password-hint"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <small id="password-hint">Pelo menos {MIN_PASSWORD_LENGTH} caracteres.</small>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Criando…' : 'Criar conta'}
        </button>
      </form>
      <p>
        Já tem conta? <Link to="/login">Entrar</Link>
      </p>
    </section>
  )
}
