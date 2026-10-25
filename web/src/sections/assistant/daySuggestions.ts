/**
 * Starting points above the composer, taken from the clock and the state of the day: a meal not
 * yet in the diary, today's tasks, the day's summary in the evening. Nothing here reads what the
 * person wrote; understanding that is the model's.
 */
import type { Day, MealKind } from '@/sections/food/api'
import { mealKindOf } from '@/sections/food/meals'

/** Half an hour without a message makes the next visit a fresh start. */
export const QUIET_MS = 30 * 60_000
const NOON = 12
const EVENING = 20

/** Nothing written for a while: the conversation is not in the middle of something. */
export function quiet(lastMessageAt: string | undefined, now: Date): boolean {
  return !lastMessageAt || now.getTime() - Date.parse(lastMessageAt) >= QUIET_MS
}

export type SuggestionKey = 'breakfast' | 'lunch' | 'dinner' | 'dayAhead' | 'dayLeft' | 'summary'

export interface Suggestion {
  key: SuggestionKey
  /** `ask` sends the question as it reads; `start` begins a message the person finishes. */
  mode: 'ask' | 'start'
}

export interface DayState {
  /** The hour on the person's clock, 0 to 23. */
  hour: number
  /** The meal the clock suggests (suggestedMealKind). */
  meal: MealKind
  /** Whether that meal is in today's diary; null when unknown or not the person's to record. */
  mealRecorded: boolean | null
  /** Whether anything is in today's diary; null when unknown or not readable. */
  foodRecorded: boolean | null
  /** Whether today has open tasks, overdue ones included; null when unknown or not readable. */
  tasksToday: boolean | null
}

/** The day's suggestions, the most useful first. */
export function daySuggestions(day: DayState): Suggestion[] {
  const suggestions: Suggestion[] = []
  // A snack has no hour of its own: the night suggests no meal.
  if (day.meal !== 'snack' && day.mealRecorded === false)
    suggestions.push({ key: day.meal, mode: 'start' })
  if (day.tasksToday)
    suggestions.push({ key: day.hour < NOON ? 'dayAhead' : 'dayLeft', mode: 'ask' })
  if (day.hour >= EVENING && day.foodRecorded) suggestions.push({ key: 'summary', mode: 'ask' })
  return suggestions
}

/** Whether the diary holds the meal: logged food or a draft waiting to be completed. */
export function mealRecorded(day: Pick<Day, 'items' | 'drafts'>, kind: MealKind): boolean {
  return (
    day.items.some((item) => mealKindOf(item) === kind) ||
    day.drafts.some((draft) => draft.meal_kind === kind)
  )
}

/** Whether the diary holds anything for the day. */
export function foodRecorded(day: Pick<Day, 'items' | 'drafts'>): boolean {
  return day.items.length > 0 || day.drafts.length > 0
}
