import { request } from './client'

// O catálogo é público; as consultas não enviam token.

export type Page<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type Coordinates = {
  latitude: number
  longitude: number
}

export type Restaurant = {
  id: string
  name: string
  active: boolean
  pickupLocation: Coordinates | null
}

export type MenuItem = {
  id: string
  restaurantId: string
  name: string
  description: string | null
  price: number
  currency: 'BRL'
  available: boolean
}

export const RESTAURANT_PAGE_SIZE = 20
const MENU_PAGE_SIZE = 100

export function listRestaurants(page: number, signal?: AbortSignal): Promise<Page<Restaurant>> {
  return request(`/api/catalog/restaurants?page=${page}&size=${RESTAURANT_PAGE_SIZE}`, { signal })
}

export function getRestaurant(id: string, signal?: AbortSignal): Promise<Restaurant> {
  return request(`/api/catalog/restaurants/${encodeURIComponent(id)}`, { signal })
}

// O cardápio é exibido inteiro; páginas de 100 (o máximo do catálogo) são lidas até a última.
export async function listMenuItems(restaurantId: string, signal?: AbortSignal): Promise<MenuItem[]> {
  const path = `/api/catalog/restaurants/${encodeURIComponent(restaurantId)}/menu-items`
  const items: MenuItem[] = []
  for (let page = 0; ; page++) {
    const result = await request<Page<MenuItem>>(`${path}?page=${page}&size=${MENU_PAGE_SIZE}`, { signal })
    items.push(...result.content)
    if (page + 1 >= result.totalPages) {
      return items
    }
  }
}
