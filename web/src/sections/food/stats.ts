/**
 * Week and month statistics of the diary: the period around a day and what its days add up to.
 * Averages count only days with entries — an empty day is not a day of eating nothing — and the
 * screen says how many those were.
 */
import { shiftDay, type IsoDay } from '@/core/dates'
import { z } from '@/core/zod'
import { MEAL_KINDS, type MealKind } from './api'
import { weekStart } from './week'

export const StatsDay = z.looseObject({
  date: z.string(),
  logged: z.boolean(),
  energy_kcal: z.string(),
  energy_complete: z.boolean(),
  protein_g: z.string(),
  /** Added by range after its initial release; absence cannot prove protein is fully counted. */
  protein_complete: z.boolean().optional(),
  fat_g: z.string(),
  carbohydrate_g: z.string(),
  completed: z.boolean(),
  target: z.string().nullable(),
  macro_targets: z.record(z.string(), z.string()).nullable(),
  kcal_by_kind: z.record(z.string(), z.string()),
})
export type StatsDay = z.infer<typeof StatsDay>
export const Stats = z.object({ days: z.array(StatsDay) })

export type PeriodKind = 'week' | 'month'
export interface Period {
  kind: PeriodKind
  start: IsoDay
  end: IsoDay
}

/** The week (Monday to Sunday) or the calendar month that holds [day]. */
export function periodOf(kind: PeriodKind, day: IsoDay): Period {
  if (kind === 'week') {
    const start = weekStart(day)
    return { kind, start, end: shiftDay(start, 6) }
  }
  const [year, month] = day.split('-').map(Number) as [number, number]
  const start = `${year}-${String(month).padStart(2, '0')}-01`
  const end = new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10)
  return { kind, start, end }
}

/** The period before (-1) or after (+1) [period], of the same kind. */
export function shiftPeriod(period: Period, step: -1 | 1): Period {
  return periodOf(period.kind, step < 0 ? shiftDay(period.start, -1) : shiftDay(period.end, 1))
}

export const MACROS = ['protein_g', 'fat_g', 'carbohydrate_g'] as const
export type Macro = (typeof MACROS)[number]
export type KindKey = MealKind | 'none'
export const KIND_KEYS: KindKey[] = [...MEAL_KINDS, 'none']

export interface Summary {
  days: number
  logged: number
  completed: number
  /** Logged days with a goal whose energy came within 10 % of it. */
  nearGoal: number
  /** Logged days with a goal that passed it by more than 10 %. */
  overGoal: number
  /** Logged days with a goal that stayed more than 10 % under it. */
  underGoal: number
  /** Logged days that had a calorie goal. */
  withGoal: number
  /** Per logged day; null without logged days. */
  kcal: number | null
  /** The average of the goals in force on the logged days; null without goals. */
  target: number | null
  macros: Record<Macro, { average: number | null; target: number | null }>
  /** Each kind's share of the logged energy, in the order of a day; kinds with none left out. */
  kinds: Array<{ kind: KindKey; kcal: number; share: number }>
}

const mean = (values: number[]) =>
  values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null

export function summarize(days: StatsDay[]): Summary {
  const logged = days.filter((day) => day.logged)
  const goals = logged.filter((day) => day.target !== null)
  const off = (day: StatsDay) => (Number(day.energy_kcal) - Number(day.target)) / Number(day.target)
  const near = goals.filter((day) => Math.abs(off(day)) <= 0.1)
  const macros = Object.fromEntries(
    MACROS.map((macro) => {
      const targets = logged
        .map((day) => day.macro_targets?.[macro])
        .filter((value): value is string => value !== undefined && value !== null)
      return [
        macro,
        {
          average: mean(logged.map((day) => Number(day[macro]))),
          target: mean(targets.map(Number)),
        },
      ]
    }),
  ) as Summary['macros']
  const byKind = new Map<KindKey, number>()
  for (const day of logged)
    for (const [kind, kcal] of Object.entries(day.kcal_by_kind))
      byKind.set(kind as KindKey, (byKind.get(kind as KindKey) ?? 0) + Number(kcal))
  const total = [...byKind.values()].reduce((sum, value) => sum + value, 0)
  return {
    days: days.length,
    logged: logged.length,
    completed: days.filter((day) => day.completed).length,
    nearGoal: near.length,
    overGoal: goals.filter((day) => off(day) > 0.1).length,
    underGoal: goals.filter((day) => off(day) < -0.1).length,
    withGoal: goals.length,
    kcal: mean(logged.map((day) => Number(day.energy_kcal))),
    target: mean(goals.map((day) => Number(day.target))),
    macros,
    kinds: total
      ? KIND_KEYS.filter((kind) => (byKind.get(kind) ?? 0) > 0).map((kind) => ({
          kind,
          kcal: byKind.get(kind)!,
          share: byKind.get(kind)! / total,
        }))
      : [],
  }
}
