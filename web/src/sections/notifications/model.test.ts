import { describe, expect, it } from 'vitest'
import {
  Notification,
  appLink,
  freshNotifications,
  isLoud,
  isOpenProblem,
  nextMorning,
  speaksInChat,
} from './model'

function notification(fields: Partial<Notification> = {}): Notification {
  return Notification.parse({
    id: 'n1',
    source: 'timers',
    topic: 'timers.fired',
    severity: 'urgent',
    title: 'Таймер «чай» на 5 минут сработал',
    body: '',
    link: '/timers',
    state: 'active',
    resolution: null,
    count: 1,
    updated_at: '2026-09-27T09:00:00+00:00',
    read_at: null,
    snoozed_until: null,
    unread: true,
    ...fields,
  })
}

describe('notifications', () => {
  it('shows nothing for what was already waiting, then each new or raised one once', () => {
    const waiting = notification()
    const first = freshNotifications(null, [waiting])
    expect(first.fresh).toEqual([])
    const arrived = notification({ id: 'n2', updated_at: '2026-09-27T09:05:00+00:00' })
    const second = freshNotifications(first.seen, [arrived, waiting])
    expect(second.fresh.map((item) => item.id)).toEqual(['n2'])
    expect(freshNotifications(second.seen, [arrived, waiting]).fresh).toEqual([])
    // Raised again after a snooze: the same id with a new time is news.
    const raised = notification({ updated_at: '2026-09-27T10:00:00+00:00' })
    expect(freshNotifications(second.seen, [raised, arrived]).fresh.map((item) => item.id)).toEqual(
      ['n1'],
    )
    // Read elsewhere or resolved: remembered, not shown.
    const read = notification({ id: 'n3', unread: false, updated_at: '2026-09-27T11:00:00+00:00' })
    expect(freshNotifications(second.seen, [read]).fresh).toEqual([])
  })

  it('keeps links inside the app', () => {
    expect(appLink('/timers')).toBe('/timers')
    expect(appLink('https://example.com/')).toBeNull()
    expect(appLink('//example')).toBeNull()
    expect(appLink('/tasks?x=1')).toBeNull()
    expect(appLink(null)).toBeNull()
  })

  it('puts important ones into the conversation and leaves info in the list', () => {
    expect(isLoud(notification({ severity: 'important' }))).toBe(true)
    expect(isLoud(notification({ severity: 'info' }))).toBe(false)
  })

  it('snoozes until eight in the morning of the account zone', () => {
    const late = new Date('2026-09-27T20:30:00Z') // 23:30 in Moscow
    expect(nextMorning('Europe/Moscow', late)).toBe('2026-09-28T08:00:00+03:00')
    expect(nextMorning('America/New_York', late)).toBe('2026-09-28T08:00:00-04:00')
  })
})

describe('speaksInChat', () => {
  it('keeps open loud problems in the conversation and lets solved ones leave it', () => {
    expect(speaksInChat(notification({ severity: 'important' }))).toBe(true)
    expect(speaksInChat(notification({ severity: 'info' }))).toBe(false)
    // Read or not, a solved failure no longer opens the chat.
    expect(
      speaksInChat(notification({ state: 'resolved', resolution: 'Бэкап снова проходит' })),
    ).toBe(false)
    expect(speaksInChat(notification({ state: 'resolved', unread: false }))).toBe(false)
  })
})

describe('isOpenProblem', () => {
  it('keeps only loud, unread problems that are still there', () => {
    expect(isOpenProblem(notification())).toBe(true)
    expect(isOpenProblem(notification({ severity: 'info' }))).toBe(false)
    expect(isOpenProblem(notification({ unread: false }))).toBe(false)
    expect(isOpenProblem(notification({ state: 'resolved', unread: true }))).toBe(false)
  })
})
