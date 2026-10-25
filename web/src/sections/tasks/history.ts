import { isIsoDay } from '@/core/dates'
import { z } from '@/core/zod'

const Change = <T extends z.ZodType>(value: T) =>
  z.strictObject({ before: value.nullable(), after: value.nullable() })
const Marker = z.strictObject({})
const Day = z.string().refine(isIsoDay)

/** History deliberately cannot carry previous authored text, receipts or private list names. */
export const HistoryEvent = z.object({
  id: z.string(),
  created_at: z.iso.datetime({ offset: true }),
  kind: z.enum([
    'created',
    'updated',
    'state_changed',
    'assigned',
    'unassigned',
    'deleted',
    'restored',
    'moved',
    'reordered',
    'parent_changed',
    'result_accepted',
  ]),
  actor: z.object({ id: z.string(), name: z.string() }),
  changes: z.strictObject({
    state: Change(z.enum(['todo', 'in_progress', 'waiting', 'done'])).optional(),
    planned_on: Change(Day).optional(),
    due_on: Change(Day).optional(),
    priority: Change(z.number().int().min(1).max(3)).optional(),
    assigned_to: Change(z.string()).optional(),
    title: Marker.optional(),
    description: Marker.optional(),
    checklist: Marker.optional(),
    waiting_reason: Marker.optional(),
    title_images: Marker.optional(),
    description_images: Marker.optional(),
    list_id: Marker.optional(),
    parent_id: Marker.optional(),
    position: Marker.optional(),
    deleted_at: Marker.optional(),
  }),
  reason: z.enum(['member_left', 'account_deleted']).nullable(),
  // Imported assignment events predate task revision tracking in the audit.
  task_revision: z.number().int().positive().nullable(),
})
export type HistoryEvent = z.infer<typeof HistoryEvent>

export const TaskHistoryPage = z.object({
  items: z.array(HistoryEvent),
  people: z.array(z.object({ id: z.string(), name: z.string() })),
  next_cursor: z.string().nullable(),
})
export type TaskHistoryPage = z.infer<typeof TaskHistoryPage>

type Translate = (key: string) => string
export type HistoryLine =
  | { field: string; kind: 'value'; label: string; before: string; after: string }
  | { field: string; kind: 'marker'; text: string }

const dateFormat = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
})

/** Only an explicit allowlist determines what an audit row can render. */
export function historyLines(
  event: HistoryEvent,
  people: ReadonlyMap<string, string>,
  t: Translate,
): HistoryLine[] {
  const lines: HistoryLine[] = []
  const fields = ['state', 'planned_on', 'due_on', 'priority', 'assigned_to'] as const
  const labels = {
    state: 'tasks.state',
    planned_on: 'tasks.plannedOn',
    due_on: 'tasks.dueOn',
    priority: 'tasks.priority',
    assigned_to: 'circle.assignee',
  }
  for (const field of fields) {
    const change = event.changes[field]
    if (!change) continue
    const label = (value: string | number | null) => {
      if (value === null)
        return t(field === 'assigned_to' ? 'tasks.history.unassigned' : 'tasks.history.notSet')
      if (field === 'state') return t(`tasks.states.${value}`)
      if (field === 'priority') return t(`tasks.priorities.${value}`)
      if (field === 'assigned_to')
        return people.get(String(value)) || t('tasks.history.unknownPerson')
      return dateFormat.format(new Date(`${value}T00:00:00Z`))
    }
    lines.push({
      field,
      kind: 'value',
      label: t(labels[field]),
      before: label(change.before),
      after: label(change.after),
    })
  }
  for (const field of [
    'title',
    'description',
    'checklist',
    'waiting_reason',
    'title_images',
    'description_images',
    'list_id',
    'parent_id',
    'position',
  ] as const) {
    if (event.changes[field])
      lines.push({ field, kind: 'marker', text: t(`tasks.history.fields.${field}`) })
  }
  // Deletion/restoration already has its own action heading; its timestamp is never a diff.
  return lines
}

/** A repeated event at a page boundary is displayed once, in the server's newest-first order. */
export function historyItems(pages: readonly TaskHistoryPage[]): HistoryEvent[] {
  const seen = new Set<string>()
  return pages.flatMap((page) =>
    page.items.filter((event) => {
      if (seen.has(event.id)) return false
      seen.add(event.id)
      return true
    }),
  )
}

export function historyPeople(pages: readonly TaskHistoryPage[]): Map<string, string> {
  const people = new Map<string, string>()
  for (const page of pages)
    for (const person of page.people) if (!people.has(person.id)) people.set(person.id, person.name)
  return people
}
