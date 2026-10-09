import type { DeliveryLocation, RoutePoint } from '../api/deliveries'
import { describeRoute, SYNTHETIC_POINTS, SYNTHETIC_ROADS, syntheticPointAt } from '../checkout/syntheticCity'

// Projeção equiretangular dos nós para um viewBox em metros aproximados, com margem para os rótulos.
const METERS_PER_DEGREE = 111_320
const PADDING = 260
const latitudes = SYNTHETIC_POINTS.map((point) => point.latitude)
const longitudes = SYNTHETIC_POINTS.map((point) => point.longitude)
const NORTH = Math.max(...latitudes)
const WEST = Math.min(...longitudes)
const LONGITUDE_SCALE = Math.cos(((NORTH + Math.min(...latitudes)) / 2) * (Math.PI / 180))
const WIDTH = (Math.max(...longitudes) - WEST) * METERS_PER_DEGREE * LONGITUDE_SCALE + 2 * PADDING
const HEIGHT = (NORTH - Math.min(...latitudes)) * METERS_PER_DEGREE + 2 * PADDING

function project({ latitude, longitude }: RoutePoint): { x: number; y: number } {
  return {
    x: PADDING + (longitude - WEST) * METERS_PER_DEGREE * LONGITUDE_SCALE,
    y: PADDING + (NORTH - latitude) * METERS_PER_DEGREE,
  }
}

const POINTS_BY_ID = new Map(SYNTHETIC_POINTS.map((point) => [point.id, point]))

type Props = {
  origin: DeliveryLocation
  destination: DeliveryLocation
  route: RoutePoint[] | null
}

export function RouteMap({ origin, destination, route }: Props) {
  const start = syntheticPointAt(origin.latitude, origin.longitude)
  const end = syntheticPointAt(destination.latitude, destination.longitude)
  const label = route
    ? `Mapa da cidade sintética com a rota ${describeRoute(route)}`
    : `Mapa da cidade sintética: coleta em ${start?.id ?? '?'}, destino em ${end?.id ?? '?'}, sem rota planejada`

  return (
    <figure className="route-map">
      <svg viewBox={`0 0 ${Math.round(WIDTH)} ${Math.round(HEIGHT)}`} role="img" aria-label={label}>
        {SYNTHETIC_ROADS.map(([from, to]) => {
          const a = project(POINTS_BY_ID.get(from)!)
          const b = project(POINTS_BY_ID.get(to)!)
          return <line key={`${from}-${to}`} className="road" x1={a.x} y1={a.y} x2={b.x} y2={b.y} />
        })}
        {route && route.length > 1 && (
          <polyline
            className="route"
            points={route.map((point) => `${project(point).x},${project(point).y}`).join(' ')}
          />
        )}
        {SYNTHETIC_POINTS.map((point) => {
          const { x, y } = project(point)
          const role =
            point === start && point === end
              ? 'both'
              : point === start
                ? 'origin'
                : point === end
                  ? 'destination'
                  : 'node'
          return (
            <g key={point.id} className={role}>
              <circle cx={x} cy={y} r={role === 'node' ? 70 : 110} />
              <text x={x} y={y}>
                {point.id}
              </text>
            </g>
          )
        })}
      </svg>
      <figcaption>
        <span className="legend origin">{start && start === end ? 'Coleta e destino' : 'Coleta'}</span>
        {!(start && start === end) && <span className="legend destination">Destino</span>}
        <span className="legend route">Rota prevista</span>
      </figcaption>
    </figure>
  )
}
