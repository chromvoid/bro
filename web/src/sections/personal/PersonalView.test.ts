import { mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { Observation, Overview, SourceMetric } from './api'
import { CaliperHistory, CaliperMeasurement } from './caliper'
import PersonalView from './PersonalView.vue'
import { sleepDecision } from './resolverFixtures'
import { SleepAnalysis } from './resolutionContract'
import sleepAnalysisFixture from './sleepAnalysis.fixture.json'

const state = ref<Overview>()
const mobile = ref(false)
const caliperState = ref<ReturnType<typeof CaliperHistory.parse>>()
const caliperPending = ref(false)
const caliperLoadingError = ref(false)
const caliperRefreshError = ref(false)
vi.mock('@vueuse/core', async (original) => ({
  ...(await original<typeof import('@vueuse/core')>()),
  useMediaQuery: (query: string) => (query === '(max-width: 767px)' ? mobile : ref(false)),
}))
vi.mock('@tanstack/vue-query', async (original) => ({
  ...(await original<typeof import('@tanstack/vue-query')>()),
  useIsFetching: () => ref(0),
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'Europe/Moscow' }, can: () => true }),
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('@/core/intent', () => ({ useQueryIntent: vi.fn() }))
vi.mock('./caliper', async (original) => ({
  ...(await original<typeof import('./caliper')>()),
  useCaliperHistory: () => ({
    data: caliperState,
    isPending: caliperPending,
    isLoadingError: caliperLoadingError,
    isRefetchError: caliperRefreshError,
  }),
}))
vi.mock('./usePersonal', () => ({
  useOverview: () => ({
    data: state,
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
  }),
  useHistory: () => ({
    data: ref(),
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
  }),
  usePersonal: () => ({
    unresolved: ref([]),
    describe: vi.fn(),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))

const wrappers: VueWrapper[] = []
function show() {
  const wrapper = mount(PersonalView, {
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: {
        SensorDaySummary: true,
        SourceLine: true,
        RouterLink: RouterLinkStub,
        DashboardWeight: true,
        DashboardBody: true,
        DashboardNutrition: true,
        CaliperPanel: true,
        WeightPanel: true,
        UnresolvedWrites: true,
        PageHeader: true,
        DayNav: true,
        Panel: { props: ['open'], template: '<aside v-if="open"><slot /></aside>' },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T10:00:00Z'))
  mobile.value = false
  caliperState.value = undefined
  caliperPending.value = false
  caliperLoadingError.value = false
  caliperRefreshError.value = false
  state.value = Overview.parse({
    date: '2026-10-05',
    timezone: 'Europe/Moscow',
    profile: { user_id: 'synthetic', timezone: 'Europe/Moscow', revision: 1 },
    sources: [],
    preferences: {},
    metrics: {
      sleep: {
        kind: 'sleep',
        status: 'available',
        value: null,
        unit: 'min',
        complete: false,
        resolution: sleepDecision(),
      },
      weight: { kind: 'weight', status: 'no_data', value: null, unit: 'kg' },
      steps: { kind: 'steps', status: 'available', value: '500', unit: 'steps' },
      active_minutes: { kind: 'active_minutes', status: 'available', value: '20', unit: 'min' },
    },
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})

function representedCaliper() {
  const observation = Observation.parse({
    id: 'synthetic-caliper-fat',
    source_id: 'synthetic-caliper',
    kind: 'body_fat_percent',
    start_at: '2026-10-05T08:00:00Z',
    end_at: '2026-10-05T08:00:00Z',
    timezone: 'Europe/Moscow',
    method: 'derived',
    data: { value: '18.00' },
    revision: 2,
  })
  const measurement = CaliperMeasurement.parse({
    id: 'synthetic-caliper-raw',
    source_id: 'synthetic-caliper',
    measured_at: observation.start_at,
    timezone: observation.timezone,
    folds_mm: { chest: '15', abdomen: '25', thigh: '20' },
    gender: 'male',
    age_years: 35,
    body_fat_percent: '18.0',
    calculation: {
      status: 'calculated',
      method: 'jackson_pollock_3_siri',
      missing_fields: [],
      algorithm_version: 'jackson_pollock_3_siri_v1',
    },
    revision: 1,
    body_fat_record_id: observation.id,
  })
  const source = SourceMetric.parse({
    kind: 'body_fat_percent',
    value: observation.data.value,
    unit: '%',
    status: 'available',
    source_id: measurement.source_id,
    source_name: 'Синтетический калипер',
    records: [observation],
  })
  caliperState.value = CaliperHistory.parse({
    records: [measurement],
    truncated: false,
    limit: 100,
  })
  state.value!.metrics_by_source = { body_fat_percent: [source] }
  return { observation: source.records![0]!, measurement: caliperState.value.records[0]!, source }
}

describe('caliper representation in the mobile overview', () => {
  it('suppresses the exact uncorrected source only on phones, including equal decimal formats', async () => {
    const { measurement } = representedCaliper()
    mobile.value = true
    const wrapper = show()
    const body = wrapper.getComponent({ name: 'DashboardBody' })
    expect(body.props('caliperSourceId')).toBe(measurement.source_id)
    mobile.value = false
    await wrapper.vm.$nextTick()
    expect(body.props('caliperSourceId')).toBeNull()
  })

  it.each([
    'missing measurement record ID',
    'missing source observation',
    'different source ID',
    'excluded observation',
    'corrected observation with the same value',
    'conflict',
    'unavailable result',
    'offline source',
    'pending history',
    'initial load error',
    'refresh error',
    'different result',
  ])('retains the source row for %s', (scenario) => {
    const { observation, measurement, source } = representedCaliper()
    mobile.value = true
    switch (scenario) {
      case 'missing measurement record ID':
        measurement.body_fat_record_id = null
        break
      case 'missing source observation':
        source.records = []
        break
      case 'different source ID':
        source.source_id = 'different-caliper'
        break
      case 'excluded observation':
        observation.excluded = true
        break
      case 'corrected observation with the same value':
        observation.corrected = true
        observation.start_at = '2026-10-05T09:00:00Z'
        break
      case 'conflict':
        source.status = 'conflict'
        source.value = null
        break
      case 'unavailable result':
        source.status = 'unavailable'
        break
      case 'offline source':
        source.source_unavailable = true
        break
      case 'pending history':
        caliperPending.value = true
        break
      case 'initial load error':
        caliperLoadingError.value = true
        break
      case 'refresh error':
        caliperRefreshError.value = true
        break
      case 'different result':
        source.value = '18.5'
        break
    }
    expect(show().getComponent({ name: 'DashboardBody' }).props('caliperSourceId')).toBeNull()
  })
})

describe('sleep on the personal dashboard', () => {
  it('shows unknown duration and the received interval instead of zero sleep on the compact card', () => {
    const analysis = SleepAnalysis.parse(structuredClone(sleepAnalysisFixture))
    Object.assign(analysis.summary, {
      asleep_minutes: 0,
      awake_minutes: 0,
      unknown_minutes: 480,
      classification_coverage: 0,
      forecast_asleep_minutes: null,
    })
    const episode = analysis.episodes[0]!
    Object.assign(episode, {
      start_at: '2026-10-05T00:00:00Z',
      end_at: '2026-10-05T08:00:00Z',
      asleep_minutes: 0,
      awake_minutes: 0,
      unknown_minutes: 480,
      classification_coverage: 0,
    })
    state.value!.metrics.sleep.resolution!.sleep_analysis = analysis
    const wrapper = show()
    const card = wrapper.get('button[data-testid="metric-sleep"]')
    expect(card.text()).toContain('Длительность сна пока не определена')
    expect(card.text()).toContain('03:00 — 11:00')
    expect(card.text()).not.toContain('Распознано сна: 0 мин')
    expect(card.text()).not.toContain('Нет данных')
  })

  it('shows received intervals instead of no data and opens the episode details', async () => {
    const wrapper = show()
    const card = wrapper.get('button[data-testid="metric-sleep"]')
    expect(card.text()).toContain('00:35 — 08:33')
    expect(card.text()).toContain('13:00 — 13:40')
    expect(card.text()).toContain('Длительность сна пока не определена')
    expect(card.text()).not.toContain('Нет данных')
    expect(card.text()).not.toContain('7 ч 58 мин')
    await card.trigger('click')
    const details = wrapper.get('article[data-testid="metric-sleep"]')
    expect(details.text()).toContain('00:35 — 08:33')
    expect(details.text()).toContain('30 мин')
    expect(details.text()).toContain('Неизвестная стадия')
  })

  it.each([
    ['same_source_overlap', 'Пересекаются записи одного кольца'],
    ['sleep_report_correction_conflict', 'Ваша поправка конфликтует с обновлённым отчётом сна'],
    ['equal_priority_overlap', 'Показания не согласованы'],
  ])('names %s on the dashboard itself', (reason, message) => {
    const sleep = state.value!.metrics.sleep
    sleep.status = 'conflict'
    sleep.resolution!.status = 'conflict'
    sleep.resolution!.sessions = []
    sleep.resolution!.alternatives = [{ id: 'old', contribution: null, reason }]
    const wrapper = show()
    expect(wrapper.get('button[data-testid="metric-sleep"]').text()).toContain(message)
  })

  it('keeps the complete sleep duration prominent', () => {
    state.value!.metrics.sleep.value = '420'
    state.value!.metrics.sleep.complete = true
    const wrapper = show()
    const card = wrapper.get('button[data-testid="metric-sleep"]')
    expect(card.text()).toContain('7 ч 0 мин')
    expect(card.text()).not.toContain('Длительность сна пока не определена')
  })
})
