/** Challenge periods and state as the user sees them; the server alone scores the days. */
import { dayLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { i18n } from '@/i18n'
import type { Challenge } from './api'

export type ChallengeState = 'upcoming' | 'running' | 'finished'

/** By the calendar days of the challenge, in the viewer's zone. */
export function challengeState(challenge: Challenge, today: string): ChallengeState {
  if (today < challenge.start_date) return 'upcoming'
  if (today > challenge.end_date) return 'finished'
  return 'running'
}

/** «1 октября – 14 октября» with «Сегодня»/«Завтра» where they apply. */
export function periodLabel(challenge: Challenge, today: string): string {
  return `${dayLabel(challenge.start_date, today)} – ${dayLabel(challenge.end_date, today)}`
}

/** Joining closes when the challenge starts (the server decides by its start instant). */
export function joinOpen(challenge: Challenge, now: number = Date.now()): boolean {
  return now < Date.parse(challenge.starts_at)
}

/** «1 800–2 200 ккал»: the band a day must fall in, from the goal fixed at the start. */
export function band(target: string, tolerance: string): string | null {
  const goal = Number(target)
  const share = Number(tolerance)
  if (!Number.isFinite(goal) || !Number.isFinite(share)) return null
  const low = Math.round(goal * (1 - share / 100))
  const high = Math.round(goal * (1 + share / 100))
  return `${formatAmount(low, 0)}–${formatAmount(high, 0)} ${i18n.global.t('food.kcal')}`
}
