/**
 * Chart geometry, pure so it is testable. Charts draw in real pixels (their measured width), so circles
 * stay round and text keeps its size; numbers here are display values, never money arithmetic.
 */

export interface Point {
  x: number
  y: number
}

/** Round values covering [min, max]: steps of 1, 2 or 5 × 10ⁿ, about `count` of them. */
export function ticks(min: number, max: number, count = 4): number[] {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return []
  // Values apart by float noise alone are one value: a step that small would not move past them.
  if (max - min <= Math.max(Math.abs(min), Math.abs(max)) * 1e-9) {
    const pad = Math.abs(min) * 0.05 || 1
    max = min + pad
    min -= pad
  }
  const rough = (max - min) / Math.max(1, count)
  const power = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 5, 10].map((factor) => factor * power).find((value) => value >= rough)!
  const first = Math.floor(min / step) * step
  const result: number[] = []
  for (let value = first; value <= max + step * 1e-9 && result.length <= count * 3; value += step)
    result.push(Number(value.toPrecision(12)))
  if (result.at(-1)! < max) result.push(Number((result.at(-1)! + step).toPrecision(12)))
  return result
}

/** A linear map of a domain onto a range; a flat domain maps to the middle. */
export function linear(
  domain: [number, number],
  range: [number, number],
): (value: number) => number {
  const [d0, d1] = domain
  const [r0, r1] = range
  if (d0 === d1) return () => (r0 + r1) / 2
  return (value) => r0 + ((value - d0) / (d1 - d0)) * (r1 - r0)
}

/** The index of the nearest of sorted positions, or -1 for none. */
export function nearestIndex(positions: number[], at: number): number {
  let best = -1
  positions.forEach((position, index) => {
    if (best < 0 || Math.abs(position - at) < Math.abs(positions[best]! - at)) best = index
  })
  return best
}

/** «306 тыс.», «1,5 млн», «0,0012»: an axis label, short rather than exact, in Russian. */
export function compact(value: number): string {
  const size = Math.abs(value)
  if (size >= 1e6) return `${trim((value / 1e6).toFixed(size >= 1e7 ? 0 : 1))}\u00a0млн`
  if (size >= 1e4) return `${trim((value / 1e3).toFixed(0))}\u00a0тыс.`
  if (size >= 1e3) return `${trim((value / 1e3).toFixed(1))}\u00a0тыс.`
  if (size >= 1 || size === 0) return trim(value.toFixed(size >= 100 ? 0 : 2))
  return trim(value.toPrecision(2))
}

/** Drops trailing zeros and writes the decimal comma. */
const trim = (text: string) =>
  (text.includes('.') ? text.replace(/\.?0+$/, '') : text).replace('.', ',')

/** An axis label as precise as the grid's step needs: «1 510» for steps of ten, «306 тыс.» for thousands. */
export function axis(value: number, step: number): string {
  const size = Math.abs(step)
  if (size >= 1000) return compact(value)
  const decimals = Math.max(0, Math.min(8, -Math.floor(Math.log10(size || 1))))
  const [whole = '', fraction] = value.toFixed(decimals).split('.')
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0')
  return fraction ? `${grouped},${fraction}` : grouped
}

/** An SVG path through points, and the area under it down to `bottom`. */
export function paths(placed: Array<{ left: number; top: number }>, bottom: number) {
  const line = placed
    .map((point, index) => `${index ? 'L' : 'M'}${point.left.toFixed(1)},${point.top.toFixed(1)}`)
    .join(' ')
  const area = placed.length
    ? `${line} L${placed.at(-1)!.left.toFixed(1)},${bottom.toFixed(1)} L${placed[0]!.left.toFixed(1)},${bottom.toFixed(1)} Z`
    : ''
  return { line, area }
}

/** Colors of series and shares, as whole class names (Tailwind finds classes only when spelled out). */
export const FILLS = [
  'fill-chart-1',
  'fill-chart-3',
  'fill-chart-2',
  'fill-chart-4',
  'fill-chart-5',
] as const
export const DOTS = ['bg-chart-1', 'bg-chart-3', 'bg-chart-2', 'bg-chart-4', 'bg-chart-5'] as const
