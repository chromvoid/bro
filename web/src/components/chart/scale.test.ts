import { describe, expect, it } from 'vitest'
import { line, nearest, scale, ticks, trend } from './scale'

describe('chart scale', () => {
  const box = { width: 100, height: 50, padding: 5 }

  it('spans the box from the first to the last point, with higher values drawn higher', () => {
    const result = scale(
      [
        { x: 0, y: 10 },
        { x: 10, y: 20 },
      ],
      box,
    )!
    expect(result.points.map((point) => [point.left, point.top])).toEqual([
      [5, 45],
      [95, 5],
    ])
    expect(result.path).toBe('M5.0,45.0 L95.0,5.0')
    expect(result.area.endsWith('L95.0,45.0 L5.0,45.0 Z')).toBe(true)
  })

  it('draws a flat series in the middle and a single point centred', () => {
    const flat = scale(
      [
        { x: 0, y: 7 },
        { x: 1, y: 7 },
      ],
      box,
    )!
    expect(flat.points[0]!.top).toBeCloseTo(25)
    expect(scale([{ x: 5, y: 1 }], box)!.points[0]!.left).toBe(50)
    expect(scale([], box)).toBeNull()
  })

  it('keeps a goal in view, leaves room for labels and breaks the line on long pauses', () => {
    const points = [
      { x: 0, y: 80 },
      { x: 1, y: 81 },
      { x: 10, y: 79 },
    ]
    const result = scale(points, { ...box, left: 20 }, { min: 75 })!
    expect(result.min).toBe(75)
    expect(result.points[0]!.left).toBe(20)
    expect(result.y(75)).toBe(45)
    expect(line(points, result.x, result.y, 5).match(/M/g)).toHaveLength(2)
  })

  it('picks round ticks that cover the range', () => {
    expect(ticks(79.2, 82.6)).toEqual([79, 80, 81, 82, 83])
    expect(ticks(0, 2100)).toEqual([0, 1000, 2000, 3000])
    expect(ticks(70, 70).length).toBeGreaterThanOrEqual(2)
  })

  it('smooths a series by the time between values', () => {
    const day = 86_400_000
    const smooth = trend(
      [
        { x: 0, y: 80 },
        { x: day, y: 82 },
        { x: 8 * day, y: 82 },
      ],
      7 * day,
    )
    expect(smooth[0]!.y).toBe(80)
    // One day pulls the trend a little towards a jump; a week of waiting pulls it most of the way.
    expect(smooth[1]!.y).toBeCloseTo(80 + 2 * (1 - Math.exp(-1 / 7)))
    expect(smooth[2]!.y).toBeGreaterThan(81)
    expect(smooth[2]!.y).toBeLessThan(82)
  })

  it('finds the nearest point to the pointer', () => {
    const points = [{ left: 5 }, { left: 50 }, { left: 95 }]
    expect(nearest(points, 60)).toEqual({ left: 50 })
  })
})
