/**
 * What the assistant told this account on its own (docs/notifications-spec.md): timers that went
 * off, reminders, the installation's problems for administrators. Only known fields are read.
 */
import { dayIn, shiftDay, zonedInstant } from '@/core/dates'
import { z } from '@/core/zod'

export const Notification = z.looseObject({
  id: z.string(),
  source: z.string(),
  topic: z.string(),
  severity: z.enum(['info', 'important', 'urgent']),
  title: z.string(),
  body: z.string(),
  link: z.string().nullable(),
  state: z.enum(['active', 'resolved']),
  resolution: z.string().nullable(),
  count: z.number(),
  updated_at: z.string(),
  read_at: z.string().nullable(),
  resolved_at: z.string().nullish(),
  snoozed_until: z.string().nullable(),
  unread: z.boolean(),
})
export type Notification = z.infer<typeof Notification>

export const NotificationList = z.object({
  notifications: z.array(Notification),
  unread: z.number(),
  before: z.string().nullable(),
})
export type NotificationList = z.infer<typeof NotificationList>

/** Only a path of this app: a notification never sends anyone to another site. */
export function appLink(link: string | null): string | null {
  return link && /^\/(?!\/)[A-Za-z0-9/_-]*$/.test(link) ? link : null
}

/** Important and urgent ones also speak in the conversation and on the Today screen. */
export function isLoud(notification: Notification): boolean {
  return notification.severity !== 'info'
}

/**
 * A line of its own in the conversation: loud and still open. A solved problem leaves the chat,
 * so the morning does not open on «Бэкап не удался»; it stays under «Решённые» on its page.
 */
export function speaksInChat(notification: Notification): boolean {
  return isLoud(notification) && notification.state === 'active'
}

/** A problem still waiting for the person: loud, unread and not solved meanwhile. */
export function isOpenProblem(notification: Notification): boolean {
  return isLoud(notification) && notification.unread && notification.state === 'active'
}

/**
 * Notifications that became news since the list was last seen: new ones and ones raised again
 * (their `updated_at` moved), unread. `seen` is updated in place; the first call only fills it,
 * so what was already waiting when the app opened is counted on the bell, not shown again.
 */
export function freshNotifications(
  seen: Map<string, string> | null,
  list: Notification[],
): { seen: Map<string, string>; fresh: Notification[] } {
  const known = seen ?? new Map<string, string>()
  const fresh: Notification[] = []
  for (const notification of list) {
    if (known.get(notification.id) === notification.updated_at) continue
    known.set(notification.id, notification.updated_at)
    if (seen && notification.unread) fresh.push(notification)
  }
  return { seen: known, fresh }
}

/** 08:00 of the next morning in the account's zone: «До утра». */
export function nextMorning(timeZone: string, now: Date = new Date()): string | null {
  return zonedInstant(`${shiftDay(dayIn(timeZone, now), 1)}T08:00`, timeZone)
}
