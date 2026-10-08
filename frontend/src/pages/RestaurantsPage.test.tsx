import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { json, mockFetch, problem } from '../test/http'
import { renderAt, signIn } from '../test/render'

const OPEN = { id: 'r-open', name: 'Cantina da Praça', active: true, pickupLocation: null }
const CLOSED = { id: 'r-closed', name: 'Padaria Fechada', active: false, pickupLocation: null }

function page(content: unknown[], page = 0, totalPages = 1) {
  return { content, page, size: 20, totalElements: content.length, totalPages }
}

describe('RestaurantsPage', () => {
  it('lists restaurants and marks the closed ones', async () => {
    signIn()
    mockFetch({ 'GET /api/catalog/restaurants?page=0&size=20': () => json(200, page([OPEN, CLOSED])) })

    renderAt('/restaurants')

    const items = await screen.findAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByRole('link', { name: 'Cantina da Praça' })).toHaveAttribute(
      'href',
      '/restaurants/r-open',
    )
    expect(within(items[0]).queryByText('Fechado')).not.toBeInTheDocument()
    expect(within(items[1]).getByText('Fechado')).toBeInTheDocument()
  })

  it('moves between pages', async () => {
    signIn()
    mockFetch({
      'GET /api/catalog/restaurants?page=0&size=20': () => json(200, page([OPEN], 0, 2)),
      'GET /api/catalog/restaurants?page=1&size=20': () => json(200, page([CLOSED], 1, 2)),
    })
    const user = userEvent.setup()
    renderAt('/restaurants')
    await screen.findByText('Cantina da Praça')
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    expect(await screen.findByText('Padaria Fechada')).toBeInTheDocument()
    expect(screen.getByText('Página 2 de 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()
  })

  it('shows an empty catalog', async () => {
    signIn()
    mockFetch({ 'GET /api/catalog/restaurants?page=0&size=20': () => json(200, page([], 0, 0)) })

    renderAt('/restaurants')

    expect(await screen.findByText('Nenhum restaurante cadastrado.')).toBeInTheDocument()
  })

  it('hides server error details', async () => {
    signIn()
    mockFetch({ 'GET /api/catalog/restaurants?page=0&size=20': () => problem(500, 'stack trace') })

    renderAt('/restaurants')

    expect(await screen.findByRole('alert')).toHaveTextContent('Erro inesperado no servidor')
  })
})
