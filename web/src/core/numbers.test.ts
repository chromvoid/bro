import { describe, expect, it } from 'vitest'
import { formatAmount, formatBytes, parseAmount, UNKNOWN } from './numbers'

describe('numbers', () => {
  it('formats decimal strings the Russian way and never turns unknown into zero', () => {
    expect(formatAmount('1842.40')).toBe('1 842,4')
    expect(formatAmount('0')).toBe('0')
    expect(formatAmount(null)).toBe(UNKNOWN)
    expect(formatAmount(undefined)).toBe(UNKNOWN)
    expect(formatAmount('n/a')).toBe(UNKNOWN)
    expect(formatAmount('12.345', 2)).toBe('12,35')
  })

  it('reads user input with either decimal separator', () => {
    expect(parseAmount('150')).toBe('150')
    expect(parseAmount(' 12,5 ')).toBe('12.5')
    expect(parseAmount('007')).toBe('7')
    expect(parseAmount('-3')).toBeNull()
    expect(parseAmount('1e3')).toBeNull()
    expect(parseAmount('')).toBeNull()
  })
})

describe('formatBytes', () => {
  it('counts in steps of 1024 under the everyday names', () => {
    expect(formatBytes(0)).toBe('0 Б')
    expect(formatBytes(1600)).toBe('1,6 КБ')
    expect(formatBytes(613.8 * 1024)).toBe('613,8 КБ')
    expect(formatBytes(32 * 1024 ** 3)).toBe('32 ГБ')
    expect(formatBytes(5 * 1024 ** 4)).toBe('5\u00a0120 ГБ')
  })
})
