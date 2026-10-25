import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import NotificationsCard from './NotificationsCard.vue'

const base = {
  source: 'system',
  topic: 'system.backup',
  severity: 'important',
  body: '',
  link: null,
  resolution: null,
  count: 1,
  updated_at: '2026-10-07T09:00:00+00:00',
  read_at: null,
  snoozed_until: null,
}
vi.mock('@/sections/notifications/useNotifications', () => ({
  useNotifications: () => ({
    items: ref([
      { ...base, id: 'a', title: 'Бэкап не удался', state: 'active', unread: true },
      { ...base, id: 'b', title: 'Обновление не установилось', state: 'resolved', unread: false },
      {
        ...base,
        id: 'c',
        title: 'Таймер сработал',
        severity: 'info',
        state: 'active',
        unread: true,
      },
    ]),
  }),
}))

describe('Today notifications card', () => {
  it('lists only problems still open', () => {
    const wrapper = mount(NotificationsCard, {
      global: { plugins: [i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(['Бэкап не удался'])
  })
})
