import { z } from '@/core/zod'
import type { Day } from '@/sections/food/api'
import { StatsDay } from '@/sections/food/stats'

// Older range responses and cached Food statistics remain valid during an additive rollout.
export const DashboardNutritionDay = StatsDay.extend({ protein_complete: z.boolean().optional() })
export const DashboardNutritionRange = z.object({ days: z.array(DashboardNutritionDay) })
export type DashboardNutritionDay = z.infer<typeof DashboardNutritionDay>

export interface NutritionRow {
  date: string
  logged: boolean
  energy: string | null
  energyComplete: boolean
  protein: string | null
  proteinComplete?: boolean
  fat: string | null
  carbs: string | null
  completed: boolean
  limit: string | null
  minimum: string | null
}

export type NutritionTone = 'goal' | 'missed' | 'pending' | 'empty'
export type NutritionReason =
  | 'empty'
  | 'goalsMissing'
  | 'dayOpen'
  | 'energyUnknown'
  | 'bothGoals'
  | 'overLimit'
  | 'proteinLow'
  | 'proteinUnknown'

/** Numeric coordinates only; statuses compare the original decimals before rounding. */
export function nutritionNumber(value: string | null | undefined): number | null {
  if (value == null || !decimal(value)) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

function decimal(value: string) {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value)
  if (!match) return null
  const fraction = match[3] ?? ''
  const exponent = Number(match[4] ?? 0)
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 1000 || fraction.length > 1000)
    return null
  return {
    coefficient: BigInt(`${match[1] === '-' ? '-' : ''}${match[2]}${fraction}`),
    scale: fraction.length - exponent,
  }
}

/** Exact comparison also accepts scientific notation emitted by Python Decimal. */
export function compareNutritionDecimals(a: string | null, b: string | null): number | null {
  if (a == null || b == null || nutritionNumber(a) == null || nutritionNumber(b) == null)
    return null
  const left = decimal(a)!,
    right = decimal(b)!
  const scale = Math.max(left.scale, right.scale)
  const difference =
    left.coefficient * 10n ** BigInt(scale - left.scale) -
    right.coefficient * 10n ** BigInt(scale - right.scale)
  return difference === 0n ? 0 : difference < 0n ? -1 : 1
}

export function rangeNutritionRow(day: DashboardNutritionDay): NutritionRow {
  return {
    date: day.date,
    logged: day.logged,
    energy: day.energy_kcal,
    energyComplete: day.energy_complete,
    protein: day.protein_g,
    proteinComplete: day.protein_complete,
    fat: day.fat_g,
    carbs: day.carbohydrate_g,
    completed: day.completed,
    limit: day.target,
    minimum: day.macro_targets?.protein_g ?? null,
  }
}

export function selectedNutritionRow(day: Day): NutritionRow {
  const logged = day.entries.length > 0
  const energyComplete = day.totals.energy_kcal?.complete ?? false
  return {
    date: day.date,
    logged,
    energy: logged ? (day.totals.energy_kcal?.known ?? null) : null,
    energyComplete,
    protein: logged ? (day.totals.protein_g?.known ?? null) : null,
    proteinComplete: day.totals.protein_g?.complete,
    fat: logged ? (day.totals.fat_g?.known ?? null) : null,
    carbs: logged ? (day.totals.carbohydrate_g?.known ?? null) : null,
    completed: day.completed && logged && !day.drafts.length && energyComplete,
    limit: day.goal?.calorie_target ?? null,
    minimum: day.goal?.macro_targets?.protein_g ?? null,
  }
}

export function nutritionStatus(row: NutritionRow): {
  tone: NutritionTone
  reason: NutritionReason
} {
  const result = (tone: NutritionTone, reason: NutritionReason) => ({ tone, reason })
  if (!row.logged) return result('empty', 'empty')
  if (nutritionNumber(row.limit) == null || nutritionNumber(row.minimum) == null)
    return result('pending', 'goalsMissing')
  if (!row.completed) return result('pending', 'dayOpen')
  const energy = compareNutritionDecimals(row.energy, row.limit)
  if (!row.energyComplete || energy == null) return result('pending', 'energyUnknown')
  const protein = compareNutritionDecimals(row.protein, row.minimum)
  if (energy <= 0 && protein != null && protein >= 0) return result('goal', 'bothGoals')
  if (energy > 0) return result('missed', 'overLimit')
  if (protein != null && protein < 0 && row.proteinComplete) return result('missed', 'proteinLow')
  return result('pending', 'proteinUnknown')
}

export function nutritionCeiling(rows: readonly NutritionRow[]): number {
  const largest = Math.max(
    0,
    ...rows.flatMap((row) => [nutritionNumber(row.energy) ?? 0, nutritionNumber(row.limit) ?? 0]),
  )
  return Math.max(500, Math.ceil((largest * 1.12) / 500) * 500)
}
