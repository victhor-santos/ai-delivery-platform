import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { json, mockFetch, problem } from '../test/http'
import { renderAt, signIn } from '../test/render'

const RESTAURANT = { id: 'r1', name: 'Cantina da Praça', active: true, pickupLocation: null }
const MENU = [
  {
    id: 'i1',
    restaurantId: 'r1',
    name: 'Prato do dia',
    description: 'Arroz e feijão',
    price: 25.9,
    currency: 'BRL',
    available: true,
  },
  { id: 'i2', restaurantId: 'r1', name: 'Sobremesa', description: null, price: 8, currency: 'BRL', available: false },
]

function menuPage(content: unknown[]) {
  return { content, page: 0, size: 100, totalElements: content.length, totalPages: content.length ? 1 : 0 }
}

describe('RestaurantPage', () => {
  it('shows the menu with prices and unavailable items', async () => {
    signIn()
    mockFetch({
      'GET /api/catalog/restaurants/r1': () => json(200, RESTAURANT),
      'GET /api/catalog/restaurants/r1/menu-items?page=0&size=100': () => json(200, menuPage(MENU)),
    })

    renderAt('/restaurants/r1')

    expect(await screen.findByRole('heading', { name: 'Cantina da Praça' })).toBeInTheDocument()
    const [dish, dessert] = within(screen.getByRole('list', { name: 'Cardápio' })).getAllByRole('listitem')
    expect(dish).toHaveTextContent('Arroz e feijão')
    expect(dish).toHaveTextContent(/R\$\s25,90/)
    expect(within(dessert).getByText('Indisponível')).toBeInTheDocument()
  })

  it('warns that a closed restaurant does not take orders', async () => {
    signIn()
    mockFetch({
      'GET /api/catalog/restaurants/r1': () => json(200, { ...RESTAURANT, active: false }),
      'GET /api/catalog/restaurants/r1/menu-items?page=0&size=100': () => json(200, menuPage([])),
    })

    renderAt('/restaurants/r1')

    expect(await screen.findByRole('status')).toHaveTextContent('fechado')
    expect(screen.getByText('Nenhum item no cardápio.')).toBeInTheDocument()
  })

  it('reports an unknown restaurant', async () => {
    signIn()
    mockFetch({
      'GET /api/catalog/restaurants/r1': () => problem(404, 'Restaurante não encontrado.'),
      'GET /api/catalog/restaurants/r1/menu-items?page=0&size=100': () => problem(404, 'Restaurante não encontrado.'),
    })

    renderAt('/restaurants/r1')

    expect(await screen.findByRole('alert')).toHaveTextContent('Restaurante não encontrado.')
    expect(screen.getByRole('link', { name: 'Voltar aos restaurantes' })).toBeInTheDocument()
  })
})
