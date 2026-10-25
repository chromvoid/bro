import { describe, expect, it } from 'vitest'
import { Progress, type Progress as ProgressData } from './api'
import { strengthDays } from './strengthSummary'

function series(points: Partial<ProgressData['series'][number]['points'][number]>[]) {
  return Progress.parse({
    exercise: { id: 'bench', revision: 1, name: 'Bench', load_basis: 'total' },
    from: '2026-10-01',
    to: '2026-10-10',
    daily: [],
    series: [
      {
        snapshot: { name: 'Bench', equipment: 'Barbell', load_basis: 'total' },
        load_unit: 'kg',
        implement_count: 1,
        points: points.map((point, index) => ({
          date: '2026-10-05',
          session_id: 'session',
          session_exercise_id: 'performed',
          set_id: `set-${index}`,
          repetitions: null,
          load_value: null,
          load_kg: null,
          ...point,
        })),
      },
    ],
  }).series[0]!
}

describe('strength result summaries', () => {
  it('preserves real pairs and all sessions of the latest day instead of multiplying separate maxima', () => {
    const data = series([
      { date: '2026-10-03', repetitions: 8, load_kg: '50' },
      { repetitions: 5, load_kg: '60' },
      { session_id: 'another', repetitions: 12, load_kg: '40' },
    ])
    const result = strengthDays(data)
    expect(result.latest?.points.map((point) => [point.load_kg, point.repetitions])).toEqual([
      ['60', 5],
      ['40', 12],
    ])
    expect(result.previous?.date).toBe('2026-10-03')
    expect(result.previous?.points).toEqual([data.points[0]])
  })

  it('keeps zero distinct from missing load or repetitions and does not invent a previous day', () => {
    const data = series([
      { repetitions: 0, load_kg: '0' },
      { repetitions: 8, load_kg: null },
    ])
    expect(strengthDays(data)).toEqual({
      latest: { date: '2026-10-05', points: data.points },
      previous: null,
    })
    expect(strengthDays(series([]))).toEqual({ latest: null, previous: null })
  })

  it('selects previous distinct dates regardless of response order', () => {
    const data = series([
      { date: '2026-10-07' },
      { date: '2026-10-01' },
      { date: '2026-10-04' },
      { date: '2026-10-07', session_id: 'second' },
    ])
    const result = strengthDays(data)
    expect(result.latest?.date).toBe('2026-10-07')
    expect(result.latest?.points).toHaveLength(2)
    expect(result.previous?.date).toBe('2026-10-04')
  })
})
