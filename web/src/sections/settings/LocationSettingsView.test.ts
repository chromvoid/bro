import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { useSession } from '@/stores/session'
import type { Permission } from '@/location/position'
import LocationSettingsView from './LocationSettingsView.vue'

const state = vi.hoisted(() => ({
  location: null as unknown,
  permission: 'prompt' as Permission,
  answer: 'granted' as Permission,
  sent: [] as unknown[],
  forgotten: 0,
  recording: null as null | { enabled: boolean; calls: string[] },
}))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/location/api', () => ({
  LOCATION_KEY: ['location'],
  readLocation: async () => state.location,
  sendFix: async (fix: unknown) => void state.sent.push(fix),
  forgetHistory: async () => void (state.forgotten += 1),
}))
vi.mock('@/location/background', () => ({
  backgroundRecording: async () => {
    const recording = state.recording
    if (!recording) return null
    const now = () => ({ enabled: recording.enabled, permission: 'granted', queued: 0 })
    return {
      status: async () => now(),
      enable: async (user: string) => {
        recording.calls.push(`enable:${user}`)
        recording.enabled = true
        return now()
      },
      disable: async () => {
        recording.calls.push('disable')
        recording.enabled = false
        return now()
      },
    }
  },
}))
vi.mock('@/location/position', () => ({
  positionSource: async () => ({
    kind: 'browser',
    status: async () => state.permission,
    request: async () => (state.permission = state.answer),
    current: async () => ({
      captured_at: '2026-10-09T18:10:00.000Z',
      latitude: 55.73812,
      longitude: 37.58004,
      accuracy_m: 40,
    }),
  }),
}))

const HERE = {
  current: {
    place: 'Москва, район Хамовники',
    country: 'RU',
    latitude: 55.73812,
    longitude: 37.58004,
    accuracy_m: 40,
    age_minutes: 3,
    device: 'Телефон',
    captured_at: '2026-10-09T18:07:00+00:00',
  },
  history: { points: 12, since: '2026-10-01T08:00:00+00:00' },
  background: { enabled: false },
}

async function screen() {
  const pinia = createPinia()
  useSession(pinia).me = {
    id: 'u',
    name: 'User',
    login: 'user',
    locale: 'ru',
    timezone: 'Europe/Moscow',
    system_admin: false,
    revision: 1,
    scopes: [],
  }
  const wrapper = mount(LocationSettingsView, {
    global: {
      plugins: [pinia, i18n, [VueQueryPlugin, { queryClient: new QueryClient() }]],
      stubs: { PageHeader: true },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

describe('location settings', () => {
  beforeEach(() => {
    Object.assign(state, {
      location: HERE,
      permission: 'prompt',
      answer: 'granted',
      sent: [],
      forgotten: 0,
      recording: null,
    })
  })

  it('shows the current place, its details and the size of the history', async () => {
    const wrapper = await screen()
    const current = wrapper.get('[data-testid="location-current"]').text()
    expect(current).toContain('Москва, район Хамовники')
    expect(current).toContain('±40 м')
    expect(current).toContain('Телефон')
    expect(wrapper.text()).toContain('В истории 12 точек')
    expect(wrapper.text()).toContain('© OpenStreetMap contributors')
    wrapper.unmount()
  })

  it('asks for permission only on the button and sends the first position', async () => {
    const wrapper = await screen()
    expect(wrapper.get('[data-testid="location-permission"]').text()).toBe('Не разрешено.')
    expect(state.sent).toHaveLength(0)
    await wrapper.get('[data-testid="location-allow"]').trigger('click')
    await flushPromises()
    expect(state.sent).toHaveLength(1)
    expect(wrapper.find('[data-testid="location-allow"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('deletes the history only after confirming', async () => {
    const wrapper = await screen()
    await wrapper.get('[data-testid="location-forget"]').trigger('click')
    await flushPromises()
    expect(state.forgotten).toBe(0)
    const dialog = new DOMWrapper(document.querySelector('[role="dialog"]')!)
    expect(dialog.text()).toContain('Удалить историю перемещений?')
    await dialog
      .findAll('button')
      .find((button) => button.text() === 'Удалить')!
      .trigger('click')
    await flushPromises()
    expect(state.forgotten).toBe(1)
    wrapper.unmount()
  })

  it('names coordinates when the place has no label yet, and says when nothing is known', async () => {
    state.location = { ...HERE, current: { ...HERE.current, place: null } }
    let wrapper = await screen()
    expect(wrapper.get('[data-testid="location-current"]').text()).toContain('55.73812, 37.58004')
    wrapper.unmount()
    state.location = {
      current: null,
      history: { points: 0, since: null },
      background: { enabled: false },
    }
    wrapper = await screen()
    expect(wrapper.text()).toContain('Место пока неизвестно.')
    expect(wrapper.find('[data-testid="location-forget"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('turns background recording on in the phone app once the position is allowed', async () => {
    state.recording = { enabled: false, calls: [] }
    let wrapper = await screen()
    expect(wrapper.find('[data-testid="location-background"]').exists()).toBe(false)
    wrapper.unmount()
    state.permission = 'granted'
    wrapper = await screen()
    expect(wrapper.text()).toContain('только пока приложение открыто')
    await wrapper.get('[data-testid="location-background"]').trigger('click')
    await flushPromises()
    expect(state.recording.calls).toEqual(['enable:u'])
    expect(wrapper.text()).toContain('и с закрытым приложением')
    wrapper.unmount()
  })
})
