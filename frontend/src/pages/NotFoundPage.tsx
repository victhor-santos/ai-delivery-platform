import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <section className="card narrow welcome">
      <h1>Página não encontrada</h1>
      <p className="muted">O endereço não existe ou mudou.</p>
      <p>
        <Link to="/">Voltar ao início</Link>
      </p>
    </section>
  )
}
