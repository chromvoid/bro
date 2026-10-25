/**
 * Daily limits of this installation. Counters reset at 00:00 UTC; the user sees that moment in
 * their own zone. Dictation is counted in milliseconds and shown in minutes.
 */
import { dayIn, dayLabel, timeIn } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { z } from '@/core/zod'

const Count = z
  .union([z.string(), z.number()])
  .nullable()
  .transform((value) => (value === null ? null : Number(value)))

export const Limit = z.looseObject({ kind: z.string(), maximum: Count, used: Count })
export type Limit = z.infer<typeof Limit>
export const Limits = z.looseObject({ limits: z.array(Limit), timezone: z.string().nullish() })

/** The next 00:00 UTC after [now]. */
export function nextReset(now: Date = new Date()): Date {
  const reset = new Date(now)
  reset.setUTCHours(24, 0, 0, 0)
  return reset
}

/** «сегодня в 02:00» / «завтра в 01:00» in the account's zone. */
export function resetLabel(timeZone: string, now: Date = new Date()): string {
  const reset = nextReset(now)
  const day = dayLabel(dayIn(timeZone, reset), dayIn(timeZone, now)).toLowerCase()
  return `${day} в ${timeIn(timeZone, reset)}`
}

export interface Usage {
  used: string
  maximum: string | null
  /** 0–100 when there is a maximum. */
  share: number | null
  exhausted: boolean
}

export function usageOf(limit: Limit): Usage {
  const minutes = limit.kind === 'dictation_ms'
  const shown = (value: number) =>
    minutes ? formatAmount(Math.ceil(value / 6_000) / 10, 1) : formatAmount(value, 0)
  const used = limit.used ?? 0
  const maximum = limit.maximum
  return {
    used: shown(used),
    maximum: maximum === null ? null : shown(maximum),
    share: maximum ? Math.min(100, (used / maximum) * 100) : null,
    exhausted: maximum !== null && used >= maximum,
  }
}

/**
 * Whether a refusal (429) came from the user's daily limit of [kind] rather than a busy server:
 * only the counters can tell, the refusal itself is plain text.
 */
export async function dailyLimitReached(
  kind: string,
  read: () => Promise<unknown>,
): Promise<boolean> {
  try {
    const limit = Limits.parse(await read()).limits.find((item) => item.kind === kind)
    return limit ? usageOf(limit).exhausted : false
  } catch {
    return false
  }
}
