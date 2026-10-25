/**
 * The day's entries as meals for reading: what was logged together. Items of one meal (one
 * meal_id) always stay together, even logged hours apart: a draft completed later or food moved
 * to another meal joins the meal it belongs to. Items logged within 45 minutes of each other stay
 * together too (oats, then a banana with «+»). The time is when they were logged, not eaten, so a
 * kind («Завтрак») comes only from the meal itself: what the person chose when logging, or what
 * the assistant chose from their words, the foods and the time, which the person can correct.
 * Meals without one keep their time grouping and are never named after the clock.
 */
import { MEAL_KINDS, isDish, type Dish, type Entry, type MealKind } from './api'

export interface Meal {
  key: string
  /** When the first item was logged; null for entries without that time. */
  at: string | null
  items: Array<Dish | Entry>
  kcal: number
  /** False when an item's energy is unknown: the sum is a lower bound. */
  complete: boolean
  /** The logged meals behind it: changing its kind changes all of them. */
  mealIds: string[]
}

/** A day's meals of one kind, or those without a kind. */
export interface KindSection {
  kind: MealKind | null
  meals: Meal[]
  kcal: number
  complete: boolean
}

const GAP_MS = 45 * 60_000

function start(item: Dish | Entry): { at: number | null; meal: string | null } {
  const parts = isDish(item) ? item.components : [item]
  const times = parts
    .map((part) => (part.logged_at ? Date.parse(part.logged_at) : Number.NaN))
    .filter(Number.isFinite)
  return { at: times.length ? Math.min(...times) : null, meal: parts[0]?.meal_id ?? null }
}

export function meals(items: Array<Dish | Entry>): Meal[] {
  const ordered = items
    .map((item, index) => ({ item, index, ...start(item) }))
    .sort((a, b) => (a.at ?? Infinity) - (b.at ?? Infinity) || a.index - b.index)
  // Positions in `ordered` joined into groups, each rooted at its earliest item: an item joins
  // the one logged just before it within the window, and the first item of its meal anywhere.
  const parent = ordered.map((_, index) => index)
  const root = (index: number): number =>
    parent[index] === index ? index : (parent[index] = root(parent[index]!))
  const join = (a: number, b: number) => {
    const [first, second] = [root(a), root(b)].sort((x, y) => x - y)
    parent[second!] = first!
  }
  // Only an item that starts a meal (or has none) takes part in the window: a late member of a
  // meal (a draft completed hours later) joins its own meal and never pulls in a neighbour.
  const firstOfMeal = new Map<string, number>()
  let previousAt: number | null = null
  let lastStart: number | null = null
  ordered.forEach(({ at, meal }, index) => {
    const first = meal === null ? undefined : firstOfMeal.get(meal)
    if (first !== undefined) {
      join(first, index)
      return
    }
    if (meal !== null) firstOfMeal.set(meal, index)
    if (
      lastStart !== null &&
      ((at !== null && previousAt !== null && at - previousAt <= GAP_MS) ||
        (at === null && previousAt === null))
    )
      join(lastStart, index)
    lastStart = index
    previousAt = at ?? previousAt
  })
  const groups = new Map<number, Meal>()
  ordered.forEach(({ item, at, meal }, index) => {
    const key = root(index)
    let group = groups.get(key)
    if (!group) {
      group = {
        key: item.id,
        at: at === null ? null : new Date(at).toISOString(),
        items: [],
        kcal: 0,
        complete: true,
        mealIds: [],
      }
      groups.set(key, group)
    }
    group.items.push(item)
    if (meal !== null && !group.mealIds.includes(meal)) group.mealIds.push(meal)
    const kcal = item.nutrients?.energy_kcal
    if (kcal === null || kcal === undefined) group.complete = false
    else group.kcal += Number(kcal)
  })
  return [...groups.values()]
}

export function mealKindOf(item: Dish | Entry): MealKind | null {
  const part = isDish(item) ? item.components[0] : item
  return part?.meal_kind ?? null
}

/** The day by kind, in a day's order; meals without a kind come last, by time. */
export function byKind(items: Array<Dish | Entry>): KindSection[] {
  const sections: KindSection[] = []
  for (const kind of [...MEAL_KINDS, null]) {
    const own = meals(items.filter((item) => mealKindOf(item) === kind))
    if (!own.length) continue
    sections.push({
      kind,
      meals: own,
      kcal: own.reduce((sum, meal) => sum + meal.kcal, 0),
      complete: own.every((meal) => meal.complete),
    })
  }
  return sections
}
