import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { affectedDomains } from '@/core/topics'
import { ActivitySession } from './api'
import DeviceWorkoutCard from './DeviceWorkoutCard.vue'
import RunningStepsCard from './RunningStepsCard.vue'
import RunningWorkoutsSummary from './RunningWorkoutsSummary.vue'
import { journalGroups, sessionWindow, useRunningData, workoutEntries } from './running'
import {
  RUNNING_DAY,
  runningDecision,
  runningPage,
  stepDay,
  workoutDay,
  workoutEpisode,
} from './runningFixtures'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
const wrappers: Array<{ unmount(): void }> = []
const queries: QueryClient[] = []
const common = { today: RUNNING_DAY, timezone: 'Europe/Moscow', loading: false }
const screen = (page = runningPage([stepDay()])) => {
  const wrapper = mount(RunningStepsCard, {
    props: { ...common, date: RUNNING_DAY, page },
    global: { plugins: [i18n] },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  transport.get.mockReset()
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  queries.splice(0).forEach((client) => client.clear())
})

describe('running daily counter presentation', () => {
  it('shows the counter, reading time and unconfirmed day independently from ordinary steps', () => {
    const wrapper = screen()
    expect(wrapper.get('[data-testid="running-steps-value"]').text()).toBe('420 шагов')
    expect(wrapper.text()).toContain('Прочитано: сегодня, 11:00')
    expect(wrapper.get('[data-testid="running-steps-partial"]').text()).toContain(
      'Полнота дня не подтверждена',
    )
    expect(wrapper.text()).toContain('не прибавляются к общим шагам')
    expect(wrapper.get('[data-testid="resolution-explanation"]').text()).toContain(
      'Формат протокола ещё не подтверждён',
    )
    expect(wrapper.findAll('[style]')).toHaveLength(0)
  })
  it('preserves a measured zero and does not turn an absent report into zero', async () => {
    const wrapper = screen(runningPage([stepDay(0)]))
    expect(wrapper.get('[data-testid="running-steps-value"]').text()).toBe('0 шагов')
    await wrapper.setProps({ page: runningPage([]) })
    expect(wrapper.find('[data-testid="running-steps-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="running-steps-status"]').text()).toBe('Нет данных')
  })
  it('does not present a conflicting decision as a usable count', () => {
    const wrapper = screen(
      runningPage([
        stepDay(900, {
          status: 'conflict',
          resolution: runningDecision({
            status: 'conflict',
            alternatives: [{ id: 'reset', reason: 'counter_reset' }],
          }),
        }),
      ]),
    )
    expect(wrapper.find('[data-testid="running-steps-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="running-steps-status"]').attributes('data-status')).toBe(
      'conflict',
    )
    expect(wrapper.text()).toContain('Счётчик сброшен')
  })
  it('does not promote a snapshot to a complete past day', async () => {
    const wrapper = screen()
    await wrapper.setProps({ today: '2026-10-07' })
    expect(wrapper.find('[data-testid="running-steps-partial"]').exists()).toBe(true)
  })
  it('rejects the wrong selected date', async () => {
    const wrapper = screen()
    await wrapper.setProps({ date: '2026-10-05' })
    expect(wrapper.find('[data-testid="running-steps-value"]').exists()).toBe(false)
  })
})

describe('whole device workouts', () => {
  it('reads selected series beyond bounded daily explanations', () => {
    const page = runningPage([workoutDay([workoutEpisode('first')])])
    page.series.push(workoutEpisode('second'))
    expect(workoutEntries(page).map((item) => item.id)).toEqual(['first', 'second'])
  })
  it('keeps a selected UTC-source workout on the server day after the profile timezone changes across midnight', () => {
    const page = runningPage([
      workoutDay([
        workoutEpisode('late-run', {
          start_at: '2026-10-06T23:30:00Z',
          end_at: '2026-10-06T23:55:00Z',
          local_date: '2026-10-06',
          timezone: 'UTC',
        }),
      ]),
    ])
    const [report] = workoutEntries(page)
    expect(report?.date).toBe('2026-10-06')
    expect(report?.local_date).toBe('2026-10-06')
    expect(report?.timezone).toBe('UTC')
    expect(report?.source_id).toBe('ring')
    const manual = ActivitySession.parse({
      id: 'next-day-manual',
      revision: 1,
      kind: 'walking',
      date: '2026-10-07',
      timezone: 'Europe/Moscow',
      started_at: '2026-10-07T09:00:00+03:00',
      duration_minutes: 30,
      distance_km: null,
      exercises: [],
    })
    expect(
      journalGroups([manual], [report!], null).map(([date, entries]) => [date, entries[0]?.type]),
    ).toEqual([
      ['2026-10-07', 'manual'],
      ['2026-10-06', 'device'],
    ])
    const wrapper = mount(DeviceWorkoutCard, {
      props: { report: report!, timezone: common.timezone, today: common.today },
      global: { plugins: [i18n] },
    })
    wrappers.push(wrapper)
    expect(wrapper.text()).toContain('23:30 — 23:55')
    expect(wrapper.get('[data-testid="device-workout-timezone"]').text()).toContain('UTC')
    expect(wrapper.text()).not.toContain('02:30')
  })
  it('uses an exact selected fact revision to assign its authoritative server day', () => {
    const page = runningPage([workoutDay([workoutEpisode('exact', { local_date: '2026-10-05' })])])
    expect(workoutEntries(page)[0]?.date).toBe(RUNNING_DAY)
    expect(workoutEntries(page)[0]?.local_date).toBe('2026-10-05')
  })
  it('uses the validated source date for selected series rows beyond compacted references', () => {
    const page = runningPage([workoutDay([workoutEpisode('retained')])])
    page.daily[0]!.resolution.selected = []
    page.daily[0]!.resolution.explanation_truncated = true
    page.series = [
      workoutEpisode('late-run', {
        start_at: '2026-10-06T23:30:00Z',
        end_at: '2026-10-06T23:55:00Z',
        local_date: RUNNING_DAY,
        timezone: 'UTC',
      }),
    ]
    expect(workoutEntries(page)[0]?.date).toBe(RUNNING_DAY)
  })
  it('does not guess running from unknown sport fields', () => {
    const page = runningPage([
      workoutDay([
        workoutEpisode('unknown', {
          data: { sport_type: 'unknown', steps: 9000, duration_seconds: 900 },
        }),
      ]),
    ])
    expect(workoutEntries(page)).toEqual([])
  })
  it('renders source, whole interval and verified fields without edit links or cadence/raw distance', () => {
    const [report] = workoutEntries(
      runningPage([
        workoutDay([
          workoutEpisode('raw', {
            data: {
              sport_type: 'running',
              steps: 0,
              distance_raw: 900,
              heart_rate_frequency_minutes: 5,
            },
          }),
        ]),
      ]),
    )
    const wrapper = mount(DeviceWorkoutCard, {
      props: { report: report!, timezone: common.timezone, today: common.today },
      global: { plugins: [i18n] },
    })
    wrappers.push(wrapper)
    expect(wrapper.text()).toContain('Источник: Кольцо')
    expect(wrapper.text()).toContain('10:00 — 10:30')
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).toContain(
      'Длительность не сообщена',
    )
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).toContain('0 шагов')
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).not.toContain('км')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.findAll('[style]')).toHaveLength(0)
  })
  it('keeps empty history unverified without inventing a device reading', async () => {
    const wrapper = mount(RunningWorkoutsSummary, {
      props: { ...common, page: runningPage([]) },
      global: { plugins: [i18n] },
    })
    wrappers.push(wrapper)
    expect(wrapper.get('[data-testid="device-workout-status"]').attributes('data-status')).toBe(
      'unverified',
    )
    expect(wrapper.text()).toContain('ещё не проверена')
  })
  it('shows exact server count and decimal-encoded duration independently of loaded rows', () => {
    const wrapper = mount(RunningWorkoutsSummary, {
      props: {
        ...common,
        page: runningPage([
          workoutDay([workoutEpisode()], {
            value: 127,
            resolution: runningDecision({
              summary: { count: 127, known_duration_seconds: '3600', unknown_duration_count: 1 },
              episodes_count: 127,
              explanation_truncated: true,
            }),
          }),
        ]),
      },
      global: { plugins: [i18n] },
    })
    wrappers.push(wrapper)
    expect(wrapper.get('[data-testid="device-workout-count"]').text()).toContain('127')
    expect(wrapper.get('[data-testid="device-workout-duration"]').text()).toContain('1 ч 0 мин')
    expect(wrapper.get('[data-testid="device-workout-duration"]').text()).toContain(
      'Без длительности: 1',
    )
  })
  it('shows conflicting workout decisions separately from unavailable history', () => {
    const wrapper = mount(RunningWorkoutsSummary, {
      props: {
        ...common,
        page: runningPage([
          workoutDay([], {
            status: 'conflict',
            resolution: runningDecision({ status: 'conflict', episodes: [] }),
          }),
        ]),
      },
      global: { plugins: [i18n] },
    })
    wrappers.push(wrapper)
    expect(wrapper.get('[data-testid="workout-conflict"]').text()).toContain(
      'Показания не согласованы',
    )
    expect(wrapper.find('[data-testid="device-workout-status"]').exists()).toBe(false)
  })
  it('merges the readonly device timeline with manual sessions without altering either summary', () => {
    const manual = ActivitySession.parse({
      id: 'manual',
      revision: 1,
      kind: 'walking',
      date: RUNNING_DAY,
      timezone: 'UTC',
      started_at: `${RUNNING_DAY}T08:00:00Z`,
      duration_minutes: 30,
      distance_km: 2,
      exercises: [],
    })
    const reports = workoutEntries(runningPage([workoutDay()]))
    expect(journalGroups([manual], reports, null)[0]?.[1].map((item) => item.type)).toEqual([
      'manual',
      'device',
    ])
    expect(journalGroups([manual], reports, 'walking')[0]?.[1].map((item) => item.type)).toEqual([
      'manual',
    ])
    expect(manual.duration_minutes).toBe('30')
  })
})

async function hook(enabled = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  queries.push(client)
  let running!: ReturnType<typeof useRunningData>
  const wrapper = mount(
    defineComponent({
      setup() {
        running = useRunningData(ref({ from: '2026-10-01', to: RUNNING_DAY }), ref(enabled))
        return () => null
      },
    }),
    { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } },
  )
  wrappers.push(wrapper)
  await flushPromises()
  return { running, client }
}

describe('workouts found in a strap history', () => {
  const found = workoutEpisode('found', {
    kind: 'workouts',
    source_name: 'WHOOP',
    start_at: `${RUNNING_DAY}T09:00:00Z`,
    end_at: `${RUNNING_DAY}T09:25:00Z`,
    origin: { source: 'history_r18', provider: 'whoop' },
    data: {
      sport_type: 'workout',
      duration_seconds: 1500,
      steps: 40,
      distance_meters: null,
      heart_rate: { mean: 150, max: 171 },
      zone_seconds: [0, 120, 600, 660, 120],
      trimp: '61.4',
      effort: { rest: '52.5', max: '182.8', basis: 'tanaka' },
    },
  })
  const page = () => runningPage([workoutDay([found], { kind: 'workouts' })])

  it('joins the journal only unfiltered, never as a run', () => {
    const reports = workoutEntries(page(), 'workouts')
    expect(reports.map((report) => report.id)).toEqual(['found'])
    expect(workoutEntries(page())).toEqual([])
    expect(journalGroups([], reports, null)[0]?.[1]).toHaveLength(1)
    expect(journalGroups([], reports, 'running')).toEqual([])
  })

  it('folds into a logged session it falls in', () => {
    const reports = workoutEntries(page(), 'workouts')
    const logged = {
      id: 'gym',
      revision: 1,
      kind: 'strength',
      date: RUNNING_DAY,
      timezone: 'Europe/Moscow',
      started_at: `${RUNNING_DAY}T08:50:00Z`,
      duration_minutes: 60,
      distance_km: null,
      exercises: [],
    }
    const gym = ActivitySession.parse(logged)
    expect(journalGroups([gym], reports, null)[0]?.[1].map((entry) => entry.type)).toEqual([
      'manual',
    ])
    // Without a start time a session claims nothing.
    const untimed = ActivitySession.parse({ ...logged, started_at: null })
    expect(sessionWindow(untimed)).toBeNull()
    expect(
      journalGroups([untimed], reports, null)[0]?.[1]
        .map((entry) => entry.type)
        .sort(),
    ).toEqual(['device', 'manual'])
  })

  it('shows its heart rate, zones and load on the card', () => {
    const [report] = workoutEntries(page(), 'workouts')
    const wrapper = mount(DeviceWorkoutCard, {
      props: { report: report!, timezone: 'Europe/Moscow', today: RUNNING_DAY },
      global: { plugins: [i18n] },
    })
    wrappers.push(wrapper)
    expect(wrapper.get('p').text()).toBe('Тренировка')
    expect(wrapper.text()).toContain('Распознано по пульсу и шагам браслета · WHOOP')
    expect(wrapper.get('[data-testid="device-workout-heart-rate"]').text()).toBe(
      'Пульс: средний 150, максимум 171 уд/мин',
    )
    // Zones reached, hardest first.
    expect(
      wrapper
        .findAll('[data-testid="device-workout-zones"] li')
        .map((item) => item.findAll('span').map((part) => part.text())),
    ).toEqual([
      ['Зона 5 · 90–100% резерва', '2 мин'],
      ['Зона 4 · 80–90% резерва', '11 мин'],
      ['Зона 3 · 70–80% резерва', '10 мин'],
      ['Зона 2 · 60–70% резерва', '2 мин'],
    ])
    expect(wrapper.get('[data-testid="device-workout-load"]').text()).toBe('Нагрузка (TRIMP): 61')
    // A finished length in whole minutes reads without «0 с».
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).toContain('25 мин')
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).not.toContain('0 с')
    // In a panel that already names it, the card does not repeat the name.
    const inPanel = mount(DeviceWorkoutCard, {
      props: { report: report!, timezone: 'Europe/Moscow', today: RUNNING_DAY, heading: false },
      global: { plugins: [i18n] },
    })
    wrappers.push(inPanel)
    expect(inPanel.get('p').text()).toContain('Распознано по пульсу и шагам браслета')
    expect(wrapper.text()).toContain('максимальный 183 уд/мин (по возрасту)')
  })
})

describe('Personal running read guards and cache domain', () => {
  it('does not read Personal without permission', async () => {
    await hook(false)
    expect(transport.get).not.toHaveBeenCalled()
  })
  it('refreshes both metrics with personal.changed rather than activity events', async () => {
    transport.get.mockImplementation(async (path: string) =>
      path.includes('metric=running_steps')
        ? runningPage([stepDay()])
        : path.includes('metric=running_workouts')
          ? runningPage([workoutDay()])
          : runningPage(),
    )
    const { running, client } = await hook()
    expect(running.reports.value).toHaveLength(1)
    expect(transport.get.mock.calls.map(([path]) => path)).toEqual(
      expect.arrayContaining([
        '/v1/personal/resolved?from=2026-10-01&to=2026-10-06&metric=running_steps&limit=1',
        '/v1/personal/resolved?from=2026-10-01&to=2026-10-06&metric=running_workouts&limit=100',
      ]),
    )
    transport.get.mockClear()
    expect(affectedDomains('personal.changed')).toContain('personal')
    await client.invalidateQueries({ queryKey: ['personal'] })
    await flushPromises()
    expect(transport.get.mock.calls.some(([path]) => path.includes('metric=running_steps'))).toBe(
      true,
    )
    expect(
      transport.get.mock.calls.some(([path]) => path.includes('metric=running_workouts')),
    ).toBe(true)
  })
  it('follows selected series pagination without dropping existing workouts after a page error', async () => {
    let fail = false
    transport.get.mockImplementation(async (path: string) => {
      if (!path.includes('metric=running_workouts')) return runningPage()
      const page = runningPage([workoutDay([workoutEpisode('first')])])
      if (path.includes('cursor=next')) {
        if (fail) throw new Error('synthetic second page failed')
        page.series = [workoutEpisode('second')]
      } else page.next_cursor = 'next'
      return page
    })
    const { running } = await hook()
    expect(running.reports.value.map((report) => report.id)).toEqual(['first'])
    fail = true
    await running.workouts.fetchNextPage()
    expect(running.workouts.isFetchNextPageError.value).toBe(true)
    expect(running.reports.value.map((report) => report.id)).toEqual(['first'])
    fail = false
    await running.workouts.fetchNextPage()
    expect(running.reports.value.map((report) => report.id)).toEqual(['first', 'second'])
    expect(transport.get.mock.calls.some(([path]) => path.includes('cursor=next'))).toBe(true)
  })
  it('does not combine series pages from different input fingerprints', async () => {
    transport.get.mockImplementation(async (path: string) => {
      if (!path.includes('metric=running_workouts')) return runningPage()
      const page = runningPage([workoutDay([workoutEpisode('first')])])
      if (path.includes('cursor=next')) {
        page.fingerprint = 'changed'
        page.series = [workoutEpisode('second')]
      } else page.next_cursor = 'next'
      return page
    })
    const { running } = await hook()
    await running.workouts.fetchNextPage()
    expect(running.consistent.value).toBe(false)
    expect(running.reports.value).toEqual([])
  })
})
