// Nós do grafo sintético (services/route-intelligence-service/app/routing/data/synthetic-city-v1.json).
// O planejamento de rotas só aceita pontos a até 1 metro de um nó; outros destinos criam o pedido,
// mas a entrega não terá rota. Se o grafo mudar, estas listas precisam acompanhar.

export type SyntheticPoint = {
  id: string
  label: string
  latitude: number
  longitude: number
}

export const SYNTHETIC_POINTS: readonly SyntheticPoint[] = [
  { id: 'A', label: 'Ponto sintético A', latitude: -23.5505, longitude: -46.6333 },
  { id: 'B', label: 'Ponto sintético B', latitude: -23.554, longitude: -46.64 },
  { id: 'C', label: 'Ponto sintético C', latitude: -23.561, longitude: -46.656 },
  { id: 'D', label: 'Ponto sintético D', latitude: -23.5455, longitude: -46.6333 },
  { id: 'E', label: 'Ponto sintético E', latitude: -23.5455, longitude: -46.656 },
  { id: 'F', label: 'Ponto sintético F', latitude: -23.5505, longitude: -46.6263 },
  { id: 'G', label: 'Ponto sintético G', latitude: -23.57, longitude: -46.66 },
]

// Ruas do mapa, sem sentido: o grafo tem trechos de mão única (A-D, D-E e E-C), mas o mapa só desenha as ligações.
export const SYNTHETIC_ROADS: readonly (readonly [string, string])[] = [
  ['A', 'B'],
  ['B', 'C'],
  ['A', 'D'],
  ['D', 'E'],
  ['E', 'C'],
  ['A', 'F'],
]

const EARTH_RADIUS_METERS = 6_371_000
const SNAP_TOLERANCE_METERS = 1

// Aproximação equiretangular: suficiente para distâncias de poucos metros.
function distanceMeters(latA: number, lonA: number, latB: number, lonB: number): number {
  const toRadians = Math.PI / 180
  const x = (lonB - lonA) * toRadians * Math.cos(((latA + latB) / 2) * toRadians)
  const y = (latB - latA) * toRadians
  return Math.hypot(x, y) * EARTH_RADIUS_METERS
}

export function syntheticPointAt(latitude: number, longitude: number): SyntheticPoint | null {
  return (
    SYNTHETIC_POINTS.find(
      (point) => distanceMeters(latitude, longitude, point.latitude, point.longitude) <= SNAP_TOLERANCE_METERS,
    ) ?? null
  )
}

export function isInSyntheticCity(latitude: number, longitude: number): boolean {
  return syntheticPointAt(latitude, longitude) !== null
}

// Descreve um percurso pelos nós, como "A → B → C"; coordenadas fora do grafo aparecem como "?".
export function describeRoute(route: readonly { latitude: number; longitude: number }[]): string {
  return route.map((point) => syntheticPointAt(point.latitude, point.longitude)?.id ?? '?').join(' → ')
}
