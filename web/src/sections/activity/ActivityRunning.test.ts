import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { i18n } from '@/i18n'
import ActivityView from './ActivityView.vue'
import { RUNNING_DAY, runningPage, stepDay, workoutDay } from './runningFixtures'

const transport = vi.hoisted(() => ({
  get: vi.fn(),
  scopes: [] as string[],
  failContract: false,
  failManual: false,
  displayUnits: 'metric' as 'metric' | 'imperial',
}))
vi.mock('@/core/client', () => ({ api: { get: transport.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'synthetic-user', timezone: 'Europe/Moscow' },
    can: (scope: string) => transport.scopes.includes(scope),
  }),
}))
vi.mock('@/core/useCalendarDay', () => ({ useCalendarDay: () => ref('2026-10-06') }))
vi.mock('@/components/Panel.vue', () => ({
  default: {
    props: ['open', 'title'],
    template: '<section v-if="open" role="dialog"><h2>{{ title }}</h2><slot /></section>',
  },
}))
const ledger = () => ({
  unresolved: ref([]),
  describe: vi.fn(),
  check: vi.fn(),
  resend: vi.fn(),
  abandon: vi.fn(),
  onResolved: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
})
vi.mock('./useActivity', async (original) => ({
  ...(await original<typeof import('./useActivity')>()),
  useActivity: () => ledger(),
}))
vi.mock('@/sections/personal/usePersonal', async (original) => ({
  ...(await original<typeof import('@/sections/personal/usePersonal')>()),
  usePersonal: () => ledger(),
}))

const manual = {
  id: 'manual',
  revision: 1,
  kind: 'walking',
  title: 'Synthetic walk',
  date: RUNNING_DAY,
  timezone: 'UTC',
  started_at: `${RUNNING_DAY}T08:00:00Z`,
  duration_minutes: 30,
  distance_km: 2,
  exercises: [],
}
let manualEntries = [manual]
const wrappers: Array<{ unmount(): void }> = []
const clients: QueryClient[] = []
beforeEach(() => {
  transport.scopes = ['activity.read', 'personal.read']
  transport.failContract = false
  transport.failManual = false
  transport.displayUnits = 'metric'
  manualEntries = [manual]
  transport.get.mockReset()
  transport.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/v1/activity/sessions')) {
      if (transport.failManual) throw new Error('synthetic manual diary unavailable')
      const kind = new URL(path, 'https://synthetic.test').searchParams.get('kind')
      return { sessions: manualEntries.filter((entry) => !kind || entry.kind === kind) }
    }
    if (path.startsWith('/v1/activity/summary'))
      return {
        from: '2026-09-07',
        to: RUNNING_DAY,
        totals: {
          sessions: manualEntries.length,
          duration_minutes: 30,
          missing_duration_sessions: 0,
          distance_km_by_kind: {},
          working_sets: 0,
        },
        daily: [],
      }
    if (path === '/v1/activity/exercises') return { exercises: [] }
    if (path.startsWith('/v1/personal/history'))
      return { from: '2026-09-07', to: RUNNING_DAY, daily: [], records: [] }
    if (path.startsWith('/v1/personal/overview')) {
      const date = new URL(path, 'https://synthetic.test').searchParams.get('date') ?? RUNNING_DAY
      const metric = { status: 'no_data', value: null, unit: 'min' }
      return {
        date,
        timezone: 'Europe/Moscow',
        profile: {
          user_id: 'synthetic-user',
          timezone: 'Europe/Moscow',
          display_units: transport.displayUnits,
          revision: 1,
        },
        metrics: {
          weight: { ...metric, kind: 'weight' },
          steps: { ...metric, kind: 'steps', status: 'available', value: '2400', unit: 'steps' },
          sleep: { ...metric, kind: 'sleep' },
          active_minutes: { ...metric, kind: 'active_minutes' },
        },
        preferences: {},
        sources: [],
      }
    }
    if (path.startsWith('/v1/personal/resolved')) {
      if (transport.failContract) throw new ApiError(503, null, null, null, null)
      return path.includes('metric=running_workouts')
        ? runningPage([workoutDay()])
        : path.includes('metric=running_steps')
          ? runningPage([stepDay()])
          : runningPage()
    }
    throw new Error(`Unexpected synthetic request: ${path}`)
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clients.splice(0).forEach((client) => client.clear())
})
async function screen(query = '?tab=journal') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/activity', name: 'activity', component: ActivityView },
      {
        path: '/activity/session/:session',
        name: 'activity-session',
        component: { template: '<div />' },
      },
      { path: '/personal/history', name: 'personal-history', component: { template: '<div />' } },
      { path: '/personal/sources', name: 'personal-sources', component: { template: '<div />' } },
    ],
  })
  await router.push(`/activity${query}`)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const wrapper = mount(ActivityView, {
    global: {
      plugins: [i18n, router, [VueQueryPlugin, { queryClient: client }]],
      stubs: { PageHeader: { template: '<header><slot /><slot name="actions" /></header>' } },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

async function openData(wrapper: Awaited<ReturnType<typeof screen>>['wrapper']) {
  await wrapper.get('[data-testid="activity-data-details"]').trigger('click')
  await flushPromises()
}

const deviceRow = '[data-testid="activity-entry-row"][data-origin="device"]'

describe('Activity uses independent Personal running decisions', () => {
  it('keeps manual journal usable and makes no Personal request without personal.read', async () => {
    transport.scopes = ['activity.read']
    const { wrapper } = await screen()
    expect(wrapper.text()).toContain('Synthetic walk')
    expect(transport.get.mock.calls.some(([path]) => path.startsWith('/v1/personal/'))).toBe(false)
    expect(wrapper.find('[data-testid="running-workouts-summary"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="today-steps-card"]').exists()).toBe(false)
  })
  it('combines readonly device rows and manual sessions, disclosing the full device report on demand', async () => {
    const { wrapper } = await screen()
    expect(wrapper.text()).toContain('Synthetic walk')
    expect(wrapper.get(deviceRow).element.tagName).toBe('BUTTON')
    expect(wrapper.get(deviceRow).text()).toContain('Synthetic ring')
    expect(wrapper.find('[data-testid="device-workout-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="running-workouts-summary"]').exists()).toBe(false)
    expect(
      wrapper
        .findAll('[data-testid="activity-entry-row"]')
        .map((row) => row.attributes('data-origin')),
    ).toEqual(['manual', 'device'])
    await wrapper.get(deviceRow).trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="device-workout-card"]').text()).toContain('Источник: Кольцо')
    expect(wrapper.findAll('[data-testid="device-workout-card"] a')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="device-workout-card"] [style]')).toHaveLength(0)
  })
  it('clears open Personal snapshots on revocation and does not reopen them when access returns', async () => {
    transport.scopes = reactive(['activity.read', 'personal.read'])
    const { wrapper } = await screen()
    await wrapper.get(deviceRow).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="device-workout-card"]').exists()).toBe(true)

    transport.scopes.splice(transport.scopes.indexOf('personal.read'), 1)
    await flushPromises()
    expect(wrapper.find('[data-testid="device-workout-card"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find(deviceRow).exists()).toBe(false)
    expect(wrapper.text()).toContain('Synthetic walk')

    transport.scopes.push('personal.read')
    await flushPromises()
    expect(wrapper.find(deviceRow).exists()).toBe(true)
    expect(wrapper.find('[data-testid="device-workout-card"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    await openData(wrapper)
    expect(wrapper.find('[data-testid="running-steps-card"]').exists()).toBe(true)
    transport.scopes.splice(transport.scopes.indexOf('personal.read'), 1)
    await flushPromises()
    expect(wrapper.find('[data-testid="running-steps-card"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Synthetic walk')

    transport.scopes.push('personal.read')
    await flushPromises()
    expect(wrapper.find('[data-testid="running-steps-card"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
  it('starts the overview with day movement and keeps the independent running counters in details', async () => {
    const { wrapper } = await screen('?tab=overview')
    expect(wrapper.find('[data-testid="today-steps-card"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="day-value-steps"]').text()).toBe('2 400')
    expect(wrapper.find('[data-testid="running-steps-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="running-workouts-summary"]').exists()).toBe(false)
    expect(transport.get.mock.calls.some(([path]) => path.startsWith('/v1/activity/summary'))).toBe(
      false,
    )
    await openData(wrapper)
    expect(wrapper.get('[data-testid="running-steps-value"]').text()).toBe('420 шагов')
    expect(wrapper.get('[data-testid="device-workout-count"]').text()).toContain('1')
    expect(wrapper.text()).toContain('Отчёты устройств и ручные занятия учитываются отдельно')
  })
  it('does not call an imported-only overview empty', async () => {
    manualEntries = []
    const { wrapper } = await screen('?tab=overview')
    expect(wrapper.find(deviceRow).exists()).toBe(true)
    expect(wrapper.text()).not.toContain('За эти дни занятий нет')
    expect(wrapper.text()).not.toContain('Пока нет занятий')
  })
  it('bounds recent entries rather than groups of dates and preserves the full journal', async () => {
    manualEntries = Array.from({ length: 5 }, (_, index) => ({
      ...manual,
      id: `manual-${index}`,
      title: `Synthetic walk ${index}`,
      started_at: `${RUNNING_DAY}T${String(8 + index).padStart(2, '0')}:00:00Z`,
    }))
    const { wrapper, router } = await screen('?tab=overview')
    expect(wrapper.findAll('[data-testid="activity-entry-row"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('Synthetic walk 4')
    expect(wrapper.text()).not.toContain('Synthetic walk 0')
    await router.replace('/activity?tab=journal')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="activity-entry-row"]')).toHaveLength(6)
  })
  it('keeps first-page device rows and manual sessions when fetching more reports fails', async () => {
    const original = transport.get.getMockImplementation()!
    transport.get.mockImplementation(async (path: string) => {
      if (path.includes('metric=running_workouts')) {
        if (path.includes('cursor=older-reports')) throw new ApiError(503, null, null, null, null)
        return { ...runningPage([workoutDay()]), next_cursor: 'older-reports' }
      }
      return original(path)
    })
    const { wrapper } = await screen()
    const more = wrapper.findAll('button').find((button) => button.text() === 'Загрузить ещё')
    expect(more).toBeDefined()
    await more!.trigger('click')
    await flushPromises()
    expect(wrapper.find(deviceRow).exists()).toBe(true)
    expect(wrapper.text()).toContain('Synthetic walk')
    expect(wrapper.text()).toContain(i18n.global.t('ring.nextPageFailed'))
  })
  it('applies the running filter without turning manual walking into a device run', async () => {
    const { wrapper, router } = await screen('?tab=journal&kind=walking')
    expect(wrapper.text()).toContain('Synthetic walk')
    expect(wrapper.find(deviceRow).exists()).toBe(false)
    await router.replace('/activity?tab=journal&kind=running')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Synthetic walk')
    expect(wrapper.find(deviceRow).exists()).toBe(true)
  })
  it('keeps manual diary intact when the running decisions fail to load', async () => {
    transport.failContract = true
    const { wrapper } = await screen()
    expect(wrapper.text()).toContain('Synthetic walk')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.find(deviceRow).exists()).toBe(false)
  })
  it('does not hide device cards when the independent manual diary fails', async () => {
    transport.failManual = true
    const { wrapper } = await screen()
    expect(wrapper.find(deviceRow).exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  })
  it.each(['manual', 'device'] as const)(
    'keeps the %s journal error visible while retrying its initial load',
    async (source) => {
      transport.failManual = source === 'manual'
      transport.failContract = source === 'device'
      const { wrapper } = await screen()
      const alert = wrapper.get('[role="alert"]')
      const element = alert.element
      expect(alert.find('.animate-spin').exists()).toBe(false)

      transport.failManual = false
      transport.failContract = false
      let answer: () => void = () => undefined
      const response = new Promise<void>((resolve) => (answer = resolve))
      const original = transport.get.getMockImplementation()!
      transport.get.mockImplementation(async (path: string) => {
        if (
          source === 'manual'
            ? path.startsWith('/v1/activity/sessions')
            : path.includes('metric=running_workouts')
        )
          await response
        return original(path)
      })
      await alert.get('button').trigger('click')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').element).toBe(element)
      expect(alert.get('button').attributes('disabled')).toBeDefined()
      expect(alert.find('.animate-spin').exists()).toBe(true)
      expect(wrapper.find('[data-state="loading"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="activity-empty-journal"]').exists()).toBe(false)

      answer()
      await flushPromises()
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('Synthetic walk')
      expect(wrapper.find(deviceRow).exists()).toBe(true)
    },
  )
  it('applies the profile distance units to manual and device rows and the opened report', async () => {
    transport.displayUnits = 'imperial'
    const { wrapper } = await screen()
    expect(wrapper.get('[data-origin="manual"]').text()).toContain('1,24 мили')
    expect(wrapper.get(deviceRow).text()).toContain('3,11 мили')
    await wrapper.get(deviceRow).trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="device-workout-card"]').text()).toContain('3,11 мили')
    expect(manualEntries[0]!.distance_km).toBe(2)
  })

  it('preserves measured zero distance when displaying imperial units', async () => {
    transport.displayUnits = 'imperial'
    manualEntries = [{ ...manual, distance_km: 0 }]
    const { wrapper } = await screen()
    expect(wrapper.get('[data-origin="manual"]').text()).toContain('0 мили')
  })
})
