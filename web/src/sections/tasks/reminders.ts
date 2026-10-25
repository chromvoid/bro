/** When a task's reminder can come, offered as one tap each (docs/notifications-spec.md). */
import { dayIn, shiftDay, zonedInstant } from '@/core/dates'

export interface ReminderPreset {
  key: 'hour' | 'evening' | 'morning'
  at: string
}

/** Closer than this to the evening there is no point offering «this evening». */
const EVENING_MARGIN = 30 * 60_000

/**
 * In an hour; this evening at 19:00 while it is comfortably ahead; tomorrow at 9:00 — in the
 * person's zone, as moments with an offset the server accepts.
 */
export function reminderPresets(now: Date, zone: string): ReminderPreset[] {
  const today = dayIn(zone, now)
  const presets: ReminderPreset[] = [
    { key: 'hour', at: new Date(now.getTime() + 60 * 60_000).toISOString() },
  ]
  const evening = zonedInstant(`${today}T19:00`, zone)
  if (evening && Date.parse(evening) - now.getTime() > EVENING_MARGIN)
    presets.push({ key: 'evening', at: evening })
  const morning = zonedInstant(`${shiftDay(today, 1)}T09:00`, zone)
  if (morning) presets.push({ key: 'morning', at: morning })
  return presets
}
