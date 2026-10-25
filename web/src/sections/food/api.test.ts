import { describe, expect, it } from 'vitest'
import {
  Day,
  Draft,
  Receipt,
  calorieBalance,
  draftMealKind,
  entryName,
  isDish,
  nutrientProgress,
  sameAmount,
  shownName,
  undoRevisions,
  unitLabel,
  suggestedMealKind,
} from './api'

describe('nutrition day', () => {
  it('reads entries and dishes and keeps unknown nutrients unknown', () => {
    const day = Day.parse({
      day_revision: 3,
      completed: false,
      date: '2026-09-25',
      entries: [],
      items: [
        {
          id: 'e1',
          revision: 2,
          quantity: '150',
          unit: 'g',
          local_date: '2026-09-25',
          name: 'Yogurt',
          name_ru: 'Йогурт',
          nutrients: { energy_kcal: '90', protein_g: null },
        },
        {
          id: 'd1',
          kind: 'dish',
          name: 'Овсянка с бананом',
          quantity: '350',
          unit: 'g',
          revision: 1,
          components: [],
          nutrients: { energy_kcal: null },
        },
      ],
      totals: { energy_kcal: { known: '90', complete: false } },
      goal: null,
      remaining_kcal: null,
      remaining_complete: false,
      drafts: [],
    })
    const [entry, dish] = day.items
    expect(isDish(entry!)).toBe(false)
    expect(isDish(dish!)).toBe(true)
    expect(entryName(entry as never)).toBe('Йогурт')
    expect((entry as { nutrients: { protein_g: null } }).nutrients.protein_g).toBeNull()
    expect(unitLabel('ml')).toBe('мл')
  })
})

describe('undoRevisions', () => {
  it('includes an aggregate dish revision even when its changed ingredients include removals', () => {
    expect(
      undoRevisions(
        Receipt.parse({
          operation_id: 'dish-op',
          kind: 'dish.update',
          dish_id: 'dish',
          revision: 5,
          expected_revisions: { dish: 5 },
          applied_entries: [{ entry_id: 'removed', revision: 4 }],
          applied_dishes: [{ dish_id: 'neighbor', revision: 2 }],
        }),
      ),
    ).toEqual({ dish: 5, neighbor: 2, removed: 4 })
  })
  it('names every entry an operation touched with its current revision', () => {
    const logged = Receipt.parse({
      operation_id: 'op1',
      kind: 'entry.add_many',
      applied_entries: [
        { entry_id: 'e1', revision: 1 },
        { entry_id: 'e2', revision: 1 },
      ],
    })
    expect(undoRevisions(logged)).toEqual({ e1: 1, e2: 1 })
    const edited = Receipt.parse({
      operation_id: 'op2',
      kind: 'entry.update',
      entry_id: 'e1',
      revision: 3,
    })
    expect(undoRevisions(edited)).toEqual({ e1: 3 })
    expect(undoRevisions(Receipt.parse({ operation_id: 'op3', kind: 'goal.set' }))).toBeNull()
  })
})

describe('day against its goal', () => {
  const day = (remaining: string | null, macros: Record<string, string | null> | null) =>
    Day.parse({
      day_revision: 1,
      completed: false,
      date: '2026-10-02',
      entries: [],
      items: [],
      totals: {
        energy_kcal: { known: '2300', complete: true },
        protein_g: { known: '63.6', complete: true },
        fat_g: { known: '81', complete: false },
      },
      goal: {
        id: 'g1',
        calorie_target: '2100',
        effective_local_date: '2026-09-18',
        revision: 1,
        macro_targets: macros,
      },
      remaining_kcal: remaining,
      remaining_complete: true,
      drafts: [],
    })

  it('says how far past the calorie goal the day went, or what is left', () => {
    expect(calorieBalance(day('-200', null))).toEqual({ kcal: 200, over: true })
    expect(calorieBalance(day('1245', null))).toEqual({ kcal: 1245, over: false })
    expect(calorieBalance(day(null, null))).toBeNull()
  })

  it('compares a nutrient only with a goal that names it', () => {
    const today = day('-200', { protein_g: '130', fat_g: '70', carbohydrate_g: null })
    expect(nutrientProgress(today, 'protein_g')).toMatchObject({
      target: '130',
      over: false,
      reached: false,
    })
    expect(nutrientProgress(today, 'protein_g')!.share).toBeCloseTo(48.9, 1)
    expect(nutrientProgress(today, 'fat_g')).toEqual({
      target: '70',
      share: 100,
      over: true,
      reached: false,
    })
    expect(nutrientProgress(today, 'carbohydrate_g')).toBeNull()
    // Protein's goal is a minimum: reaching it is good news, not a limit passed.
    const fed = day('-200', { protein_g: '40', fat_g: null, carbohydrate_g: null })
    expect(nutrientProgress(fed, 'protein_g')).toEqual({
      target: '40',
      share: 100,
      over: false,
      reached: true,
    })
    expect(nutrientProgress(day('0', null), 'protein_g')).toBeNull()
  })
})

describe('suggested meal kind', () => {
  const at = (hour: string) =>
    suggestedMealKind(new Date(`2026-10-03T${hour}:00:00Z`), 'Europe/Belgrade')
  it('follows the clock in the person’s zone', () => {
    // Belgrade is UTC+2 in October: 04 UTC is 06:00 there.
    expect([at('04'), at('11'), at('15'), at('21')]).toEqual([
      'breakfast',
      'lunch',
      'dinner',
      'snack',
    ])
    expect(at('02')).toBe('snack')
  })

  it('keeps breakfast until noon, as the assistant does', () => {
    // Local 05, 11, 12, 16 and 22: the edges, and 11 o'clock («до 12 дня») is still breakfast.
    expect([at('03'), at('09'), at('10'), at('14'), at('20')]).toEqual([
      'breakfast',
      'breakfast',
      'lunch',
      'dinner',
      'snack',
    ])
  })
})

describe('meal kind of a draft', () => {
  const now = new Date('2026-10-03T07:00:00Z')
  it('starts from the meal the draft belongs to, else from the day', () => {
    const draft = Draft.parse({
      id: 'd1',
      label: 'Суп',
      status: 'unresolved',
      missing_fields: ['quantity'],
      revision: 1,
      meal_kind: 'dinner',
    })
    expect(draftMealKind(draft, '2026-10-03', 'UTC', now)).toBe('dinner')
    // A draft without a kind (or from before drafts kept their meal) gets the clock's for today.
    const legacy = Draft.parse({ ...draft, meal_kind: undefined })
    expect(legacy.meal_kind).toBeUndefined()
    expect(draftMealKind(legacy, '2026-10-03', 'UTC', now)).toBe('breakfast')
    expect(draftMealKind({ meal_kind: null }, '2026-10-02', 'UTC', now)).toBeNull()
    expect(draftMealKind(null, '2026-10-03', 'UTC', now)).toBe('breakfast')
  })
})

describe('same amount', () => {
  it('compares decimal strings as numbers', () => {
    expect(sameAmount('150', '150.000')).toBe(true)
    expect(sameAmount('150', '150')).toBe(true)
    expect(sameAmount('150', '151')).toBe(false)
    expect(sameAmount(null, '150')).toBe(false)
    expect(sameAmount(null, null)).toBe(true)
  })
})

describe('shownName', () => {
  it('raises the first letter for the screen only', () => {
    expect(shownName('яйцо вкрутую')).toBe('Яйцо вкрутую')
    expect(shownName('Капучино')).toBe('Капучино')
    expect(shownName('')).toBe('')
  })
})
