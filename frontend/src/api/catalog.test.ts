import { describe, expect, it } from 'vitest'
import { json, mockFetch } from '../test/http'
import { listMenuItems } from './catalog'

const item = (n: number) => ({ id: `item-${n}`, name: `Item ${n}` })

describe('listMenuItems', () => {
  it('reads every page of the menu', async () => {
    const fetchMock = mockFetch({
      'GET /api/catalog/restaurants/r1/menu-items?page=0&size=100': () =>
        json(200, { content: [item(1)], page: 0, size: 100, totalElements: 2, totalPages: 2 }),
      'GET /api/catalog/restaurants/r1/menu-items?page=1&size=100': () =>
        json(200, { content: [item(2)], page: 1, size: 100, totalElements: 2, totalPages: 2 }),
    })

    const items = await listMenuItems('r1')

    expect(items.map((i) => i.id)).toEqual(['item-1', 'item-2'])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('returns an empty menu after a single request', async () => {
    mockFetch({
      'GET /api/catalog/restaurants/r1/menu-items?page=0&size=100': () =>
        json(200, { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
    })

    await expect(listMenuItems('r1')).resolves.toEqual([])
  })
})
