import { Link, NavLink, Outlet } from 'react-router'
import { useAuth } from '../auth/AuthContext'

function BrandMark() {
  return (
    <span className="brand-mark" aria-hidden="true">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
        <path d="M5 8h14l-1.2 11.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8z" strokeLinejoin="round" />
        <path d="M9 10V7a3 3 0 0 1 6 0v3" strokeLinecap="round" />
      </svg>
    </span>
  )
}

export function Layout() {
  const { session, signOut } = useAuth()
  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          <BrandMark />
          Delivery
        </Link>
        <nav aria-label="Principal">
          {session ? (
            <>
              <NavLink to="/" end>
                Início
              </NavLink>
              <NavLink to="/restaurants">Restaurantes</NavLink>
              {session.role === 'OPERATOR' && <NavLink to="/operations">Operação</NavLink>}
              <button type="button" className="link-button" onClick={() => signOut()}>
                Sair
              </button>
              {session.role === 'OPERATOR' && <span className="role-chip">Operador</span>}
            </>
          ) : (
            <>
              <NavLink to="/login">Entrar</NavLink>
              <NavLink to="/register">Criar conta</NavLink>
            </>
          )}
        </nav>
      </header>
      <main className="content">
        <Outlet />
      </main>
      <p className="demo-banner">Demonstração: pagamentos, ruas e trânsito são simulados.</p>
    </>
  )
}
