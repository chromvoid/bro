import { describe, expect, it } from 'vitest'
import type { Day } from '@/sections/food/api'
import { daySuggestions, foodRecorded, mealRecorded, quiet, type DayState } from './daySuggestions'

const morning: DayState = {
  hour: 8,
  meal: 'breakfast',
  mealRecorded: false,
  foodRecorded: false,
  tasksToday: true,
}

function entry(kind: Day['entries'][number]['meal_kind']) {
  return {
    id: 'e',
    revision: 1,
    quantity: '1',
    unit: 'g',
    local_date: '2026-10-07',
    meal_kind: kind,
  }
}

describe('suggestions of the day', () => {
  it('suggests recording the meal of the hour and asking about the day ahead', () => {
    expect(daySuggestions(morning)).toEqual([
      { key: 'breakfast', mode: 'start' },
      { key: 'dayAhead', mode: 'ask' },
    ])
  })

  it('asks what is left in the afternoon and leaves out a meal already recorded', () => {
    expect(daySuggestions({ ...morning, hour: 14, meal: 'lunch', mealRecorded: true })).toEqual([
      { key: 'dayLeft', mode: 'ask' },
    ])
  })

  it('offers the summary in the evening once something is recorded', () => {
    const evening: DayState = {
      hour: 21,
      meal: 'dinner',
      mealRecorded: true,
      foodRecorded: true,
      tasksToday: false,
    }
    expect(daySuggestions(evening)).toEqual([{ key: 'summary', mode: 'ask' }])
    expect(daySuggestions({ ...evening, mealRecorded: false, foodRecorded: false })).toEqual([
      { key: 'dinner', mode: 'start' },
    ])
  })

  it('suggests no meal at night and nothing it does not know', () => {
    expect(
      daySuggestions({
        hour: 23,
        meal: 'snack',
        mealRecorded: false,
        foodRecorded: null,
        tasksToday: null,
      }),
    ).toEqual([])
    expect(daySuggestions({ ...morning, mealRecorded: null, tasksToday: null })).toEqual([])
  })

  it('counts a meal by its kind, a draft included', () => {
    const day = { items: [entry('breakfast')], drafts: [] }
    expect(mealRecorded(day, 'breakfast')).toBe(true)
    expect(mealRecorded(day, 'lunch')).toBe(false)
    const draft = {
      id: 'd',
      label: 'суп',
      status: 'open',
      missing_fields: [],
      revision: 1,
      meal_kind: 'lunch' as const,
    }
    expect(mealRecorded({ items: [], drafts: [draft] }, 'lunch')).toBe(true)
    expect(foodRecorded({ items: [], drafts: [] })).toBe(false)
    expect(foodRecorded(day)).toBe(true)
  })

  it('waits half an hour after the last message', () => {
    const now = new Date('2026-10-07T12:00:00Z')
    expect(quiet(undefined, now)).toBe(true)
    expect(quiet('2026-10-07T11:45:00Z', now)).toBe(false)
    expect(quiet('2026-10-07T11:30:00Z', now)).toBe(true)
  })
})
