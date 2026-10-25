import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import NotificationCard from './NotificationCard.vue'
import { Notification } from './model'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'UTC' } }) }))
vi.mock('./useNotifications', () => ({
  useNotifications: () => ({ read: vi.fn(), snooze: vi.fn() }),
}))

const notification = (fields: Partial<Notification> = {}) =>
  Notification.parse({
    id: 'n1',
    source: 'system',
    topic: 'system.deploy',
    severity: 'important',
    title: 'Обновление не установилось',
    body: 'Новая версия не запустилась. Работает прежняя версия.',
    link: '/admin',
    state: 'active',
    resolution: null,
    count: 1,
    updated_at: '2026-10-07T09:00:00+00:00',
    read_at: null,
    snoozed_until: null,
    unread: true,
    ...fields,
  })
const card = (value: Notification, feed = false) =>
  mount(NotificationCard, { props: { notification: value, feed }, global: { plugins: [i18n] } })

describe('NotificationCard', () => {
  it('keeps an open problem in full, «Открыть» as an outline button', () => {
    const wrapper = card(notification())
    expect(wrapper.text()).toContain('Новая версия не запустилась')
    const open = wrapper.findAll('button').find((button) => button.text() === 'Открыть')!
    expect(open.attributes('data-variant')).toBe('outline')
  })

  it('folds a solved one into a grey line with no actions, its text one tap away', async () => {
    const wrapper = card(
      notification({
        state: 'resolved',
        unread: false,
        read_at: '2026-10-07T09:10:00+00:00',
        resolved_at: '2026-10-07T09:10:00+00:00',
        resolution: 'Обновление установлено',
      }),
    )
    expect(wrapper.get('[data-testid="notification"]').attributes('data-state')).toBe('resolved')
    const toggle = wrapper.get('button')
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(toggle.text()).toContain('Обновление не установилось')
    expect(toggle.text()).toContain('решено')
    expect(wrapper.text()).not.toContain('Новая версия не запустилась')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.text()).toContain('Новая версия не запустилась')
    expect(wrapper.text()).toContain('Обновление установлено')
  })

  it('folds a solved one in the conversation as well, the moment in lower case', () => {
    const wrapper = card(
      notification({
        state: 'resolved',
        unread: false,
        resolved_at: '2026-10-07T09:10:00+00:00',
        resolution: 'Обновление установлено',
      }),
      true,
    )
    expect(wrapper.get('[data-testid="notification"]').attributes('data-state')).toBe('resolved')
    expect(wrapper.text()).not.toContain('Новая версия не запустилась')
    expect(wrapper.get('button').text()).toMatch(/решено \d|решено [а-я]/)
  })
})
