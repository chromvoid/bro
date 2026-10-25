import { describe, expect, it } from 'vitest'
import type { Dish, Entry } from './api'
import { byKind, meals } from './meals'

const entry = (
  id: string,
  at: string | null,
  kcal: string | null,
  meal: string | null = id,
  kind: Entry['meal_kind'] = null,
) =>
  ({
    id,
    revision: 1,
    quantity: '100',
    unit: 'g',
    local_date: '2026-10-03',
    logged_at: at,
    meal_id: meal,
    meal_kind: kind,
    nutrients: { energy_kcal: kcal },
  }) as Entry

describe('meals of a day', () => {
  it('keeps what was logged together and starts a new meal after a long pause', () => {
    const day = meals([
      entry('oats', '2026-10-03T06:30:00Z', '190'),
      entry('banana', '2026-10-03T06:40:00Z', '91'),
      entry('soup', '2026-10-03T11:00:00Z', '230', 'lunch'),
      // Logged in the same operation as the soup, even if the time came out later.
      entry('bread', '2026-10-03T12:30:00Z', null, 'lunch'),
    ])
    expect(day.map((meal) => meal.items.map((item) => item.id))).toEqual([
      ['oats', 'banana'],
      ['soup', 'bread'],
    ])
    expect(day[0]).toMatchObject({ at: '2026-10-03T06:30:00.000Z', kcal: 281, complete: true })
    expect(day[1]).toMatchObject({ kcal: 230, complete: false })
  })

  it('keeps a meal together across a long pause and starts it at its earliest item', () => {
    const day = meals([
      entry('oats', '2026-10-03T06:30:00Z', '190', 'morning'),
      entry('soup', '2026-10-03T11:00:00Z', '230', 'noon'),
      // A draft of the breakfast completed hours later still belongs to the breakfast.
      entry('banana', '2026-10-03T13:00:00Z', '91', 'morning'),
      entry('tea', '2026-10-03T18:00:00Z', '5', 'evening'),
    ])
    expect(day.map((meal) => meal.items.map((item) => item.id))).toEqual([
      ['oats', 'banana'],
      ['soup'],
      ['tea'],
    ])
    expect(day[0]).toMatchObject({
      key: 'oats',
      at: '2026-10-03T06:30:00.000Z',
      kcal: 281,
      mealIds: ['morning'],
    })
  })

  it('joins logged meals that a shared meal and the 45-minute window connect', () => {
    const day = meals([
      entry('coffee', '2026-10-03T09:40:00Z', '2', 'morning'),
      entry('oats', '2026-10-03T07:00:00Z', '190', 'morning'),
      // Logged right after the oats in its own operation: one meal to read, two logged ones.
      entry('banana', '2026-10-03T07:20:00Z', '91', 'later'),
      entry('soup', '2026-10-03T12:00:00Z', '230', 'noon'),
    ])
    expect(day.map((meal) => meal.items.map((item) => item.id))).toEqual([
      ['oats', 'banana', 'coffee'],
      ['soup'],
    ])
    expect(day[0]!.mealIds).toEqual(['morning', 'later'])
  })

  it('never lets a late member of one meal pull in a neighbour logged near it', () => {
    const day = meals([
      entry('coffee', '2026-10-03T11:00:00Z', '2', 'first'),
      entry('yogurt', '2026-10-03T16:30:00Z', '90', 'second'),
      // The morning snack's draft completed ten minutes after a separate snack was logged.
      entry('apple', '2026-10-03T16:40:00Z', '78', 'first'),
    ])
    expect(day.map((meal) => meal.items.map((item) => item.id))).toEqual([
      ['coffee', 'apple'],
      ['yogurt'],
    ])
    expect(day.map((meal) => meal.mealIds)).toEqual([['first'], ['second']])
  })

  it('places a dish by its first component and entries without a time last', () => {
    const dish = {
      id: 'dish',
      kind: 'dish',
      name: 'Завтрак',
      quantity: '300',
      unit: 'g',
      revision: 1,
      components: [entry('egg', '2026-10-03T07:00:00Z', '150', 'dish-meal')],
      nutrients: { energy_kcal: '300' },
    } as Dish
    const day = meals([entry('old', null, '50'), dish, entry('tea', '2026-10-03T07:20:00Z', '5')])
    expect(day.map((meal) => meal.items.map((item) => item.id))).toEqual([['dish', 'tea'], ['old']])
    expect(day[1]!.at).toBeNull()
  })
})

describe('meals by kind', () => {
  it('orders the kinds of a day and keeps meals without one last, by time', () => {
    const sections = byKind([
      entry('tea', '2026-10-03T18:00:00Z', '40', 'late'),
      entry('soup', '2026-10-03T11:00:00Z', '230', 'noon', 'lunch'),
      entry('oats', '2026-10-03T06:30:00Z', '190', 'morning', 'breakfast'),
      entry('banana', '2026-10-03T06:40:00Z', '91', 'later', 'breakfast'),
    ])
    expect(sections.map((section) => section.kind)).toEqual(['breakfast', 'lunch', null])
    expect(sections[0]).toMatchObject({ kcal: 281, complete: true })
    // Logged in a row, oats and the banana read as one meal of two logged meals.
    expect(sections[0]!.meals.map((meal) => meal.mealIds)).toEqual([['morning', 'later']])
  })
})
