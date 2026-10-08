import { describe, expect, it } from 'vitest'
import { isInSyntheticCity, SYNTHETIC_POINTS } from './syntheticCity'

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
