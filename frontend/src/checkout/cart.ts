import type { MenuItem } from '../api/catalog'
import { toCents } from './money'

// Limites do Order Service: até 50 itens distintos, cada um com quantidade inteira de 1 a 99.
export const MAX_DISTINCT_ITEMS = 50
export const MAX_QUANTITY = 99

// Quantidade por item do cardápio, na ordem em que os itens foram escolhidos.
export type Cart = ReadonlyMap<string, number>

export type CartLine = {
  item: MenuItem
  quantity: number
  lineCents: number
}

export function setQuantity(cart: Cart, itemId: string, quantity: number): Cart {
  const next = new Map(cart)
  const clamped = Math.min(Math.max(Math.trunc(quantity), 0), MAX_QUANTITY)
  if (clamped === 0) {
    next.delete(itemId)
  } else if (next.has(itemId) || next.size < MAX_DISTINCT_ITEMS) {
    next.set(itemId, clamped)
  }
  return next
}

// Linhas do resumo; itens que saíram do cardápio ou ficaram indisponíveis são descartados.
export function cartLines(cart: Cart, menu: readonly MenuItem[]): CartLine[] {
  const byId = new Map(menu.map((item) => [item.id, item]))
  const lines: CartLine[] = []
  for (const [itemId, quantity] of cart) {
    const item = byId.get(itemId)
    if (item?.available) {
      lines.push({ item, quantity, lineCents: toCents(item.price) * quantity })
    }
  }
  return lines
}

export function totalCents(lines: readonly CartLine[]): number {
  return lines.reduce((sum, line) => sum + line.lineCents, 0)
}
