import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import SourcesView from './SourcesView.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), writable: true }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'owner', timezone: 'UTC' },
    can: (scope: string) => scope === 'personal.read' || mocks.writable,
  }),
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))

const DAY = new Date().toISOString().slice(0, 10)
const source = (id: string, name: string, kind: string, metrics: string[], extra = {}) => ({
  id,
  name,
  kinds: [],
  status: 'connected',
  revision: 1,
  metric_keys: metrics,
  device: { kind },
  last_received_at: `${DAY}T00:00:00Z`,
  created_at: '2026-10-01T10:00:00Z',
  ...extra,
})
const SOURCES = {
  sources: [
    source('strap', 'WHOOP 5A00095660', 'whoop', ['heart_rate', 'steps', 'temperature']),
    source('ring', 'R20_5AF2', 'ring', ['heart_rate', 'steps', 'sleep']),
    source('inbody', 'DDX InBody', 'ddx', ['weight', 'body_fat_percent']),
    source('old', 'R20_5AF2', 'ring', ['heart_rate'], { status: 'disabled' }),
  ],
}
const daily = (kind: string, contributions: unknown[]) => ({
  date: DAY,
  kind,
  status: 'available',
  value: null,
  unit: '',
  resolution: { status: 'available', fingerprint: 'f', resolver_version: 'v6', contributions },
})
const RESOLVED = {
  from: DAY,
  to: DAY,
  timezone: 'UTC',
  computed_at: `${DAY}T20:00:00Z`,
  series: [],
  next_cursor: null,
  fingerprint: 'f',
  daily: [
    daily('heart_rate', [
      { source_id: 'strap', count: 27, spans: [] },
      { source_id: 'ring', count: 200, spans: [] },
    ]),
  ],
  policies: [
    { id: 'p', metric: 'steps', source_ids: ['ring'], effective_from: '2026-10-01', revision: 1 },
  ],
}
const RINGS = {
  rings: [
    {
      collector_id: 'c-strap',
      source_id: 'strap',
      name: 'WHOOP 5A00095660',
      profile: 'whoop-5',
      generation: 1,
      enabled: true,
      last_received_at: null,
      cursor: null,
      error: null,
      status: 'connected',
      device_state: {
        observed_at: `${DAY}T19:00:00Z`,
        revision: 1,
        received_at: `${DAY}T19:00:00Z`,
        stale: false,
        battery_percent: 100,
      },
    },
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.writable = true
  mocks.get.mockImplementation(async (path: string) =>
    path.startsWith('/v1/personal/sources')
      ? SOURCES
      : path.startsWith('/v1/personal/resolved')
        ? RESOLVED
        : RINGS,
  )
})

function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SourcesView, {
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: {
        PageHeader: true,
        Panel: true,
        MetricSourcesPanel: {
          props: ['row', 'open'],
          template: '<aside data-testid="panel">{{ row.key }}</aside>',
        },
        RouterLink: RouterLinkStub,
      },
    },
  })
}

describe('measurement sources screen', () => {
  it('lists devices by kind with their charge and keeps switched-off ones folded', async () => {
    const wrapper = show()
    await flushPromises()
    const devices = wrapper.get('[data-testid="source-devices"]').text()
    expect(devices.indexOf('Кольцо HerzRing')).toBeLessThan(devices.indexOf('Браслет WHOOP'))
    expect(devices).toContain('R20_5AF2')
    expect(devices).toContain('100 %')
    expect(devices).toContain('DDX InBody')
    expect(wrapper.text()).toContain('Отключённые · 1')
    expect(wrapper.find('[data-source-id="strap"]').exists()).toBe(true)
  })

  it('says where each measurement comes from today and opens its choice', async () => {
    const wrapper = show()
    await flushPromises()
    expect(wrapper.get('[data-testid="source-row-heart_rate"]').text()).toContain('Кольцо, WHOOP')
    expect(wrapper.get('[data-testid="source-row-steps"]').text()).toContain('Всегда кольцо')
    expect(wrapper.get('[data-testid="source-row-body"]').text()).toContain('InBody')
    expect(wrapper.find('[data-testid="source-row-spo2"]').exists()).toBe(false)
    await wrapper.get('[data-testid="source-row-heart_rate"]').trigger('click')
    expect(wrapper.get('[data-testid="panel"]').text()).toBe('heart_rate')
  })

  it('offers connecting a device only to a writer', async () => {
    mocks.writable = false
    const wrapper = show()
    await flushPromises()
    expect(wrapper.find('[data-testid="source-connect"]').exists()).toBe(false)
  })
})
