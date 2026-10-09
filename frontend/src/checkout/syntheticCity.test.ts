import { describe, expect, it } from 'vitest'
import { describeRoute, isInSyntheticCity, SYNTHETIC_POINTS, SYNTHETIC_ROADS, syntheticPointAt } from './syntheticCity'

describe('isInSyntheticCity', () => {
  it('accepts every node and points within one meter of it', () => {
    for (const point of SYNTHETIC_POINTS) {
      expect(isInSyntheticCity(point.latitude, point.longitude)).toBe(true)
    }
    expect(isInSyntheticCity(-23.561005, -46.656)).toBe(true)
  })

  it('rejects points farther than one meter from every node', () => {
    expect(isInSyntheticCity(-23.562, -46.657)).toBe(false)
    expect(isInSyntheticCity(-23.56102, -46.656)).toBe(false)
  })
})

describe('syntheticPointAt', () => {
  it('finds the node under a coordinate', () => {
    expect(syntheticPointAt(-23.561005, -46.656)?.id).toBe('C')
    expect(syntheticPointAt(-23.562, -46.657)).toBeNull()
  })

  it('describes a route by its nodes', () => {
    const [a, b, c] = SYNTHETIC_POINTS
    expect(describeRoute([a, b, c])).toBe('A → B → C')
    expect(describeRoute([a, { latitude: 0, longitude: 0 }])).toBe('A → ?')
  })

  it('draws roads only between known nodes', () => {
    const ids = new Set(SYNTHETIC_POINTS.map((point) => point.id))
    for (const [from, to] of SYNTHETIC_ROADS) {
      expect(ids.has(from) && ids.has(to)).toBe(true)
    }
  })
})
