import { describe, expect, it } from 'vitest'
import { Task } from './api'
import { dateChip, taskDates } from './dates'

const today = '2026-10-08'
const task = (fields: Record<string, unknown>) =>
  Task.parse({ id: 't', title: 'Задача', status: 'open', revision: 1, ...fields })

describe('task dates', () => {
  it('tints only what needs attention', () => {
    expect(taskDates(task({ planned_on: '2026-10-07' }), today)).toEqual([
      { day: '2026-10-07', label: 'tasks.planned', tone: 'warning' },
    ])
    expect(taskDates(task({ planned_on: today }), today)[0]!.tone).toBe('muted')
    expect(taskDates(task({ due_on: '2026-10-11' }), today)[0]!.tone).toBe('warning')
    expect(taskDates(task({ due_on: '2026-10-12' }), today)[0]!.tone).toBe('muted')
    expect(taskDates(task({ due_on: '2026-10-07', overdue: true }), today)).toEqual([
      { day: '2026-10-07', label: 'tasks.overdue', tone: 'danger' },
    ])
  })

  it('puts the plan before the deadline and gives a board card the deadline', () => {
    const both = task({ planned_on: '2026-10-09', due_on: '2026-10-20' })
    expect(taskDates(both, today).map((date) => date.label)).toEqual(['tasks.planned', 'tasks.due'])
    expect(dateChip(both, today)?.label).toBe('tasks.due')
    expect(dateChip(task({ planned_on: '2026-10-09' }), today)?.label).toBe('tasks.planned')
  })

  it('shows no dates on a finished task', () => {
    const done = task({ status: 'done', due_on: '2026-10-01', overdue: false })
    expect(taskDates(done, today)).toEqual([])
    expect(dateChip(done, today)).toBeNull()
  })
})
