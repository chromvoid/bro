import { describe, expect, it } from 'vitest'
import { Freshness } from './contracts'
import {
  MISSING,
  amount,
  dateTime,
  isStale,
  month,
  percent,
  price,
  quote,
  roundDecimal,
  usd,
  usdShort,
} from './format'

const NB = ' '

describe('trading display', () => {
  it('rounds decimals as text, half away from zero', () => {
    expect(roundDecimal('1.36666667', 2)).toBe('1.37')
    expect(roundDecimal('82.86748', 2)).toBe('82.87')
    expect(roundDecimal('1520.5', 2)).toBe('1520.5')
    expect(roundDecimal('9.999', 2)).toBe('10')
    expect(roundDecimal('-0.004', 2)).toBe('0')
    expect(roundDecimal('-0.005', 2)).toBe('-0.01')
    expect(roundDecimal('0.10000000000000000001', 2)).toBe('0.1')
    expect(roundDecimal('123456789012345678901234567890.125', 2)).toBe(
      '123456789012345678901234567890.13',
    )
    expect(roundDecimal('2.5', 0)).toBe('3')
    for (const bad of ['1e5', 'NaN', '']) expect(roundDecimal(bad, 2)).toBeNull()
  })

  it('groups and signs amounts', () => {
    expect(usd('1520.5')).toBe(`1${NB}520,5${NB}$`)
    expect(usd('20.5', true)).toBe(`+20,5${NB}$`)
    expect(usd('-1000000')).toBe(`-1${NB}000${NB}000${NB}$`)
    expect(usd(null)).toBe(MISSING)
    expect(percent('1.36666667')).toBe(`+1,37${NB}%`)
    expect(percent('82.86748', false)).toBe(`82,87${NB}%`)
    expect(percent('0')).toBe(`0${NB}%`)
    expect(quote('-12.345', 'USDT', true)).toBe(`-12,35${NB}USDT`)
    expect(amount('0.000005')).toBe('0,000005')
    expect(amount('62000')).toBe(`62${NB}000`)
    expect(amount('not a number')).toBe(MISSING)
  })

  it('shows times in the account zone', () => {
    expect(dateTime('2026-09-25T10:00:00Z', 'UTC')).toBe('25.09.2026 10:00')
    expect(dateTime('2026-09-25T10:00:00Z', 'Europe/Moscow')).toBe('25.09.2026 13:00')
    expect(dateTime(null, 'UTC')).toBe(MISSING)
    expect(dateTime('yesterday', 'UTC')).toBe(MISSING)
  })

  it('marks data stale by connection and snapshot age', () => {
    const now = Date.parse('2026-09-25T10:05:00Z')
    const fresh = Freshness.parse({
      as_of: '2026-09-25T10:00:00Z',
      connection_state: 'active',
      stream: 'live',
    })
    expect(isStale(fresh, now)).toBe(false)
    expect(isStale(fresh, Date.parse('2026-09-25T10:11:00Z'))).toBe(true)
    expect(isStale({ ...fresh, connection_state: 'ip_blocked' }, now)).toBe(true)
    expect(isStale({ ...fresh, connection_state: 'something_new' }, now)).toBe(true)
    expect(isStale({ ...fresh, as_of: null }, now)).toBe(true)
  })
})

describe('months', () => {
  it('names a month short for an axis and long for a tooltip', () => {
    expect(month('2025-09')).toBe('сент')
    expect(month('2025-09', true)).toBe('сентябрь 2025')
    expect(month('bad')).toBe('bad')
  })
})

describe('short dollars', () => {
  it('rounds to whole dollars from a hundred up', () => {
    expect([
      usdShort('1500.06', true),
      usdShort('-500'),
      usdShort('20.5', true),
      usdShort(null),
    ]).toEqual(['+1 500 $', '-500 $', '+20,5 $', '—'])
  })
})

describe('prices', () => {
  it('keep four significant digits below a dollar and cents above', () => {
    expect([
      price('0.000712345', 'USD'),
      price('0.5123456', 'USD'),
      price('3.4812', 'USD'),
      price('62000.456', 'USD'),
      price('0.00000012', 'BTC'),
      price(null, 'USD'),
    ]).toEqual(
      ['0,0007123 $', '0,5123 $', '3,48 $', '62 000,46 $', '0,00000012 BTC', '—'].map((text) =>
        text.replaceAll(' ', NB),
      ),
    )
  })
})
