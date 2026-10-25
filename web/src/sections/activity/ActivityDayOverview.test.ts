import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import ActivityDayOverview from './ActivityDayOverview.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), read: true, write: true }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    can: (scope: string) =>
      scope === 'personal.read' ? mocks.read : scope === 'personal.write' && mocks.write,
  }),
}))
const TODAY = '2026-10-06'
const YESTERDAY = '2026-10-05'
const clients: QueryClient[] = []
const wrappers: Array<{ unmount(): void }> = []
let stepMetric: Record<string, unknown>
let activeMetric: Record<string, unknown>
let moreMetrics: Record<string, unknown>
let history: Array<Record<string, unknown>>
function report(date: string) {
  const empty = { status: 'no_data', value: null, unit: 'min' }
  return {
    date,
    timezone: 'Europe/Moscow',
    profile: { user_id: 'synthetic-user', timezone: 'Europe/Moscow', revision: 1 },
    metrics: {
      weight: { ...empty, kind: 'weight' },
      sleep: { ...empty, kind: 'sleep' },
      steps: { ...empty, kind: 'steps', unit: 'count', ...stepMetric },
      active_minutes: { ...empty, kind: 'active_minutes', ...activeMetric },
      ...moreMetrics,
    },
    preferences: {},
    sources: [],
  }
}
function resolution(status = 'available', extra: Record<string, unknown> = {}) {
  return {
    status,
    fingerprint: 'synthetic-inputs',
    resolver_version: 'personal-resolution-v1',
    selected: [],
    alternatives: [],
    ...extra,
  }
}
async function respond(path: string) {
  const url = new URL(path, 'https://synthetic.invalid')
  if (url.pathname === '/v1/personal/overview') return report(url.searchParams.get('date')!)
  if (url.pathname === '/v1/personal/sources') return { sources: [] }
  if (url.pathname === '/v1/personal/resolved')
    return {
      from: url.searchParams.get('from'),
      to: url.searchParams.get('to'),
      timezone: 'Europe/Moscow',
      computed_at: `${TODAY}T10:00:00Z`,
      daily: [],
      series: [],
      next_cursor: null,
      fingerprint: 'synthetic-day',
    }
  if (url.pathname === '/v1/personal/history')
    return {
      from: url.searchParams.get('from'),
      to: url.searchParams.get('to'),
      records: [],
      daily: history.filter((row) => row.kind === url.searchParams.get('kind')),
    }
  throw new Error(`Unexpected synthetic read: ${path}`)
}
async function screen(date = TODAY) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(queryClient)
  const wrapper = mount(ActivityDayOverview, {
    props: { date, today: TODAY, timezone: 'Europe/Moscow' },
    global: {
      plugins: [i18n, [VueQueryPlugin, { queryClient }]],
      stubs: {
        Panel: {
          props: ['open', 'title'],
          template:
            '<section v-if="open" data-testid="day-details"><h2>{{ title }}</h2><slot /><slot name="footer" /></section>',
        },
        MetricSourcesPanel: {
          props: ['open', 'row', 'day'],
          template:
            '<section v-if="open" data-testid="metric-sources-panel" :data-row="row.key" :data-day="day" />',
        },
        RouterLink: { props: ['to'], template: '<a :data-to="to.name"><slot /></a>' },
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, queryClient }
}
beforeEach(() => {
  mocks.read = true
  mocks.write = true
  stepMetric = {}
  activeMetric = {}
  moreMetrics = {}
  history = []
  mocks.get.mockReset()
  mocks.get.mockImplementation(respond)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clients.splice(0).forEach((client) => client.clear())
})

describe('Activity day overview', () => {
  it('preserves a measured zero and marks absent week days instead of inventing zeroes', async () => {
    stepMetric = { status: 'available', value: 0 }
    activeMetric = { status: 'available', value: 0 }
    history = [{ date: TODAY, kind: 'steps', status: 'available', value: 0 }]
    const { wrapper } = await screen()
    expect(wrapper.get('[data-testid="day-value-steps"]').text()).toBe('0')
    expect(wrapper.get('[data-testid="day-value-active_minutes"]').text()).toBe('0 мин')
    expect(wrapper.get(`[data-testid="week-day-${TODAY}"]`).attributes('aria-label')).toContain(
      '0 шагов',
    )
    expect(wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).attributes('data-status')).toBe(
      'no_data',
    )
    expect(wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).attributes('aria-label')).toContain(
      'Нет данных',
    )
    // The week carries no notes on its data; a day tells them in its details.
    expect(wrapper.get('[data-testid="activity-week"]').text()).not.toContain('Дневной итог')
    // An unknown measuring time is no note on the tile.
    expect(wrapper.get('[data-testid="day-metric-steps"]').text()).not.toContain('Время измерения')
  })

  it('shows active energy and distance as tiles once a source reports them', async () => {
    const empty = await screen()
    expect(empty.wrapper.find('[data-testid="day-metric-active_energy"]').exists()).toBe(false)
    expect(empty.wrapper.find('[data-testid="day-metric-distance"]').exists()).toBe(false)
    moreMetrics = {
      active_energy: { status: 'available', kind: 'active_energy', unit: 'kcal', value: 59.537 },
      distance: { status: 'available', kind: 'distance', unit: 'm', value: 1582.75 },
    }
    const { wrapper } = await screen()
    expect(wrapper.get('[data-testid="day-value-active_energy"]').text()).toBe('60 ккал')
    expect(wrapper.get('[data-testid="day-value-distance"]').text()).toBe('1,58 км')
  })

  it('keeps useful earlier readings in the week without relabelling them as the selected day', async () => {
    history = [{ date: YESTERDAY, kind: 'steps', status: 'available', value: 700 }]
    const { wrapper } = await screen()
    expect(wrapper.find('[data-testid="day-value-steps"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="day-status-steps"]').text()).toBe('Нет данных')
    expect(wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).attributes('aria-label')).toContain(
      '700 шагов',
    )
    expect(wrapper.get('[data-testid="day-metric-steps"]').text()).not.toContain('700')
  })

  it('states a conflicting decision once, without an action, and never shows its amount', async () => {
    stepMetric = {
      status: 'available',
      value: 450,
      resolution: resolution('conflict'),
    }
    history = [
      {
        date: YESTERDAY,
        kind: 'steps',
        status: 'conflict',
        value: 450,
        resolution: resolution('conflict'),
      },
    ]
    const { wrapper } = await screen(YESTERDAY)
    expect(wrapper.find('[data-testid="day-value-steps"]').exists()).toBe(false)
    const status = wrapper.get('[data-testid="day-status-steps"]')
    expect(status.text()).toBe('Показания не согласованы')
    expect(status.attributes('data-status')).toBe('conflict')
    const tile = status.element.closest('div')!
    expect(tile.textContent!.split('Показания не согласованы')).toHaveLength(2)
    expect(tile.querySelectorAll('button')).toHaveLength(1)
    expect(tile.querySelector('a')).toBeNull()
    expect(
      wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).attributes('aria-label'),
    ).not.toContain('450')
  })

  it('gates reads and write actions by Personal permissions', async () => {
    mocks.write = false
    stepMetric = { status: 'conflict', resolution: resolution('conflict') }
    const { wrapper } = await screen()
    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.get('[data-testid="day-metric-steps"]').trigger('click')
    expect(wrapper.find('[data-testid="day-correct-steps"]').exists()).toBe(false)
    mocks.read = false
    mocks.get.mockClear()
    const forbidden = await screen()
    expect(forbidden.wrapper.find('[data-testid="activity-day-overview"]').exists()).toBe(false)
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('keeps coverage, protocol and actual measurement freshness local to their metric', async () => {
    stepMetric = {
      status: 'available',
      value: 300,
      complete: false,
      resolution: resolution('partial', {
        selected: [{ id: 'synthetic-fact', source_name: 'Example ring', contribution: '300' }],
        freshness: { measured_at: '2026-10-06T11:20:00Z' },
        protocol: { verified: false },
      }),
    }
    activeMetric = { status: 'available', value: 35 }
    const { wrapper } = await screen()
    const steps = wrapper.get('[data-testid="day-metric-steps"]')
    expect(steps.text()).toContain('Неполные данные')
    expect(steps.text()).toContain('Измерено: сегодня, 14:20')
    expect(steps.text()).toContain('Протокол не подтверждён')
    const minutes = wrapper.get('[data-testid="day-metric-active_minutes"]')
    // Only a partial day is marked on a tile.
    expect(minutes.text()).not.toContain('Полнота')
    expect(minutes.text()).not.toContain('Протокол')
    expect(minutes.text()).not.toContain('14:20')
    expect(wrapper.find('[data-testid="resolution-explanation"]').exists()).toBe(false)
  })

  it('opens source details progressively and emits the selected-day correction action', async () => {
    stepMetric = {
      status: 'available',
      value: 900,
      resolution: resolution('available', {
        selected: [{ id: 'synthetic-fact', source_name: 'Example ring', contribution: '900' }],
        freshness: { measured_at: '2026-10-05T08:00:00Z' },
        contributions: [
          { source_id: 'synthetic-ring', source_name: 'Example ring', count: 1, value: '900' },
        ],
      }),
    }
    const { wrapper } = await screen(YESTERDAY)
    expect(wrapper.find('[data-testid="day-details"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Example ring')
    await wrapper.get('[data-testid="day-metric-steps"]').trigger('click')
    await flushPromises()
    const details = wrapper.get('[data-testid="day-details"]')
    // One line says where the value came from; the measuring time is told once, in the explanation.
    expect(details.get('[data-testid="source-line"]').text()).toBe('Источник: Example ring')
    expect(details.text().split('Измерено:')).toHaveLength(2)
    expect(details.text()).not.toContain('Источник результата')
    expect(wrapper.get('[data-testid="resolution-explanation"]').attributes('open')).toBeUndefined()
    await details.get('[data-testid="source-line"]').trigger('click')
    await flushPromises()
    const sheet = wrapper.get('[data-testid="metric-sources-panel"]')
    expect(sheet.attributes('data-row')).toBe('steps')
    expect(sheet.attributes('data-day')).toBe(YESTERDAY)
    await wrapper.get('[data-testid="day-correct-steps"]').trigger('click')
    expect(wrapper.emitted('correctSteps')).toEqual([[]])
    expect(wrapper.find('[data-testid="day-details"]').exists()).toBe(false)
  })

  it('selects a week day directly while preserving the explicit seven-day interval', async () => {
    const { wrapper } = await screen()
    expect(wrapper.findAll('[data-testid^="week-day-"]')).toHaveLength(7)
    expect(wrapper.get(`[data-testid="week-day-${TODAY}"]`).attributes('aria-pressed')).toBe('true')
    await wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).trigger('click')
    expect(wrapper.emitted('go')).toEqual([[YESTERDAY]])
    const historyCalls = mocks.get.mock.calls.filter(([path]) => path.includes('/history?')).length
    await wrapper.setProps({ date: YESTERDAY })
    await flushPromises()
    expect(wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).attributes('aria-pressed')).toBe(
      'true',
    )
    expect(wrapper.get(`[data-testid="week-day-${TODAY}"]`).attributes('aria-pressed')).toBe(
      'false',
    )
    expect(mocks.get.mock.calls.filter(([path]) => path.includes('/history?'))).toHaveLength(
      historyCalls,
    )
    expect(wrapper.findAll('[style]')).toHaveLength(0)
  })

  it('reveals an offscreen selected day horizontally and leaves manual panning alone', async () => {
    const { wrapper } = await screen()
    const strip = wrapper.get('[data-testid="activity-week-strip"]').element as HTMLElement
    const yesterday = wrapper.get(`[data-testid="week-day-${YESTERDAY}"]`).element
    const today = wrapper.get(`[data-testid="week-day-${TODAY}"]`).element
    strip.getBoundingClientRect = () => ({ left: 0, right: 280, width: 280 }) as DOMRect
    yesterday.getBoundingClientRect = () => ({ left: 600, right: 688, width: 88 }) as DOMRect
    const scrollTo = vi.fn()
    strip.scrollTo = scrollTo as unknown as typeof strip.scrollTo

    await wrapper.setProps({ date: YESTERDAY })
    await flushPromises()
    expect(scrollTo).toHaveBeenCalledWith({ left: 504, behavior: 'auto' })

    scrollTo.mockClear()
    strip.dispatchEvent(new Event('scroll'))
    await flushPromises()
    expect(scrollTo).not.toHaveBeenCalled()

    today.getBoundingClientRect = () => ({ left: 90, right: 178, width: 88 }) as DOMRect
    await wrapper.setProps({ date: TODAY })
    await flushPromises()
    expect(scrollTo).not.toHaveBeenCalled()
  })

  it('clears an old day during a new read and rejects a mismatched response', async () => {
    stepMetric = { status: 'available', value: 3200 }
    const { wrapper } = await screen()
    await wrapper.get('[data-testid="day-metric-steps"]').trigger('click')
    let finish!: (value: unknown) => void
    mocks.get.mockImplementation((path: string) =>
      path.includes(`date=${YESTERDAY}`)
        ? new Promise((resolve) => {
            finish = resolve
          })
        : respond(path),
    )
    await wrapper.setProps({ date: YESTERDAY })
    await flushPromises()
    expect(wrapper.find('[data-testid="day-value-steps"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="day-details"]').exists()).toBe(false)
    finish(report(TODAY))
    await flushPromises()
    expect(wrapper.find('[data-testid="day-value-steps"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="day-status-steps"]').text()).toBe('Нет данных')
    expect(wrapper.get('[data-testid="day-metric-steps"]').text()).not.toContain('3\u00a0200')
  })

  it('loads active-minute history only when that detail is requested', async () => {
    activeMetric = { status: 'available', value: 35 }
    history = [{ date: TODAY, kind: 'active_minutes', status: 'available', value: 35 }]
    const { wrapper } = await screen()
    expect(mocks.get.mock.calls.some(([path]) => path.includes('kind=active_minutes'))).toBe(false)
    await wrapper.get('[data-testid="day-metric-active_minutes"]').trigger('click')
    await flushPromises()
    expect(mocks.get.mock.calls.some(([path]) => path.includes('kind=active_minutes'))).toBe(true)
    expect(wrapper.get('[data-testid="day-details"]').text()).toContain('35 мин')
    expect(wrapper.get('[data-testid="day-details"]').text()).toContain(
      'Активные минуты учитываются отдельно от длительности занятий.',
    )
    expect(wrapper.find('[data-testid="day-correct-steps"]').exists()).toBe(false)
  })

  it('keeps loaded readings and their measurement time visible after a failed refresh', async () => {
    stepMetric = {
      status: 'available',
      value: 350,
      resolution: resolution('available', { freshness: { measured_at: '2026-10-06T08:10:00Z' } }),
    }
    const { wrapper, queryClient } = await screen()
    mocks.get.mockImplementation((path: string) => {
      if (path.includes('/overview?')) throw new Error('synthetic refresh failure')
      return respond(path)
    })
    await queryClient.invalidateQueries({ queryKey: ['personal', 'overview', TODAY] })
    await flushPromises()
    expect(wrapper.get('[data-testid="day-value-steps"]').text()).toBe('350')
    expect(wrapper.get('[data-testid="day-metric-steps"]').text()).toContain('11:10')
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(true)
  })
})
