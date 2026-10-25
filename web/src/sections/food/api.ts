/**
 * The nutrition diary API. Numbers stay decimal strings from the server; unknown nutrients stay
 * null. Writes go through the submission journal as ledger operations with expected revisions.
 */
import { dayIn, hourIn } from '@/core/dates'
import { z } from '@/core/zod'

const Decimal = z.string().nullable()

export const Nutrients = z.looseObject({
  energy_kcal: Decimal.optional(),
  protein_g: Decimal.optional(),
  fat_g: Decimal.optional(),
  carbohydrate_g: Decimal.optional(),
  fiber_g: Decimal.optional(),
})
export type Nutrients = z.infer<typeof Nutrients>

/**
 * A meal's kind: chosen in a panel when logging, or by the assistant from the person's words, the
 * foods and the time; either can be corrected. Meals logged before kinds existed have none.
 */
export const MEAL_KINDS = ['breakfast', 'lunch', 'dinner', 'snack'] as const
export type MealKind = (typeof MEAL_KINDS)[number]

/**
 * The kind the clock suggests for food logged now, in the person's zone: from 5 to 12 breakfast,
 * to 16 lunch, to 22 dinner, otherwise a snack — the same hours the assistant infers a meal from.
 * Only for today: another day gets no suggestion.
 */
export function suggestedMealKind(now: Date, zone: string): MealKind {
  const hour = hourIn(zone, now)
  if (hour >= 5 && hour < 12) return 'breakfast'
  if (hour >= 12 && hour < 16) return 'lunch'
  if (hour >= 16 && hour < 22) return 'dinner'
  return 'snack'
}

/** The kind a meal logged for `day` starts with: the clock's for today, none for another day. */
export function defaultMealKind(day: string, zone: string, now = new Date()): MealKind | null {
  return day === dayIn(zone, now) ? suggestedMealKind(now, zone) : null
}

/**
 * The kind completing a draft starts with: the meal the draft already belongs to (the assistant
 * chose it with the food that was logged), otherwise the day's default.
 */
export function draftMealKind(
  draft: Pick<Draft, 'meal_kind'> | null | undefined,
  day: string,
  zone: string,
  now = new Date(),
): MealKind | null {
  return draft?.meal_kind ?? defaultMealKind(day, zone, now)
}

/** What changing meals' kind returns: each meal with its kind before, which undoes it. */
export const MealKindReceipt = z.looseObject({
  kind: z.enum(MEAL_KINDS).nullable(),
  meals: z.array(
    z.looseObject({ meal_id: z.string(), previous_kind: z.enum(MEAL_KINDS).nullable() }),
  ),
})

export const Entry = z.looseObject({
  id: z.string(),
  revision: z.number(),
  dish_id: z.string().nullish(),
  food_id: z.string().nullish(),
  snapshot_id: z.string().nullish(),
  quantity: z.string(),
  unit: z.string(),
  local_date: z.string(),
  logged_at: z.string().nullish(),
  /**
   * The meal the entry belongs to: entries logged in one operation share it, and a completed
   * draft or a moved entry joins an existing one later.
   */
  meal_id: z.string().nullish(),
  meal_kind: z.enum(MEAL_KINDS).nullish(),
  estimated: z.boolean().nullish(),
  estimation_evidence: z.string().nullish(),
  name: z.string().nullish(),
  name_ru: z.string().nullish(),
  source_provider: z.string().nullish(),
  basis_unit: z.string().nullish(),
  nutrients: Nutrients.nullish(),
})
export type Entry = z.infer<typeof Entry>

export const Dish = z.looseObject({
  id: z.string(),
  kind: z.literal('dish'),
  name: z.string(),
  quantity: z.string(),
  unit: z.string(),
  revision: z.number(),
  local_date: z.string().nullish(),
  components: z.array(Entry),
  nutrients: Nutrients.nullish(),
  estimated: z.boolean().nullish(),
})
export type Dish = z.infer<typeof Dish>

const Total = z.object({ known: z.string(), complete: z.boolean() })
export type Total = z.infer<typeof Total>

export const Draft = z.looseObject({
  id: z.string(),
  plan_id: z.string().nullish(),
  label: z.string(),
  status: z.string(),
  missing_fields: z.array(z.string()),
  revision: z.number(),
  /** The kind of the meal the draft belongs to; null when it has none or was drafted before. */
  meal_kind: z.enum(MEAL_KINDS).nullish(),
})
export type Draft = z.infer<typeof Draft>

export const Goal = z.looseObject({
  id: z.string(),
  calorie_target: z.string(),
  effective_local_date: z.string(),
  revision: z.number(),
  macro_targets: z.record(z.string(), z.string().nullable()).nullish(),
})

export const Day = z.looseObject({
  day_revision: z.number(),
  completed: z.boolean(),
  date: z.string(),
  entries: z.array(Entry),
  items: z.array(z.union([Dish, Entry])),
  totals: z.record(z.string(), Total),
  goal: Goal.nullable(),
  remaining_kcal: z.string().nullable(),
  remaining_complete: z.boolean(),
  drafts: z.array(Draft),
})
export type Day = z.infer<typeof Day>

const Portion = z.looseObject({
  id: z.string(),
  name: z.string(),
  quantity: z.string(),
  unit: z.string(),
})
export const Food = z.looseObject({
  food_id: z.string(),
  snapshot_id: z.string(),
  name: z.string(),
  name_ru: z.string().nullish(),
  common_ru: z.string().nullish(),
  brand: z.string().nullish(),
  personal: z.boolean().nullish(),
  source_provider: z.string().nullish(),
  basis_unit: z.string(),
  basis_quantity: z.string(),
  energy_kcal: Decimal.optional(),
  protein_g: Decimal.optional(),
  fat_g: Decimal.optional(),
  carbohydrate_g: Decimal.optional(),
  fiber_g: Decimal.optional(),
  portions: z.array(Portion).nullish(),
  saved_portions: z.array(Portion.extend({ revision: z.number() })).nullish(),
})
export type Food = z.infer<typeof Food>
export const FoodResults = z.object({ foods: z.array(Food) })

export const Remembered = z.looseObject({
  food_id: z.string(),
  name: z.string(),
  brand: z.string().nullish(),
  favorite: z.boolean(),
  preference_revision: z.number(),
  uses_90_days: z.number().nullish(),
  last_eaten: z.string().nullish(),
  last_quantity: z.string().nullish(),
  last_unit: z.string().nullish(),
  /** The source to log it again with, and what a «+» needs to show and record it. */
  snapshot_id: z.string().nullish(),
  basis_quantity: z.string().nullish(),
  basis_unit: z.string().nullish(),
  energy_kcal: z.string().nullish(),
})
export type Remembered = z.infer<typeof Remembered>
export const Memory = z.object({ foods: z.array(Remembered) })

export const Composition = z.looseObject({
  id: z.string(),
  name: z.string(),
  components: z.array(z.looseObject({ label: z.string(), quantity: z.string(), unit: z.string() })),
})
export type Composition = z.infer<typeof Composition>
export const Compositions = z.object({ compositions: z.array(Composition) })

export const Estimate = z.looseObject({
  items: z.array(
    z.looseObject({ energy_kcal: Decimal.optional(), estimated: z.boolean().nullish() }),
  ),
  totals: z.record(z.string(), Total),
})

/** A ledger receipt; only the fields the page acts on are read. */
export const Receipt = z.looseObject({
  operation_id: z.string(),
  kind: z.string(),
  entry_id: z.string().nullish(),
  revision: z.number().nullish(),
  dish_id: z.string().nullish(),
  expected_revisions: z.record(z.string(), z.number().int().positive()).nullish(),
  applied_dishes: z
    .array(z.object({ dish_id: z.string(), revision: z.number().int().positive() }))
    .nullish(),
  undo_status: z.enum(['available', 'undone', 'changed', 'unsupported']).optional(),
  undone_operation_id: z.string().nullish(),
  name: z.string().nullish(),
  energy_kcal: Decimal.optional(),
  food_id: z.string().nullish(),
  snapshot_id: z.string().nullish(),
  status: z.string().nullish(),
  applied_entries: z
    .array(
      z.looseObject({
        entry_id: z.string(),
        revision: z.number().nullish(),
        name: z.string().nullish(),
        quantity: z.string().nullish(),
        unit: z.string().nullish(),
        energy_kcal: Decimal.optional(),
      }),
    )
    .nullish(),
})
export type Receipt = z.infer<typeof Receipt>

export const ItemTarget = z.object({
  kind: z.enum(['entry', 'dish']),
  id: z.string(),
  date: z.string(),
  revision: z.number().int().positive(),
  status: z.enum(['active', 'deleted']),
})

export const OPERATIONS = '/v1/nutrition/operations'
export const LOOKUP = '/v1/nutrition/operations/by-key/'
export const NUTRIENTS = ['protein_g', 'fat_g', 'carbohydrate_g', 'fiber_g'] as const

export const isDish = (item: Dish | Entry): item is Dish => (item as Dish).kind === 'dish'

/** How the day stands against its calorie goal; null without a goal or a known remainder. */
export function calorieBalance(day: Day): { kcal: number; over: boolean } | null {
  if (day.remaining_kcal === null) return null
  const remaining = Number(day.remaining_kcal)
  return { kcal: Math.abs(remaining), over: remaining < 0 }
}

/**
 * Protein's goal is a minimum to reach, as on «Личное» («Белок ниже минимума»); the other goals
 * are limits.
 */
const MINIMUM_GOALS: ReadonlySet<string> = new Set(['protein_g'])

/**
 * A nutrient against its goal: share 0–100 for the bar, whether a limit is passed (`over`) and
 * whether a minimum is reached (`reached`). What is known so far is a floor of the day's amount,
 * so either is certain once the known part gets there.
 */
export function nutrientProgress(
  day: Day,
  nutrient: (typeof NUTRIENTS)[number],
): { target: string; share: number; over: boolean; reached: boolean } | null {
  const target = day.goal?.macro_targets?.[nutrient]
  const known = Number(day.totals[nutrient]?.known ?? 0)
  if (!target || !(Number(target) > 0)) return null
  const minimum = MINIMUM_GOALS.has(nutrient)
  return {
    target,
    share: Math.min(100, (known / Number(target)) * 100),
    over: !minimum && known > Number(target),
    reached: minimum && known >= Number(target),
  }
}

/**
 * Whether two decimal amounts are the same number however they are written («150» and «150.0»):
 * the server refuses a change that changes nothing.
 */
export function sameAmount(a: string | null | undefined, b: string | null | undefined): boolean {
  return a === b || (a != null && b != null && Number(a) === Number(b))
}

export function entryName(entry: Entry): string {
  return entry.name_ru || entry.name || '—'
}

export function foodName(food: Pick<Food, 'name' | 'name_ru' | 'common_ru'>): string {
  return food.common_ru || food.name_ru || food.name
}

/**
 * A name as the screen shows it: with a capital first letter, whatever case its source gave it
 * («яйцо вкрутую» next to «Капучино»). What is stored and sent keeps the source's case.
 */
export function shownName(name: string): string {
  return name.charAt(0).toLocaleUpperCase('ru-RU') + name.slice(1)
}

const UNITS: Record<string, string> = { g: 'г', ml: 'мл', serving: 'порц.' }
export function unitLabel(unit: string): string {
  return UNITS[unit] ?? unit
}

/**
 * What undoing an operation must name: the current revision of every entry it touched,
 * taken from its receipt. Null when the receipt changed no entries (nothing to undo).
 */
export function undoRevisions(receipt: Receipt): Record<string, number> | null {
  const revisions: Record<string, number> = { ...receipt.expected_revisions }
  for (const dish of receipt.applied_dishes ?? []) revisions[dish.dish_id] = dish.revision
  if (receipt.dish_id && receipt.revision) revisions[receipt.dish_id] = receipt.revision
  for (const applied of receipt.applied_entries ?? [])
    if (applied.revision) revisions[applied.entry_id] = applied.revision
  if (receipt.entry_id && receipt.revision) revisions[receipt.entry_id] = receipt.revision
  return Object.keys(revisions).length ? revisions : null
}
