import { useCallback, useState, type FormEvent } from 'react'
import { currentUser } from '../api/auth'
import type { Coordinates } from '../api/catalog'
import type { Destination } from '../api/orders'
import { useFailureMessage, useLoad } from '../api/useLoad'
import { createAddress, listAddresses, type Address } from '../api/users'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { isInSyntheticCity, SYNTHETIC_POINTS, syntheticPointAt } from './syntheticCity'

const ADDRESS_PREFIX = 'address:'
const POINT_PREFIX = 'point:'

function resolve(key: string, addresses: readonly Address[]): Destination | null {
  if (key.startsWith(ADDRESS_PREFIX)) {
    const saved = addresses.find((address) => address.id === key.slice(ADDRESS_PREFIX.length))
    return saved ? { address: saved.address, latitude: saved.latitude, longitude: saved.longitude } : null
  }
  const point = SYNTHETIC_POINTS.find((candidate) => POINT_PREFIX + candidate.id === key)
  return point ? { address: point.label, latitude: point.latitude, longitude: point.longitude } : null
}

// Destino do pedido: um endereço salvo no perfil ou um ponto da cidade sintética.
type Props = {
  pickup: Coordinates | null
  onChange: (destination: Destination | null) => void
}

export function DestinationPicker({ pickup, onChange }: Props) {
  const { session } = useAuth()
  const token = session?.token ?? ''
  const load = useCallback(
    async (signal: AbortSignal) => {
      const profile = await currentUser(token, signal)
      return { userId: profile.id, addresses: await listAddresses(token, profile.id, signal) }
    },
    [token],
  )
  const { data, error, setData } = useLoad(load, 'Não foi possível carregar seus endereços.')
  const [selected, setSelected] = useState('')
  const [adding, setAdding] = useState(false)
  const addresses = data?.addresses ?? []
  const destination = resolve(selected, addresses)
  const pickupPoint = pickup ? syntheticPointAt(pickup.latitude, pickup.longitude) : null
  const points = SYNTHETIC_POINTS.filter((point) => point !== pickupPoint)

  function select(key: string, from: readonly Address[] = addresses) {
    setSelected(key)
    onChange(resolve(key, from))
  }

  function handleSaved(address: Address) {
    if (!data) {
      return
    }
    const next = [...data.addresses, address]
    setData({ ...data, addresses: next })
    setAdding(false)
    select(ADDRESS_PREFIX + address.id, next)
  }

  return (
    <div className="destination">
      <label>
        Destino
        <select value={selected} onChange={(event) => select(event.target.value)}>
          <option value="">Selecione um destino</option>
          {addresses.length > 0 && (
            <optgroup label="Meus endereços">
              {addresses.map((address) => (
                <option key={address.id} value={ADDRESS_PREFIX + address.id}>
                  {address.label}: {address.address}
                </option>
              ))}
            </optgroup>
          )}
          <optgroup label="Cidade sintética">
            {points.map((point) => (
              <option key={point.id} value={POINT_PREFIX + point.id}>
                {point.label}
              </option>
            ))}
          </optgroup>
        </select>
      </label>
      {pickupPoint && <small>O restaurante fica no ponto {pickupPoint.id}, por isso ele não aparece como destino.</small>}
      {error && <Alert>{error}</Alert>}
      {destination && !isInSyntheticCity(destination.latitude, destination.longitude) && (
        <Alert kind="info">
          Este endereço fica fora da cidade sintética: o pedido pode ser feito, mas a rota da entrega não poderá ser
          planejada.
        </Alert>
      )}
      {data &&
        (adding ? (
          <NewAddressForm userId={data.userId} onSaved={handleSaved} onCancel={() => setAdding(false)} />
        ) : (
          <button type="button" className="link-button accent" onClick={() => setAdding(true)}>
            Cadastrar novo endereço
          </button>
        ))}
    </div>
  )
}

function coordinate(value: string, limit: number): number | null {
  const parsed = Number(value)
  return value.trim() !== '' && Number.isFinite(parsed) && Math.abs(parsed) <= limit ? parsed : null
}

function NewAddressForm({
  userId,
  onSaved,
  onCancel,
}: {
  userId: string
  onSaved: (address: Address) => void
  onCancel: () => void
}) {
  const { session } = useAuth()
  const failureMessage = useFailureMessage()
  const [label, setLabel] = useState('')
  const [address, setAddress] = useState('')
  const [latitude, setLatitude] = useState('')
  const [longitude, setLongitude] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const lat = coordinate(latitude, 90)
    const lon = coordinate(longitude, 180)
    if (lat === null || lon === null) {
      setError('Informe latitude entre -90 e 90 e longitude entre -180 e 180.')
      return
    }
    setError(null)
    setSaving(true)
    try {
      onSaved(await createAddress(session?.token ?? '', userId, { label, address, latitude: lat, longitude: lon }))
    } catch (failure) {
      setError(failureMessage(failure, 'Não foi possível salvar o endereço.'))
      setSaving(false)
    }
  }

  return (
    <form className="card nested" onSubmit={handleSubmit} aria-label="Novo endereço">
      {error && <Alert>{error}</Alert>}
      <label>
        Rótulo
        <input required maxLength={80} value={label} onChange={(e) => setLabel(e.target.value)} />
      </label>
      <label>
        Endereço
        <input required maxLength={255} value={address} onChange={(e) => setAddress(e.target.value)} />
      </label>
      <div className="row">
        <label>
          Latitude
          <input inputMode="decimal" required value={latitude} onChange={(e) => setLatitude(e.target.value)} />
        </label>
        <label>
          Longitude
          <input inputMode="decimal" required value={longitude} onChange={(e) => setLongitude(e.target.value)} />
        </label>
      </div>
      <small>Não há geocodificação: as coordenadas são informadas diretamente.</small>
      <div className="row">
        <button type="submit" disabled={saving}>
          {saving ? 'Salvando…' : 'Salvar endereço'}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </form>
  )
}
