import { describe, expect, it } from 'vitest'
import type { MenuItem } from '../api/catalog'
import { cartLines, MAX_DISTINCT_ITEMS, setQuantity, totalCents, type Cart } from './cart'

function menuItem(id: string, price: number, available = true): MenuItem {
  return { id, restaurantId: 'r1', name: id, description: null, price, currency: 'BRL', available }
}

describe('cart', () => {
  it('adds, changes and removes quantities within the order limits', () => {
    let cart: Cart = new Map()
    cart = setQuantity(cart, 'a', 2)
    cart = setQuantity(cart, 'b', 150)
    expect([...cart]).toEqual([
      ['a', 2],
      ['b', 99],
    ])

    cart = setQuantity(cart, 'a', 0)
    expect([...cart]).toEqual([['b', 99]])
  })

  it('refuses more distinct items than an order accepts', () => {
    let cart: Cart = new Map()
    for (let i = 0; i < MAX_DISTINCT_ITEMS; i++) {
      cart = setQuantity(cart, `item-${i}`, 1)
    }

    const full = setQuantity(cart, 'extra', 1)

    expect(full.size).toBe(MAX_DISTINCT_ITEMS)
    expect(setQuantity(full, 'item-0', 3).get('item-0')).toBe(3)
  })

  it('sums line totals in cents and skips unavailable items', () => {
    const menu = [menuItem('a', 25.9), menuItem('b', 0.1), menuItem('c', 8, false)]
    const cart: Cart = new Map([
      ['b', 3],
      ['a', 2],
      ['c', 1],
      ['gone', 1],
    ])

    const lines = cartLines(cart, menu)

    expect(lines.map((line) => [line.item.id, line.lineCents])).toEqual([
      ['b', 30],
      ['a', 5180],
    ])
    expect(totalCents(lines)).toBe(5210)
  })
})
