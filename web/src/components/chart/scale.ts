/** Maps data points into an SVG box; pure, so the chart geometry is testable. */
export interface Point {
  x: number
  y: number
}

export interface Box {
  width: number
  height: number
  padding: number
  /** Room on the left for the value labels; `padding` when absent. */
  left?: number
}

export interface Scaled {
  path: string
  area: string
  points: Array<Point & { left: number; top: number }>
  min: number
  max: number
  /** Horizontal and vertical position of any value, for extra lines on the same scale. */
  x(value: number): number
  y(value: number): number
}

/**
 * [domain] widens the value range (a goal line that must stay in view, rounded tick ends); the
 * x range always spans the points.
 */
export function scale(
  points: Point[],
  box: Box,
  domain: { min?: number; max?: number } = {},
): Scaled | null {
  if (!points.length) return null
  const xs = points.map((point) => point.x)
  const ys = points.map((point) => point.y)
  const [minX, maxX] = [Math.min(...xs), Math.max(...xs)]
  let min = Math.min(...ys, domain.min ?? Infinity)
  let max = Math.max(...ys, domain.max ?? -Infinity)
  if (min === max) {
    // A flat series still needs a visible band around it.
    const pad = Math.abs(min) * 0.05 || 1
    min -= pad
    max += pad
  }
  const start = box.left ?? box.padding
  const inner = { width: box.width - start - box.padding, height: box.height - box.padding * 2 }
  const x = (value: number) =>
    start + (maxX === minX ? inner.width / 2 : ((value - minX) / (maxX - minX)) * inner.width)
  const y = (value: number) => box.padding + (1 - (value - min) / (max - min)) * inner.height
  const placed = points.map((point) => ({ ...point, left: x(point.x), top: y(point.y) }))
  const path = line(points, x, y)
  const bottom = (box.height - box.padding).toFixed(1)
  const area = `${path} L${placed.at(-1)!.left.toFixed(1)},${bottom} L${placed[0]!.left.toFixed(1)},${bottom} Z`
  return { path, area, points: placed, min, max, x, y }
}

/** An SVG path through the points; a pause longer than [gap] starts a new piece. */
export function line(
  points: Point[],
  x: (value: number) => number,
  y: (value: number) => number,
  gap = Infinity,
): string {
  return points
    .map((point, index) => {
      const move = index === 0 || point.x - points[index - 1]!.x > gap
      return `${move ? 'M' : 'L'}${x(point.x).toFixed(1)},${y(point.y).toFixed(1)}`
    })
    .join(' ')
}

/** Three to five round values covering [min, max] (1, 2, 2.5 or 5 times a power of ten). */
export function ticks(min: number, max: number, count = 4): number[] {
  const span = max - min || Math.abs(max) || 1
  const raw = span / count
  const power = 10 ** Math.floor(Math.log10(raw))
  const step = ([1, 2, 2.5, 5, 10].find((factor) => factor * power >= raw) ?? 10) * power
  const first = Math.floor(min / step) * step
  const values: number[] = []
  for (let value = first; value <= max + step * 1e-9; value += step)
    values.push(Number(value.toFixed(10)))
  if (values.at(-1)! < max) values.push(Number((values.at(-1)! + step).toFixed(10)))
  return values
}

/**
 * A smoothed trend that weighs each new value by the time since the previous one, so daily
 * noise (water, salt) fades and a week-long change shows: an EMA with a [tau] time constant.
 */
export function trend(points: Point[], tau: number): Point[] {
  const result: Point[] = []
  for (const point of points) {
    const previous = result.at(-1)
    if (!previous) {
      result.push({ ...point })
      continue
    }
    const alpha = 1 - Math.exp(-(point.x - previous.x) / tau)
    result.push({ x: point.x, y: previous.y + alpha * (point.y - previous.y) })
  }
  return result
}

/** The point closest to a horizontal position, for the pointer and keyboard tooltip. */
export function nearest<T extends { left: number }>(points: T[], left: number): T | null {
  let best: T | null = null
  for (const point of points)
    if (!best || Math.abs(point.left - left) < Math.abs(best.left - left)) best = point
  return best
}
