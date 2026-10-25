import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import SourceLine from './SourceLine.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), writable: true }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'owner', timezone: 'UTC' },
    can: (scope: string) => scope === 'personal.read' || mocks.writable,
  }),
}))

const DAY = '2026-10-12'
const source = (id: string, name: string, kind: string, metrics: string[]) => ({
  id,
  name,
  kinds: [],
  status: 'connected',
  revision: 1,
  metric_keys: metrics,
  device: { kind },
  created_at: '2026-10-01T10:00:00Z',
})
const SOURCES = {
  sources: [
    source('strap', 'WHOOP 5A00095660', 'whoop', ['heart_rate', 'steps']),
    source('ring', 'R20_5AF2', 'ring', ['heart_rate', 'steps', 'sleep']),
  ],
}
const RESOLVED = {
  from: DAY,
  to: DAY,
  timezone: 'UTC',
  computed_at: `${DAY}T20:00:00Z`,
  daily: [],
  series: [],
  next_cursor: null,
  fingerprint: 'f',
  policies: [],
}
const STEPS = [
  { source_id: 'strap', count: 4, value: '1200', spans: [] },
  { source_id: 'ring', count: 9, value: '6400', spans: [] },
]

function show(props: { metric: string; contributions?: unknown[] | null; day?: string }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SourceLine, {
    props: { day: DAY, ...props },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: {
        MetricSourcesPanel: {
          props: ['open', 'row', 'day', 'today', 'names', 'canWrite', 'page', 'sources', 'zone'],
          template:
            '<section v-if="open" data-testid="metric-sources-panel" :data-row="row.key" :data-day="day" :data-write="String(canWrite)" />',
        },
      },
    },
  })
}
const reads = () => mocks.get.mock.calls.map(([path]) => String(path))

beforeEach(() => {
  mocks.writable = true
  mocks.get.mockReset()
  mocks.get.mockImplementation(async (path: string) => {
    if (path === '/v1/personal/sources') return SOURCES
    if (path.startsWith('/v1/personal/resolved?')) return RESOLVED
    throw new Error(`Unexpected read: ${path}`)
  })
})

describe('SourceLine', () => {
  it('names the devices that supplied the value, the largest share first', async () => {
    const wrapper = show({ metric: 'steps', contributions: STEPS })
    await flushPromises()
    expect(wrapper.get('[data-testid="source-line"]').text()).toBe('Источник: кольцо, WHOOP')
    expect(wrapper.get('[data-testid="source-line"]').element.tagName).toBe('BUTTON')
  })

  it('falls back to the name the decision carries for a source it does not list', async () => {
    mocks.get.mockImplementation(async (path: string) =>
      path === '/v1/personal/sources' ? { sources: [] } : RESOLVED,
    )
    const wrapper = show({
      metric: 'heart_rate',
      contributions: [{ source_id: 'gone', source_name: 'Example ring', count: 3, spans: [] }],
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="source-line"]').text()).toBe('Источник: Example ring')
  })

  it.each([[undefined], [null], [[]]])(
    'renders nothing and reads nothing without contributions (%s)',
    async (contributions) => {
      const wrapper = show({ metric: 'steps', contributions })
      await flushPromises()
      expect(wrapper.find('[data-testid="source-line"]').exists()).toBe(false)
      expect(reads()).toEqual([])
    },
  )

  it('renders nothing for a measurement that has no row of sources', async () => {
    const wrapper = show({ metric: 'derived_scores', contributions: STEPS })
    await flushPromises()
    expect(wrapper.find('[data-testid="source-line"]').exists()).toBe(false)
  })

  it("opens the measurement's sources for its day and reads that day only then", async () => {
    mocks.writable = false
    const wrapper = show({ metric: 'running_workouts', contributions: STEPS })
    await flushPromises()
    expect(wrapper.find('[data-testid="metric-sources-panel"]').exists()).toBe(false)
    expect(reads().some((path) => path.startsWith('/v1/personal/resolved?'))).toBe(false)
    await wrapper.get('[data-testid="source-line"]').trigger('click')
    await flushPromises()
    const panel = wrapper.get('[data-testid="metric-sources-panel"]')
    expect(panel.attributes('data-row')).toBe('workouts')
    expect(panel.attributes('data-day')).toBe(DAY)
    expect(panel.attributes('data-write')).toBe('false')
    expect(reads()).toContain(`/v1/personal/resolved?from=${DAY}&to=${DAY}&limit=1`)
  })
})
