import { describe, expect, it } from 'vitest'
import { formatAmount, formatCents, toCents } from './money'

describe('money', () => {
  it('converts amounts to whole cents without floating point drift', () => {
    expect(toCents(25.9)).toBe(2590)
    expect(toCents(0.1 + 0.2)).toBe(30)
    expect(toCents(99999999.99)).toBe(9999999999)
  })

  it('formats cents as Brazilian reais', () => {
    expect(formatCents(5180)).toMatch(/^R\$\s51,80$/)
    expect(formatCents(123456)).toMatch(/^R\$\s1\.234,56$/)
    expect(formatAmount(25.9)).toMatch(/^R\$\s25,90$/)
  })
})
