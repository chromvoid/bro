/**
 * Display of service decimals. They are exact strings: rounded and grouped as text, never through
 * binary floating point. A missing value is a dash, never zero.
 */
import type { Freshness } from './contracts'

export const MISSING = '—'
const NBSP = ' '
const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/

/** Rounds half away from zero to at most [places] digits and drops trailing zeros. */
export function roundDecimal(value: string, places: number): string | null {
  const match = DECIMAL.exec(value.trim())
  if (!match) return null
  const [, sign = '', whole = '', fraction = ''] = match
  const digits = (whole + fraction.padEnd(places, '0').slice(0, places)).split('')
  if (fraction.length > places && fraction[places]! >= '5') {
    let index = digits.length - 1
    while (index >= 0) {
      if (digits[index] === '9') {
        digits[index] = '0'
        index -= 1
      } else {
        digits[index] = String(Number(digits[index]) + 1)
        break
      }
    }
    if (index < 0) digits.unshift('1')
  }
  const all = digits.join('')
  const integer = all.slice(0, all.length - places).replace(/^0+/, '') || '0'
  const decimals = places ? all.slice(all.length - places).replace(/0+$/, '') : ''
  const body = decimals ? `${integer}.${decimals}` : integer
  return sign === '-' && /[1-9]/.test(body) ? `-${body}` : body
}

/** «1 520,5»: thousands grouped with a no-break space and a decimal comma, as in the rest of the app. */
export function groupThousands(value: string): string {
  const sign = /^[-+]*/.exec(value)![0]
  const body = value.slice(sign.length)
  const [whole = '', rest] = body.split('.', 2)
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, NBSP)
  return sign + grouped + (rest === undefined ? '' : `,${rest}`)
}

/** Adds «+» to a positive non-zero value; for changes, not amounts. */
export function signed(value: string): string {
  return !value.startsWith('-') && /[1-9]/.test(value) ? `+${value}` : value
}

export function usd(value: string | null | undefined, sign = false): string {
  const rounded = value == null ? null : roundDecimal(value, 2)
  if (rounded === null) return MISSING
  return `${groupThousands(sign ? signed(rounded) : rounded)}${NBSP}$`
}

/** Dollars for a small tile: whole ones from a hundred up («+1 500 $»), cents below. */
export function usdShort(value: string | null | undefined, sign = false): string {
  const size = value == null ? null : roundDecimal(value.replace(/^-/, ''), 0)
  return usd(size !== null && size.length >= 3 ? roundDecimal(value!, 0) : value, sign)
}

export function percent(value: string | null | undefined, sign = true): string {
  const rounded = value == null ? null : roundDecimal(value, 2)
  if (rounded === null) return MISSING
  return `${groupThousands(sign ? signed(rounded) : rounded)}${NBSP}%`
}

/** A result or price in the unit positions are counted in: dollars, or a coin to 8 decimals («+0.0125 BTC»). */
export function inUnit(value: string | null | undefined, unit: string, sign = false): string {
  if (unit === 'USD') return usd(value, sign)
  const rounded = value == null ? null : roundDecimal(value, 8)
  if (rounded === null) return MISSING
  return `${groupThousands(sign ? signed(rounded) : rounded)}${NBSP}${unit}`
}

/** Decimals that keep four significant digits of a price below one («0.0007123»); two from one up. */
function priceDecimals(value: string): number {
  const size = Math.abs(Number(value))
  return !Number.isFinite(size) || size === 0 || size >= 1
    ? 2
    : Math.min(12, 3 - Math.floor(Math.log10(size)))
}

/** A coin's price in the unit: dollars to four significant digits below one, so that HMSTR does not read «0 $»;
 * a coin to 8 decimals. */
export function price(value: string | null | undefined, unit: string): string {
  if (unit !== 'USD' || value == null) return inUnit(value, unit)
  const rounded = roundDecimal(value, priceDecimals(value))
  return rounded === null ? MISSING : `${groupThousands(rounded)}${NBSP}$`
}

/** A quote-currency amount: «+12.34 USDT». */
export function quote(value: string | null | undefined, coin: string, sign = false): string {
  const rounded = value == null ? null : roundDecimal(value, 2)
  if (rounded === null) return MISSING
  return `${groupThousands(sign ? signed(rounded) : rounded)}${NBSP}${coin}`
}

/** An exact amount as sent, grouped: coin quantities and prices. */
export function amount(value: string | null | undefined): string {
  const trimmed = value?.trim()
  return trimmed && DECIMAL.test(trimmed) ? groupThousands(trimmed) : MISSING
}

/** Formatters are costly to build (charts and long lists format hundreds of dates): one per zone and style. */
const formatters = new Map<string, Intl.DateTimeFormat>()
function formatter(locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = `${locale} ${JSON.stringify(options)}`
  let found = formatters.get(key)
  if (!found) formatters.set(key, (found = new Intl.DateTimeFormat(locale, options)))
  return found
}

/** «25.09.2026 13:00» in the account's zone. */
export function dateTime(value: string | null | undefined, timeZone: string): string {
  const instant = value ? new Date(value) : null
  if (!instant || Number.isNaN(instant.getTime())) return MISSING
  const parts = formatter('en-GB', {
    timeZone,
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant)
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${get('day')}.${get('month')}.${get('year')} ${get('hour')}:${get('minute')}`
}

/** «13:05» today, «24.09 13:05» this year, the full date and time before. */
export function moment(value: string | null | undefined, timeZone: string, now: number): string {
  const shown = dateTime(value, timeZone)
  if (shown === MISSING) return shown
  const today = dateTime(new Date(now).toISOString(), timeZone)
  if (shown.slice(0, 10) === today.slice(0, 10)) return shown.slice(11)
  if (shown.slice(6, 10) === today.slice(6, 10)) return `${shown.slice(0, 5)} ${shown.slice(11)}`
  return shown
}

/** «сен» and «сентябрь 2025» for a «2025-09» month. */
export function month(value: string, long = false): string {
  const at = new Date(`${value}-01T00:00:00Z`)
  if (Number.isNaN(at.getTime())) return value
  const text = formatter(
    'ru',
    long
      ? { timeZone: 'UTC', month: 'long', year: 'numeric' }
      : { timeZone: 'UTC', month: 'short' },
  ).format(at)
  return text.replace(/ г\.$/, '').replace(/\.$/, '')
}

/** «25.09.2026» in the account's zone. */
export function date(value: string | null | undefined, timeZone: string): string {
  const shown = dateTime(value, timeZone)
  return shown === MISSING ? shown : shown.slice(0, 10)
}

const LIVE = new Set(['active', 'validating'])
export const STALE_AFTER_MS = 10 * 60_000

/** Unhealthy connection, no snapshot time, or a snapshot older than ten minutes. */
export function isStale(freshness: Freshness, now: number): boolean {
  if (!LIVE.has(freshness.connection_state)) return true
  const asOf = freshness.as_of ? Date.parse(freshness.as_of) : NaN
  return Number.isNaN(asOf) || now - asOf > STALE_AFTER_MS
}
