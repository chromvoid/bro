import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import { russianPlural } from '@/i18n'
import SourceDataView from './SourceDataView.vue'
import SourceView from './SourceView.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  route: { params: {} as Record<string, string>, query: {} as Record<string, string> },
  replace: vi.fn(),
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner', timezone: 'UTC' }, can: () => true }),
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
}))
vi.mock('./usePersonal', () => ({
  useOverview: () => ({ data: ref(undefined) }),
  usePersonal: () => ({ unresolved: ref([]), operate: vi.fn(), failed: vi.fn() }),
}))

const DAY = new Date().toISOString().slice(0, 10)
const SOURCES = {
  sources: [
    {
      id: 'strap',
      name: 'WHOOP 5A00095660',
      kinds: [],
      status: 'connected',
      revision: 1,
      metric_keys: ['heart_rate', 'steps', 'running_steps'],
      device: { kind: 'whoop' },
      last_received_at: `${DAY}T00:00:00Z`,
      created_at: '2026-10-13T17:00:00Z',
    },
    {
      id: 'ring',
      name: 'R20_5AF2',
      kinds: [],
      status: 'connected',
      revision: 1,
      metric_keys: ['heart_rate', 'steps'],
      device: { kind: 'ring' },
      last_received_at: `${DAY}T00:00:00Z`,
      created_at: '2026-10-01T10:00:00Z',
    },
  ],
}
const daily = (
  kind: string,
  value: string | null,
  unit: string,
  contributions: unknown[] = [],
) => ({
  date: DAY,
  kind,
  status: 'available',
  value,
  unit,
  resolution: { status: 'available', fingerprint: 'f', resolver_version: 'v6', contributions },
})
const page = (days: unknown[], series: unknown[] = []) => ({
  from: DAY,
  to: DAY,
  timezone: 'UTC',
  computed_at: `${DAY}T20:00:00Z`,
  daily: days,
  series,
  next_cursor: null,
  fingerprint: 'f',
  policies: [],
})
const reading = (id: string, minute: number, value: string) => ({
  id,
  source_id: 'strap',
  metric: 'heart_rate',
  start_at: `${DAY}T10:${String(minute).padStart(2, '0')}:00Z`,
  end_at: `${DAY}T10:${String(minute + 5).padStart(2, '0')}:00Z`,
  value,
  unit: 'bpm',
})
// The strap alone, and the combined day in which the ring gave the steps.
const OWN = page(
  [
    daily('heart_rate', '70', 'bpm'),
    daily('steps', '1200', 'steps'),
    daily('running_steps', '0', 'steps'),
  ],
  [reading('a', 0, '62'), reading('b', 5, '80'), reading('c', 10, '68')],
)
const COMBINED = page([
  daily('heart_rate', '70', 'bpm', [{ source_id: 'strap', count: 3 }]),
  daily('steps', '8014', 'steps', [{ source_id: 'ring', count: 20, value: 8014 }]),
])

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route = { params: { source: 'strap', row: 'heart_rate' }, query: {} }
  mocks.get.mockImplementation(async (path: string) =>
    path.startsWith('/v1/personal/sources')
      ? SOURCES
      : path.startsWith('/v1/personal/resolved')
        ? path.includes('source_id=')
          ? OWN
          : COMBINED
        : { rings: [] },
  )
})

const spaced = (text: string) => text.replace(/\s+/g, ' ').trim()

function show(component: typeof SourceView | typeof SourceDataView) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(component, {
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({
          legacy: false,
          locale: 'ru',
          messages: { ru },
          pluralRules: { ru: russianPlural },
        }),
      ],
      stubs: {
        PageHeader: {
          props: ['title', 'description'],
          template: '<h1>{{ title }} {{ description }}</h1>',
        },
        DayNav: { props: ['day'], template: '<nav data-testid="day">{{ day }}</nav>' },
        LineChart: {
          props: ['points'],
          template: '<figure data-testid="chart">{{ points.length }}</figure>',
        },
        MetricSourcesPanel: {
          props: ['row'],
          template: '<aside data-testid="panel">{{ row.key }}</aside>',
        },
        UnresolvedWrites: true,
        CollectorDetails: true,
        SourceLinks: true,
        RouterLink: RouterLinkStub,
      },
    },
  })
}

describe('a device’s own data', () => {
  it('lists what the device measures with its own value today and links each to its data', async () => {
    mocks.route = { params: { source: 'strap' }, query: {} }
    const wrapper = show(SourceView)
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith(
      expect.stringContaining('source_id=strap'),
      expect.anything(),
    )
    expect(wrapper.get('[data-testid="source-value-heart_rate"]').text()).toBe('70 уд/мин')
    // The strap counted its own steps, even though the day took the ring's.
    expect(spaced(wrapper.get('[data-testid="source-value-steps"]').text())).toBe('1 200 шагов')
    expect(wrapper.get('[data-testid="source-measure-steps"]').text()).not.toContain('Основной')
    expect(wrapper.get('[data-testid="source-measure-heart_rate"]').text()).toContain('Основной')
    // No run counted: the workouts row has no line rather than «0 шагов».
    expect(wrapper.get('[data-testid="source-measure-workouts"]').text()).toContain('Тренировки')
    expect(wrapper.find('[data-testid="source-value-workouts"]').exists()).toBe(false)
    const link = wrapper
      .findAllComponents(RouterLinkStub)
      .find((item) => item.attributes('data-testid') === 'source-measure-heart_rate')!
    expect(link.props('to')).toEqual({
      name: 'personal-source-data',
      params: { source: 'strap', row: 'heart_rate' },
      query: {},
    })
  })

  it('shows one vital through the day: its value, range, chart and every reading', async () => {
    const wrapper = show(SourceDataView)
    await flushPromises()
    expect(wrapper.text()).toContain('Пульс')
    expect(wrapper.text()).toContain('Браслет WHOOP')
    const card = wrapper.get('[data-testid="source-data-heart_rate"]')
    expect(card.text()).toContain('70 уд/мин')
    expect(card.text()).toContain('мин. 62 уд/мин · макс. 80 уд/мин')
    expect(card.get('[data-testid="chart"]').text()).toBe('3')
    const list = card.get('[data-testid="source-readings-heart_rate"]')
    expect(list.text()).toContain('3 замера')
    expect(
      list.findAll('li').map((item) =>
        item
          .findAll('span')
          .map((part) => part.text())
          .join(' '),
      ),
    ).toEqual(['10:10 68 уд/мин', '10:05 80 уд/мин', '10:00 62 уд/мин'])
    expect(wrapper.get('[data-testid="source-data-role"]').text()).toBe(
      'Основной источник за этот день',
    )
  })

  it('says when the combined day did not use the device', async () => {
    mocks.route = { params: { source: 'strap', row: 'steps' }, query: {} }
    const wrapper = show(SourceDataView)
    await flushPromises()
    expect(spaced(wrapper.get('[data-testid="source-data-steps"]').text())).toBe('1 200 шагов')
    expect(wrapper.get('[data-testid="source-data-role"]').text()).toBe('В итог дня не вошёл')
  })

  it('moves between days and opens the source choice', async () => {
    mocks.route = { params: { source: 'strap', row: 'heart_rate' }, query: { day: '2026-10-01' } }
    mocks.get.mockImplementation(async (path: string) =>
      path.startsWith('/v1/personal/sources') ? SOURCES : page([]),
    )
    const wrapper = show(SourceDataView)
    await flushPromises()
    expect(wrapper.get('[data-testid="day"]').text()).toBe('2026-10-01')
    expect(mocks.get).toHaveBeenCalledWith(
      expect.stringContaining('from=2026-10-01&to=2026-10-01&source_id=strap'),
      expect.anything(),
    )
    expect(wrapper.text()).toContain('За этот день данных нет')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Выбор источника')!
      .trigger('click')
    expect(wrapper.get('[data-testid="panel"]').text()).toBe('heart_rate')
  })
})
