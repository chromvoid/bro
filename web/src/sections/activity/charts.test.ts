import { describe, expect, it } from 'vitest'
import { History } from '@/sections/personal/api'
import { Progress, Summary } from './api'
import { dailyMetric, hasSessions, progressPoints, rangeDigits, summaryPoints } from './charts'

describe('activity graph projections', () => {
  it('retains measured zero and gaps in steps, without adding session steps', () => {
    const history = History.parse({
      from: '2026-10-01',
      to: '2026-10-04',
      records: [],
      daily: [
        { date: '2026-10-01', kind: 'steps', status: 'available', value: '0', target: null },
        { date: '2026-10-02', kind: 'steps', status: 'no_data', value: null, target: null },
        { date: '2026-10-03', kind: 'steps', status: 'conflict', value: '500', target: null },
        {
          date: '2026-10-04',
          kind: 'steps',
          status: 'available',
          value: '8000',
          target: null,
          manual_override: { active: true },
        },
      ],
    })
    expect(dailyMetric(history, 'steps').map((point) => point.y)).toEqual([0, 8000])
  })
  it('does not turn an unknown duration into zero or combine distance kinds', () => {
    const summary = Summary.parse({
      from: '2026-10-01',
      to: '2026-10-02',
      totals: {
        sessions: 2,
        duration_minutes: '0',
        missing_duration_sessions: 1,
        distance_km_by_kind: { walking: '2', running: '4' },
        working_sets: 0,
      },
      daily: [
        {
          date: '2026-10-01',
          sessions: 1,
          duration_minutes: null,
          missing_duration_sessions: 1,
          distance_km_by_kind: { walking: '2' },
          working_sets: 0,
        },
        {
          date: '2026-10-02',
          sessions: 1,
          duration_minutes: '0',
          missing_duration_sessions: 0,
          distance_km_by_kind: { running: '4' },
          working_sets: 0,
        },
      ],
    })
    expect(summaryPoints(summary, 'duration_minutes').map((point) => point.y)).toEqual([0])
    expect(summaryPoints(summary, 'sessions', 'walking').map((point) => point.y)).toEqual([2])
  })
  it('uses normalized loads inside compatible series and reverses assistance selection', () => {
    const progress = Progress.parse({
      exercise: {
        id: 'def',
        revision: 1,
        name: 'Bench',
        equipment: '',
        variant: '',
        load_basis: 'assistance',
      },
      from: '2026-10-01',
      to: '2026-10-01',
      daily: [],
      series: [
        {
          snapshot: { name: 'Bench', load_basis: 'assistance' },
          load_unit: 'kg',
          implement_count: 1,
          points: [
            {
              date: '2026-10-01',
              session_id: 'session',
              session_exercise_id: 'exercise',
              set_id: 'one',
              repetitions: 8,
              load_value: '50',
              load_kg: '22.6796185',
            },
            {
              date: '2026-10-01',
              session_id: 'session',
              session_exercise_id: 'exercise',
              set_id: 'two',
              repetitions: 10,
              load_value: '20',
              load_kg: '20',
            },
            {
              date: '2026-10-01',
              session_id: 'session',
              session_exercise_id: 'exercise',
              set_id: 'unknown',
              repetitions: null,
              load_value: null,
              load_kg: null,
            },
          ],
        },
      ],
    })
    expect(progressPoints(progress.series[0]!, 'load_kg').map((point) => point.y)).toEqual([20])
    expect(progressPoints(progress.series[0]!, 'repetitions').map((point) => point.y)).toEqual([10])
  })
})

describe('period words', () => {
  it('writes a period in digits for a narrow row', () => {
    expect(rangeDigits('2026-05-12', '2026-06-10')).toBe('12.05–10.06')
  })

  it('knows an empty period', () => {
    const summary = (sessions: number) =>
      Summary.parse({
        from: '2026-10-01',
        to: '2026-10-07',
        totals: {
          sessions,
          duration_minutes: null,
          missing_duration_sessions: 0,
          distance_km_by_kind: {},
          working_sets: 0,
        },
        daily: [],
      })
    expect(hasSessions(summary(0))).toBe(false)
    expect(hasSessions(summary(2))).toBe(true)
    expect(hasSessions(undefined)).toBe(false)
  })
})
