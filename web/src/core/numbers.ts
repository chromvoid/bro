/**
 * Display of server numbers. Nutrition values arrive as decimal strings and are only ever shown,
 * never recalculated here; an unknown value (null) is shown as a dash, never as zero.
 */
const formats = new Map<number, Intl.NumberFormat>()

function format(fraction: number): Intl.NumberFormat {
  let found = formats.get(fraction)
  if (!found) {
    found = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: fraction })
    formats.set(fraction, found)
  }
  return found
}

export const UNKNOWN = '—'

export function formatAmount(value: string | number | null | undefined, fraction = 1): string {
  if (value === null || value === undefined || value === '') return UNKNOWN
  const number = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(number)) return UNKNOWN
  // Intl uses a non-breaking space for thousands; keep it so numbers never wrap.
  return format(fraction).format(number)
}

/** Parses what the user typed (comma or dot) into the decimal string the server expects. */
export function parseAmount(text: string): string | null {
  const normalized = text.trim().replace(/\s/g, '').replace(',', '.')
  if (!/^\d+(\.\d+)?$/.test(normalized)) return null
  return normalized.replace(/^0+(?=\d)/, '')
}

const BYTE_UNITS = ['Б', 'КБ', 'МБ', 'ГБ'] as const
/**
 * A size as people read it: «613,8 КБ», «32 ГБ». Steps of 1024, as phones and computers count
 * storage, under the everyday names (not «КиБ»).
 */
export function formatBytes(value: number): string {
  const index =
    value > 0 ? Math.min(BYTE_UNITS.length - 1, Math.floor(Math.log(value) / Math.log(1024))) : 0
  const amount = value / 1024 ** index
  return `${format(index ? 1 : 0).format(amount)} ${BYTE_UNITS[index]}`
}
