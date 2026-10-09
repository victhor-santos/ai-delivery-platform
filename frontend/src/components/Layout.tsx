import { Link, NavLink, Outlet } from 'react-router'
import { useAuth } from '../auth/AuthContext'

export function Layout() {
  const { session, signOut } = useAuth()
  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
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
            </>
          ) : (
            <>
              <NavLink to="/login">Entrar</NavLink>
              <NavLink to="/register">Criar conta</NavLink>
            </>
          )}
        </nav>
      </header>
      <p className="demo-banner">Demonstração: pagamentos, ruas e trânsito são simulados.</p>
      <main className="content">
        <Outlet />
      </main>
    </>
  )
}
