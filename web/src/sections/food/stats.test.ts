import { describe, expect, it } from 'vitest'
import { periodOf, shiftPeriod, summarize, type StatsDay } from './stats'

const day = (date: string, fields: Partial<StatsDay> = {}): StatsDay => ({
  date,
  logged: false,
  energy_kcal: '0',
  energy_complete: true,
  protein_g: '0',
  fat_g: '0',
  carbohydrate_g: '0',
  completed: false,
  target: null,
  macro_targets: null,
  kcal_by_kind: {},
  ...fields,
})

describe('statistics periods', () => {
  it('takes the week from Monday and the calendar month, and moves by whole periods', () => {
    expect(periodOf('week', '2026-10-03')).toEqual({
      kind: 'week',
      start: '2026-09-28',
      end: '2026-10-04',
    })
    const month = periodOf('month', '2026-02-14')
    expect(month).toEqual({ kind: 'month', start: '2026-02-01', end: '2026-02-28' })
    expect(shiftPeriod(month, -1)).toMatchObject({ start: '2026-01-01', end: '2026-01-31' })
    expect(shiftPeriod(month, 1)).toMatchObject({ start: '2026-03-01', end: '2026-03-31' })
  })
})

describe('statistics summary', () => {
  it('averages only the days with entries and says how close they came to the goal', () => {
    const summary = summarize([
      day('2026-09-28', {
        logged: true,
        energy_kcal: '1900',
        protein_g: '90',
        target: '2000',
        macro_targets: { protein_g: '100' },
        completed: true,
        kcal_by_kind: { breakfast: '500', lunch: '900', none: '500' },
      }),
      day('2026-09-29', {
        logged: true,
        energy_kcal: '2500',
        protein_g: '70',
        target: '2000',
        macro_targets: { protein_g: '100' },
        kcal_by_kind: { dinner: '2500' },
      }),
      day('2026-09-30'),
    ])
    expect(summary).toMatchObject({
      days: 3,
      logged: 2,
      completed: 1,
      withGoal: 2,
      nearGoal: 1,
      overGoal: 1,
      underGoal: 0,
      kcal: 2200,
      target: 2000,
    })
    expect(summary.macros.protein_g).toEqual({ average: 80, target: 100 })
    expect(summary.macros.fat_g.target).toBeNull()
    expect(summary.kinds.map((item) => [item.kind, item.kcal])).toEqual([
      ['breakfast', 500],
      ['lunch', 900],
      ['dinner', 2500],
      ['none', 500],
    ])
  })

  it('has nothing to average in an empty period', () => {
    const summary = summarize([day('2026-09-28'), day('2026-09-29')])
    expect(summary).toMatchObject({ logged: 0, kcal: null, target: null, kinds: [] })
  })
})
