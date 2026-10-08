import { Component, type ReactNode } from 'react'

type State = { failed: boolean }

// Falha de renderização mostra uma mensagem em vez de uma página em branco.
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  render() {
    if (this.state.failed) {
      return (
        <main className="content">
          <h1>Algo deu errado</h1>
          <p>Recarregue a página para tentar novamente.</p>
        </main>
      )
    }
    return this.props.children
  }
}
