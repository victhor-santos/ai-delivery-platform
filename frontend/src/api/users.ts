import { request } from './client'

export type Address = {
  id: string
  userId: string
  label: string
  address: string
  latitude: number
  longitude: number
}

export type NewAddress = Omit<Address, 'id' | 'userId'>

type AddressPage = {
  items: Address[]
  totalPages: number
}

// O seletor de destino mostra os 100 primeiros endereços (ordenados por rótulo), o máximo de uma página.
export async function listAddresses(token: string, userId: string, signal?: AbortSignal): Promise<Address[]> {
  const page = await request<AddressPage>(`/api/users/${encodeURIComponent(userId)}/addresses?page=0&size=100`, {
    token,
    signal,
  })
  return page.items
}

export function createAddress(token: string, userId: string, address: NewAddress): Promise<Address> {
  return request(`/api/users/${encodeURIComponent(userId)}/addresses`, { method: 'POST', body: address, token })
}
