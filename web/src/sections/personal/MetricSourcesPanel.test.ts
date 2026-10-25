import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import MetricSourcesPanel from './MetricSourcesPanel.vue'
import { ResolvedPage } from './resolutionContract'
import { SOURCE_ROWS, SourceEntry } from './sources'

const mocks = vi.hoisted(() => ({ get: vi.fn(), operate: vi.fn(), failed: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner', timezone: 'UTC' }, can: () => true }),
}))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))

const DAY = '2026-10-13'
const source = (id: string, kind: string, metrics: string[]) =>
  SourceEntry.parse({
    id,
    name: id,
    kinds: [],
    status: 'connected',
    revision: 2,
    metric_keys: metrics,
    device: { kind },
  })
const sources = [
  source('ring', 'ring', ['heart_rate', 'steps']),
  source('strap', 'whoop', ['heart_rate', 'steps']),
  source('inbody', 'ddx', ['body_fat_percent', 'skeletal_muscle_mass']),
]
const names = new Map([
  ['ring', 'кольцо'],
  ['strap', 'WHOOP'],
  ['inbody', 'InBody'],
])
const resolved = (daily: unknown[], policies: unknown[] = []) =>
  ResolvedPage.parse({
    from: DAY,
    to: DAY,
    timezone: 'UTC',
    computed_at: `${DAY}T20:00:00Z`,
    daily,
    series: [],
    next_cursor: null,
    fingerprint: 'f',
    policies,
    catalog: [{ key: 'heart_rate', unit: 'bpm', shape: 'point' }],
  })
const heart = (resolution: Record<string, unknown>, date = DAY) => ({
  date,
  kind: 'heart_rate',
  status: 'available',
  value: null,
  unit: 'bpm',
  resolution: { status: 'available', fingerprint: 'f', resolver_version: 'v6', ...resolution },
})
const today = resolved(
  [
    heart({
      contributions: [
        {
          source_id: 'ring',
          count: 200,
          spans: [{ start_at: `${DAY}T00:00:00Z`, end_at: `${DAY}T17:10:00Z` }],
        },
        {
          source_id: 'strap',
          count: 27,
          spans: [{ start_at: `${DAY}T17:10:00Z`, end_at: `${DAY}T19:25:00Z` }],
        },
      ],
    }),
  ],
  [{ id: 'old', metric: 'heart_rate', source_ids: ['ring'], effective_from: DAY, revision: 4 }],
)

function show(rowKey = 'heart_rate', page = today, day?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(MetricSourcesPanel, {
    props: {
      open: true,
      row: SOURCE_ROWS.find((row) => row.key === rowKey)!,
      sources,
      page,
      today: DAY,
      day,
      zone: 'UTC',
      names,
      canWrite: true,
    },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: {
        Panel: {
          props: ['open'],
          template: '<section v-if="open"><slot /><slot name="footer" /></section>',
        },
        RouterLink: RouterLinkStub,
      },
    },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.operate.mockResolvedValue({ status: 'applied' })
  mocks.get.mockResolvedValue(
    resolved([
      heart({
        comparisons: [
          {
            source_ids: ['ring', 'strap'],
            count: 26,
            totals: ['1900', '1800'],
            mean_difference: '3.8',
            mean_absolute_difference: '4.2',
            outside_reference: 3,
          },
        ],
      }),
    ]),
  )
})

describe('a measurement’s source choice', () => {
  it('starts from the saved pin, shows today’s split and the week’s comparison', async () => {
    const wrapper = show()
    await flushPromises()
    const checked = wrapper.get('[data-source-id="ring"] [role="radio"]')
    expect(checked.attributes('data-state')).toBe('checked')
    expect(wrapper.text()).toContain('Всегда кольцо')
    expect(wrapper.get('[data-testid="metric-sources-today"]').text()).toContain('00:00–17:10')
    expect(wrapper.get('[data-testid="metric-sources-today"]').text()).toContain('WHOOP')
    const compare = wrapper.get('[data-testid="metric-sources-compare"]').text()
    expect(compare).toContain('кольцо и WHOOP')
    expect(compare).toContain('Расходятся в среднем на 4,2 уд/мин')
    expect(compare).toContain('26 общих замеров')
    expect(compare).toContain('Сильно расходятся: 3')
    expect(mocks.get.mock.calls[0]![0]).toContain('metric=heart_rate')
  })

  it('saves automatic as an empty order over the saved revision of the same day', async () => {
    const wrapper = show()
    await flushPromises()
    await wrapper.get('[data-testid="metric-sources-choice"] [role="radio"]').trigger('click')
    await wrapper.get('section > button').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'resolution.policy.set',
      { metric: 'heart_rate', source_ids: [], revision: 4, effective_from: DAY },
      { old: 4 },
    )
  })

  it('pins the whole body analysis to one device in one change', async () => {
    const wrapper = show('body', resolved([]))
    await flushPromises()
    await wrapper.get('[data-source-id="inbody"] [role="radio"]').trigger('click')
    await wrapper.get('section > button').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'resolution.policies.set',
      {
        effective_from: DAY,
        policies: [
          { metric: 'body_fat_percent', source_ids: ['inbody'], revision: 0 },
          { metric: 'skeletal_muscle_mass', source_ids: ['inbody'], revision: 0 },
        ],
      },
      {},
    )
    expect(wrapper.find('[data-testid="metric-sources-compare"]').exists()).toBe(false)
  })

  it('shows the split of the earlier day it was opened from', async () => {
    const YESTERDAY = '2026-10-12'
    const page = resolved([
      heart(
        {
          contributions: [
            {
              source_id: 'strap',
              count: 5,
              spans: [{ start_at: `${YESTERDAY}T06:00:00Z`, end_at: `${YESTERDAY}T07:30:00Z` }],
            },
          ],
        },
        YESTERDAY,
      ),
    ])
    const wrapper = show('heart_rate', page, YESTERDAY)
    await flushPromises()
    expect(wrapper.get('#metric-sources-today').text()).toBe('Вчера')
    const split = wrapper.get('[data-testid="metric-sources-today"]').text()
    expect(split).toContain('WHOOP')
    expect(split).toContain('06:00–07:30')
  })
})
