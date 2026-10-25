import { describe, expect, it } from 'vitest'
import { bar, weekStart, type WeekDay } from './week'

const day = (energy_kcal: string, target: string | null): WeekDay => ({
  date: '2026-10-03',
  energy_kcal,
  completed: false,
  target,
})

describe('week strip', () => {
  it('starts the week on Monday', () => {
    expect(weekStart('2026-10-03')).toBe('2026-09-28') // Saturday
    expect(weekStart('2026-09-28')).toBe('2026-09-28') // Monday
    expect(weekStart('2026-10-04')).toBe('2026-09-28') // Sunday
  })

  it('measures a day against its goal, or against the largest day without one', () => {
    expect(bar(day('1050', '2100'), 0)).toEqual({ share: 40, over: false, goal: 80 })
    expect(bar(day('2100', '2100'), 0)).toEqual({ share: 80, over: false, goal: 80 })
    expect(bar(day('900', null), 1800)).toEqual({ share: 50, over: false, goal: null })
    expect(bar(day('10', '2100'), 0).share).toBe(6)
    expect(bar(day('0', '2100'), 0)).toEqual({ share: 0, over: false, goal: 80 })
  })

  it('shows a day past its goal above the goal mark, not only in another colour', () => {
    expect(bar(day('2400', '2100'), 0)).toEqual({
      share: (2400 / 2100) * 80,
      over: true,
      goal: 80,
    })
    expect(bar(day('5000', '2100'), 0)).toMatchObject({ share: 100, over: true })
  })
})
