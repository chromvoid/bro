import { shiftDay, type IsoDay } from '@/core/dates'
import type { Task } from './api'

export type DateTone = 'danger' | 'warning' | 'muted'

export interface TaskDate {
  day: IsoDay
  /** Locale key of the line, «План: …», «Срок: …» or «Просрочено: …». */
  label: 'tasks.planned' | 'tasks.due' | 'tasks.overdue'
  tone: DateTone
}

/**
 * The dates an open task shows: its plan, then its deadline. Only the date carries a colour: a
 * passed deadline is red (the server's own `overdue`), a deadline three days off or less and a
 * plan that is already behind are amber. A finished task shows none.
 */
export function taskDates(task: Task, today: IsoDay): TaskDate[] {
  if (task.status === 'done') return []
  const dates: TaskDate[] = []
  if (task.planned_on)
    dates.push({
      day: task.planned_on,
      label: 'tasks.planned',
      tone: task.planned_on < today ? 'warning' : 'muted',
    })
  if (task.due_on)
    dates.push({
      day: task.due_on,
      label: task.overdue ? 'tasks.overdue' : 'tasks.due',
      tone: task.overdue ? 'danger' : task.due_on <= shiftDay(today, 3) ? 'warning' : 'muted',
    })
  return dates
}

/** The one date a board card has room for: the deadline when there is one, else the plan. */
export function dateChip(task: Task, today: IsoDay): TaskDate | null {
  return taskDates(task, today).at(-1) ?? null
}
