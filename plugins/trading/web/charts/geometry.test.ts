import { describe, expect, it } from 'vitest'
import { axis, compact, linear, nearestIndex, paths, ticks } from './geometry'

describe('chart geometry', () => {
  it('covers a range with round ticks', () => {
    expect(ticks(0, 100)).toEqual([0, 50, 100])
    expect(ticks(1500, 1520.5)).toEqual([1500, 1510, 1520, 1530])
    expect(ticks(-0.77, 0.78)).toEqual([-1, -0.5, 0, 0.5, 1])
    expect(ticks(5, 5).length).toBeGreaterThan(1)
    // apart by float noise alone: padded like one value, not stepped through forever
    expect(ticks(1, 1.0000000000000002)).toEqual(ticks(1, 1))
  })

  it('maps, finds the nearest and draws', () => {
    expect(linear([0, 10], [0, 100])(2.5)).toBe(25)
    expect(linear([3, 3], [0, 100])(3)).toBe(50)
    expect(nearestIndex([0, 10, 20], 14)).toBe(1)
    expect(nearestIndex([], 1)).toBe(-1)
    expect(
      paths(
        [
          { left: 0, top: 5 },
          { left: 10, top: 0 },
        ],
        20,
      ),
    ).toEqual({
      line: 'M0.0,5.0 L10.0,0.0',
      area: 'M0.0,5.0 L10.0,0.0 L10.0,20.0 L0.0,20.0 Z',
    })
  })

  it('labels an axis as precisely as its step needs', () => {
    const NB = '\u00a0'
    expect([1500, 1510, 1520].map((value) => axis(value, 10))).toEqual(
      ['500', '510', '520'].map((rest) => `1${NB}${rest}`),
    )
    expect([300000, 302000].map((value) => axis(value, 2000))).toEqual([
      `300${NB}тыс.`,
      `302${NB}тыс.`,
    ])
    expect([-0.5, 0, 0.5].map((value) => axis(value, 0.5))).toEqual(['-0,5', '0,0', '0,5'])
    expect(axis(0.0009, 0.0001)).toBe('0,0009')
  })

  it('writes short axis labels', () => {
    expect([
      compact(306115),
      compact(1520.5),
      compact(2_500_000),
      compact(-0.00092),
      compact(0),
    ]).toEqual(
      ['306 тыс.', '1,5 тыс.', '2,5 млн', '-0,00092', '0'].map((text) =>
        text.replace(' ', '\u00a0'),
      ),
    )
  })
})
