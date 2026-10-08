import { useEffect, useState } from 'react'
import { currentUser, type UserProfile } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'

export function HomePage() {
  const { session, signOut } = useAuth()
  const token = session?.token
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) {
      return
    }
    const controller = new AbortController()
    currentUser(token, controller.signal)
      .then(setProfile)
      .catch((failure: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        if (failure instanceof ApiError && failure.status === 401) {
          signOut('expired')
          return
        }
        setError(failure instanceof ApiError ? failure.message : 'Não foi possível carregar o perfil.')
      })
    return () => controller.abort()
  }, [token, signOut])

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
    </section>
  )
}
