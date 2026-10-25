import { describe, expect, it } from 'vitest'
import { HistoryEvent, TaskHistoryPage, historyItems, historyLines, historyPeople } from './history'

const event = (changes: Record<string, unknown> = {}) => ({
  id: 'event',
  created_at: '2026-10-08T11:30:00+00:00',
  kind: 'updated',
  actor: { id: 'actor', name: 'Анна' },
  changes,
  reason: null,
  task_revision: 3,
})
const t = (key: string) => key

describe('compact task history contract', () => {
  it('allows scalar diffs and rejects authored content or private resource names', () => {
    const parsed = HistoryEvent.parse(
      event({
        state: { before: 'todo', after: 'done' },
        planned_on: { before: null, after: '2026-10-12' },
        due_on: { before: '2026-10-09', after: null },
        priority: { before: 2, after: 1 },
        assigned_to: { before: 'former', after: 'next' },
        title: {},
        checklist: {},
        list_id: {},
      }),
    )
    expect(parsed.changes.title).toEqual({})
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
      'deleted_at',
    ]) {
      expect(
        HistoryEvent.safeParse(event({ [field]: { before: 'PRIVATE', after: 'PRIVATE' } })).success,
      ).toBe(false)
    }
    expect(HistoryEvent.safeParse(event({ receipt: {} })).success).toBe(false)
    expect(HistoryEvent.safeParse(event({ priority: { before: 0, after: 4 } })).success).toBe(false)
    expect(
      HistoryEvent.safeParse(event({ due_on: { before: '2026-02-30', after: null } })).success,
    ).toBe(false)
    expect(HistoryEvent.safeParse({ ...event(), created_at: 'not a timestamp' }).success).toBe(
      false,
    )
  })

  it('does not retain extra receipt fields returned alongside an event', () => {
    const parsed = TaskHistoryPage.parse({
      items: [{ ...event(), receipt: { old_title: 'PRIVATE' } }],
      people: [],
      next_cursor: null,
      receipts: ['PRIVATE'],
    })
    expect(JSON.stringify(parsed)).not.toContain('PRIVATE')
  })

  it('accepts an assistant result event with or without a task state change', () => {
    for (const changes of [{}, { state: { before: 'in_progress', after: 'done' } }]) {
      const parsed = HistoryEvent.parse({ ...event(changes), kind: 'result_accepted' })
      expect(parsed.kind).toBe('result_accepted')
    }
  })

  it('accepts imported assignments without inventing their former task revision', () => {
    const parsed = TaskHistoryPage.parse({
      items: [
        {
          ...event({ assigned_to: { before: 'former', after: null } }),
          kind: 'unassigned',
          task_revision: null,
          reason: 'member_left',
        },
      ],
      people: [{ id: 'former', name: 'Иван' }],
      next_cursor: null,
    })
    expect(parsed.items[0]?.task_revision).toBeNull()
    expect(historyLines(parsed.items[0]!, historyPeople([parsed]), t)[0]).toMatchObject({
      before: 'Иван',
      after: 'tasks.history.unassigned',
    })
  })

  it('accepts an anonymized account-deletion unassignment without retaining the former name', () => {
    const parsed = HistoryEvent.parse({
      ...event({ assigned_to: { before: 'deleted-user', after: null } }),
      kind: 'unassigned',
      actor: { id: 'deleted-user', name: 'Удалённый пользователь' },
      reason: 'account_deleted',
    })
    expect(parsed.reason).toBe('account_deleted')
    expect(
      historyLines(parsed, new Map([['deleted-user', 'Удалённый пользователь']]), t)[0],
    ).toMatchObject({ before: 'Удалённый пользователь', after: 'tasks.history.unassigned' })
  })

  it('renders typed values with names and records only facts for content changes', () => {
    const parsed = HistoryEvent.parse(
      event({
        state: { before: 'todo', after: 'in_progress' },
        due_on: { before: null, after: '2026-10-12' },
        priority: { before: 2, after: 1 },
        assigned_to: { before: 'former', after: null },
        title: {},
        description: {},
        title_images: {},
        list_id: {},
        deleted_at: {},
      }),
    )
    const lines = historyLines(parsed, new Map([['former', 'Иван']]), t)
    expect(lines[0]).toEqual({
      field: 'state',
      kind: 'value',
      label: 'tasks.state',
      before: 'tasks.states.todo',
      after: 'tasks.states.in_progress',
    })
    expect(lines.find((line) => line.field === 'due_on')).toMatchObject({
      before: 'tasks.history.notSet',
      after: expect.stringContaining('2026'),
    })
    expect(lines.find((line) => line.field === 'assigned_to')).toMatchObject({
      before: 'Иван',
      after: 'tasks.history.unassigned',
    })
    expect(lines.filter((line) => line.kind === 'marker')).toEqual(
      ['title', 'description', 'title_images', 'list_id'].map((field) => ({
        field,
        kind: 'marker',
        text: `tasks.history.fields.${field}`,
      })),
    )
  })

  it('never displays a missing assignee UUID as a person name', () => {
    const lines = historyLines(
      HistoryEvent.parse(event({ assigned_to: { before: 'missing-person', after: null } })),
      new Map(),
      t,
    )
    expect(lines[0]).toMatchObject({ before: 'tasks.history.unknownPerson' })
    expect(JSON.stringify(lines)).not.toContain('missing-person')
  })

  it('combines page people and removes repeated events without reordering them', () => {
    const newest = TaskHistoryPage.parse({
      items: [{ ...event(), id: 'newest' }],
      people: [{ id: 'former', name: 'Current name' }],
      next_cursor: 'cursor',
    })
    const older = TaskHistoryPage.parse({
      items: [
        { ...event(), id: 'newest' },
        { ...event(), id: 'oldest' },
      ],
      people: [
        { id: 'former', name: 'Old name' },
        { id: 'next', name: 'Next person' },
      ],
      next_cursor: null,
    })
    expect(historyItems([newest, older]).map((item) => item.id)).toEqual(['newest', 'oldest'])
    expect(historyPeople([newest, older])).toEqual(
      new Map([
        ['former', 'Current name'],
        ['next', 'Next person'],
      ]),
    )
  })
})
