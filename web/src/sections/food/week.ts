/** The diary's week strip: Monday to Sunday around a day, and how tall each day's bar is. */
import { shiftDay, type IsoDay } from '@/core/dates'
import { z } from '@/core/zod'

export const WeekDay = z.looseObject({
  date: z.string(),
  energy_kcal: z.string(),
  completed: z.boolean(),
  target: z.string().nullable(),
})
export type WeekDay = z.infer<typeof WeekDay>
export const Week = z.object({ days: z.array(WeekDay) })

/** Monday of the week that holds [day] (weeks start on Monday in Russian calendars). */
export function weekStart(day: IsoDay): IsoDay {
  const weekday = new Date(`${day}T00:00:00Z`).getUTCDay()
  return shiftDay(day, -((weekday + 6) % 7))
}

/** Where a goal sits in its day's bar: the headroom above it shows a day past the goal by height. */
export const GOAL_SHARE = 80

export interface Bar {
  /** Filled height, 0–100. */
  share: number
  over: boolean
  /** Height of the goal mark, or null for a day without a goal. */
  goal: number | null
}

/**
 * A bar against its day's goal (the goal at GOAL_SHARE), or as a share of the week's largest day
 * without a goal. Any food shows at least a sliver; `over` marks a day past its goal.
 */
export function bar(day: WeekDay, largest: number): Bar {
  const kcal = Number(day.energy_kcal)
  const target = day.target ? Number(day.target) : null
  const goal = target ? GOAL_SHARE : null
  const scale = target ?? largest
  if (!kcal || !scale) return { share: 0, over: false, goal }
  return {
    share: Math.max(6, Math.min(100, (kcal / scale) * (goal ?? 100))),
    over: !!target && kcal > target,
    goal,
  }
}
