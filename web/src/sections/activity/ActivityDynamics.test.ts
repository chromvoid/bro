import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LineChart from '@/components/chart/LineChart.vue'
import { i18n } from '@/i18n'
import { provideDisplayUnits, type DisplayUnits } from '@/sections/personal/displayUnits'
import ActivityDynamics from './ActivityDynamics.vue'
import { atDay } from './charts'
import {
  RUNNING_DAY,
  runningDecision,
  runningPage,
  workoutDay,
  workoutEpisode,
} from './runningFixtures'
import type { ActivityRange } from './useActivity'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
const scopes = ref(['personal.read'])
vi.mock('@/stores/session', () => ({
  useSession: () => ({ can: (scope: string) => scopes.value.includes(scope) }),
}))

type Daily = {
  date: string
  kind: string
  status: string
  value: string | number | null
  resolution?: Record<string, unknown>
}
let daily: Daily[]
let manualError: boolean
let deviceError: boolean
const wrappers: Array<{ unmount(): void }> = []
const clients: QueryClient[] = []
const range = { from: '2026-10-01', to: RUNNING_DAY, kind: null }
const manualReply = () => ({
  ...range,
  totals: {
    sessions: 2,
    duration_minutes: 30,
    missing_duration_sessions: 1,
    distance_km_by_kind: { running: 5 },
    working_sets: 0,
  },
  daily: [
    {
      date: '2026-10-03',
      sessions: 1,
      duration_minutes: 30,
      missing_duration_sessions: 0,
      distance_km_by_kind: { running: 5 },
      working_sets: 0,
    },
    {
      date: '2026-10-05',
      sessions: 1,
      duration_minutes: 0,
      missing_duration_sessions: 1,
      distance_km_by_kind: {},
      working_sets: 0,
    },
  ],
})
const historyReply = (url: string) => {
  const query = new URL(url, 'https://synthetic.invalid').searchParams
  return {
    from: query.get('from'),
    to: query.get('to'),
    records: [],
    daily: daily
      .filter(
        (row) =>
          row.kind === query.get('kind') &&
          row.date >= query.get('from')! &&
          row.date <= query.get('to')!,
      )
      .map((row) => ({ ...row, target: null })),
  }
}
const screen = (
  subject: 'steps' | 'active_minutes' | 'sessions' = 'steps',
  selectedRange: ActivityRange = range,
) => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
  })
  clients.push(client)
  const wrapper = mount(ActivityDynamics, {
    props: { subject, range: selectedRange, today: RUNNING_DAY, timezone: 'Europe/Moscow' },
    global: {
      plugins: [[VueQueryPlugin, { queryClient: client }], i18n],
      stubs: { LineChart: true },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  scopes.value = ['personal.read']
  manualError = false
  deviceError = false
  daily = [
    { date: '2026-10-01', kind: 'steps', status: 'available', value: 0 },
    { date: '2026-10-02', kind: 'steps', status: 'no_data', value: null },
    { date: '2026-10-03', kind: 'steps', status: 'conflict', value: 999 },
    { date: '2026-10-04', kind: 'steps', status: 'available', value: '' },
    { date: '2026-10-05', kind: 'steps', status: 'available', value: 'invalid' },
    { date: RUNNING_DAY, kind: 'steps', status: 'available', value: 100 },
    { date: RUNNING_DAY, kind: 'active_minutes', status: 'available', value: 30 },
  ]
  transport.get.mockReset()
  transport.get.mockImplementation(async (url: string) => {
    if (url.startsWith('/v1/personal/history?')) return historyReply(url)
    if (url.startsWith('/v1/activity/summary?')) {
      if (manualError) throw new Error('Synthetic manual query failure')
      return manualReply()
    }
    if (url.startsWith('/v1/personal/resolved?')) {
      if (deviceError) throw new Error('Synthetic device query failure')
      return runningPage([
        workoutDay([workoutEpisode('first'), workoutEpisode('second'), workoutEpisode('third')]),
      ])
    }
    throw new Error(`Unexpected endpoint: ${url}`)
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clients.splice(0).forEach((client) => client.clear())
})

describe('Activity dynamics reading context', () => {
  it('keeps measured zero and gaps while excluding conflicting, empty and invalid measurements', async () => {
    const wrapper = screen()
    await flushPromises()
    expect(wrapper.getComponent(LineChart).props('points')).toEqual([
      { x: atDay('2026-10-01'), y: 0 },
      { x: atDay(RUNNING_DAY), y: 100 },
    ])
    expect(wrapper.getComponent(LineChart).props('gapDays')).toBe(1)
    const table = wrapper.get('details')
    expect(table.attributes('open')).toBeUndefined()
    expect(table.text()).toContain('0 шагов')
    expect(table.text()).not.toContain('999 шагов')
    expect(table.text()).not.toContain('NaN')
    const blankDay = table.findAll('div.divide-y > div')[3]!
    expect(blankDay.text()).toContain('Нет данных')
  })

  it('requests only the selected subject and withholds Personal queries without its read scope', async () => {
    scopes.value = []
    const wrapper = screen()
    await flushPromises()
    expect(transport.get).not.toHaveBeenCalled()
    expect(wrapper.findComponent(LineChart).exists()).toBe(false)
    await wrapper.setProps({ subject: 'sessions' })
    await flushPromises()
    expect(transport.get.mock.calls.map(([url]) => url)).toEqual([
      '/v1/activity/summary?from=2026-10-01&to=2026-10-06',
    ])
    expect(wrapper.find('[data-testid="running-workouts-summary"]').exists()).toBe(false)
  })

  it('keeps a conflicting decision visible while withholding its amount in graph, rows and details', async () => {
    daily = [
      {
        date: RUNNING_DAY,
        kind: 'steps',
        status: 'available',
        value: 999,
        resolution: runningDecision({ status: 'conflict' }),
      },
    ]
    const wrapper = screen()
    await flushPromises()
    expect(wrapper.findComponent(LineChart).exists()).toBe(false)
    expect(wrapper.get('details').text()).toContain('Показания не согласованы')
    expect(wrapper.find('[data-testid="movement-day-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="movement-day-status"]').attributes('data-status')).toBe(
      'conflict',
    )
  })

  it('keeps loaded points visible with a warning when refreshing the history failed', async () => {
    const wrapper = screen()
    await flushPromises()
    const original = wrapper.getComponent(LineChart).props('points')
    transport.get.mockRejectedValueOnce(new Error('Synthetic refresh failure'))
    await clients.at(-1)!.refetchQueries({ queryKey: ['personal', 'history'] })
    await flushPromises()
    expect(wrapper.getComponent(LineChart).props('points')).toEqual(original)
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(true)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
  })

  it('switches subject and period in place without presenting the previous query as new data', async () => {
    const wrapper = screen()
    await flushPromises()
    let resolve!: (value: unknown) => void
    transport.get.mockImplementationOnce(
      () =>
        new Promise((settled) => {
          resolve = settled
        }),
    )
    await wrapper.setProps({ subject: 'active_minutes' })
    await flushPromises()
    expect(wrapper.find('[data-state="loading"]').exists()).toBe(true)
    expect(wrapper.findComponent(LineChart).exists()).toBe(false)
    resolve(historyReply('/v1/personal/history?from=2026-10-01&to=2026-10-06&kind=active_minutes'))
    await flushPromises()
    expect(wrapper.getComponent(LineChart).props('points')).toEqual([
      { x: atDay(RUNNING_DAY), y: 30 },
    ])
    expect(wrapper.getComponent(LineChart).props('formatY')(30)).toBe('30 мин')
    await wrapper.setProps({ range: { from: '2026-10-01', to: '2026-10-04', kind: null } })
    await flushPromises()
    expect(wrapper.findComponent(LineChart).exists()).toBe(false)
    expect(wrapper.text()).not.toContain('30 мин')
    expect(transport.get.mock.calls.map(([url]) => url)).toEqual([
      '/v1/personal/history?from=2026-10-01&to=2026-10-06&kind=steps',
      '/v1/personal/history?from=2026-10-01&to=2026-10-06&kind=active_minutes',
      '/v1/personal/history?from=2026-10-01&to=2026-10-04&kind=active_minutes',
    ])
  })

  it('shows independently loaded device reports when the manual summary failed', async () => {
    manualError = true
    const wrapper = screen('sessions')
    await flushPromises()
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="device-workout-count"]').text()).toContain('3')
  })

  it('keeps manual statistics when loading device reports failed', async () => {
    deviceError = true
    const wrapper = screen('sessions')
    await flushPromises()
    expect(wrapper.findComponent(LineChart).exists()).toBe(true)
    expect(
      wrapper.get('[data-testid="running-workouts-summary"]').find('[role="alert"]').exists(),
    ).toBe(true)
    expect(wrapper.get('[data-testid="activity-dynamics-sessions"]').text()).toContain('30 мин')
  })

  it('excludes unknown durations from the duration graph and keeps exact day navigation', async () => {
    const wrapper = screen('sessions')
    await flushPromises()
    await wrapper.get('select').setValue('duration_minutes')
    expect(wrapper.getComponent(LineChart).props('points')).toEqual([
      { x: atDay('2026-10-03'), y: 30 },
    ])
    const missing = wrapper.get('tbody').findAll('tr').at(-1)!
    expect(missing.text()).toContain('—')
    await missing.get('button').trigger('click')
    expect(wrapper.emitted('go')).toEqual([['2026-10-05']])
  })

  it('does not query or display running-device statistics for a strength-only period', async () => {
    const wrapper = screen('sessions', { ...range, kind: 'strength' })
    await flushPromises()
    expect(wrapper.find('[data-testid="running-workouts-summary"]').exists()).toBe(false)
    expect(
      transport.get.mock.calls.every(([url]) => !String(url).startsWith('/v1/personal/')),
    ).toBe(true)
  })

  it('inherits and reactively updates distance units without refetching canonical statistics', async () => {
    const units = ref<DisplayUnits>('imperial')
    const host = defineComponent({
      setup() {
        provideDisplayUnits(units)
        return () =>
          h(ActivityDynamics, {
            subject: 'sessions',
            range,
            today: RUNNING_DAY,
            timezone: 'Europe/Moscow',
          })
      },
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
    })
    clients.push(client)
    const wrapper = mount(host, {
      global: {
        plugins: [[VueQueryPlugin, { queryClient: client }], i18n],
        stubs: { LineChart: true },
      },
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.get('details > p.mt-2').text()).toContain('3,11 мили')
    const requests = transport.get.mock.calls.length

    units.value = 'metric'
    await flushPromises()
    expect(wrapper.get('details > p.mt-2').text()).toContain('5 км')
    expect(transport.get).toHaveBeenCalledTimes(requests)
  })
})
