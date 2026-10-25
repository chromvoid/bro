import { mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { reactive, ref, type PropType } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { History, Profile } from './api'
import HistoryView from './HistoryView.vue'
import { sleepDecision } from './resolverFixtures'

const profile = Profile.parse({
  user_id: 'owner',
  timezone: 'UTC',
  height_cm: '170',
  gender: 'female',
  gender_origin: 'manual',
  birth_date: '1990-01-01',
  revision: 3,
})
const overview = ref<{ profile: Profile } | undefined>({ profile })
const overviewError = ref(false)
const retryOverview = vi.fn()
const historyRefreshFailed = ref(false)
const retryHistory = vi.fn()
const canWrite = ref(true)
const sessionZone = ref('UTC')
const history = ref(History.parse({ from: '2026-01-01', to: '2026-10-03', records: [], daily: [] }))
const route = reactive({ query: { kind: 'body' } as Record<string, string> })
const replace = vi.fn()
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return { timezone: sessionZone.value }
    },
    can: () => canWrite.value,
  }),
}))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => route,
  useRouter: () => ({ replace }),
}))
vi.mock('./usePersonal', () => ({
  useOverview: () => ({
    data: overview,
    isLoadingError: overviewError,
    isRefetchError: ref(false),
    isFetching: ref(false),
    error: ref(new Error('Profile unavailable')),
    refetch: retryOverview,
  }),
  useHistory: () => ({
    data: history,
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: historyRefreshFailed,
    isFetching: ref(false),
    refetch: retryHistory,
  }),
  usePersonal: () => ({
    unresolved: ref([]),
    describe: vi.fn(),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
const HistoryStub = {
  name: 'BodyHistory',
  props: { profile: Object as PropType<Profile>, canWrite: Boolean },
  emits: ['profile'],
  template: '<button @click="$emit(\'profile\')">profile</button>',
}
const ProfileStub = {
  name: 'ProfilePanel',
  props: { profile: Object as PropType<Profile>, open: Boolean },
  template: '<div />',
}
const CaliperStub = {
  name: 'CaliperPanel',
  props: { profile: Object as PropType<Profile>, open: Boolean, day: String, today: String },
  template: '<div />',
}
function show() {
  return mount(HistoryView, {
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: {
        ActivityReportHistory: true,
        RawReportHistory: true,
        RecordVersionHistory: true,
        BodyHistory: HistoryStub,
        ProfilePanel: ProfileStub,
        CaliperPanel: CaliperStub,
        RouterLink: RouterLinkStub,
        PageHeader: {
          name: 'PageHeader',
          props: ['back'],
          template: '<header><slot name="actions" /><nav><slot name="menu" /></nav></header>',
        },
        DropdownMenuItem: { template: '<div><slot /></div>' },
        UnresolvedWrites: true,
        WeightPanel: true,
        RecordPanel: true,
      },
    },
  })
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T06:00:00Z'))
  overview.value = { profile }
  overviewError.value = false
  retryOverview.mockClear()
  historyRefreshFailed.value = false
  retryHistory.mockClear()
  canWrite.value = true
  sessionZone.value = 'UTC'
  route.query = { kind: 'body' }
  replace.mockClear()
  history.value = History.parse({ from: '2026-01-01', to: '2026-10-05', records: [], daily: [] })
})
afterEach(() => vi.useRealTimers())

describe('sleep history', () => {
  it.each(['steps', 'sleep'])(
    'keeps the immutable %s archive available beside a corrected and excluded row',
    (kind) => {
      route.query = { kind }
      history.value = History.parse({
        from: '2026-10-01',
        to: '2026-10-05',
        daily: [],
        records: [
          {
            id: 'synthetic-record',
            source_id: 'synthetic-source',
            source_name: 'Synthetic ring',
            kind,
            start_at: '2026-10-04T12:00:00Z',
            end_at: '2026-10-04T13:00:00Z',
            timezone: 'UTC',
            method: 'device_report',
            revision: 2,
            data: { value: null },
            corrected: true,
            excluded: true,
            original: { data: { value: '12' } },
          },
        ],
      })
      const wrapper = show()
      expect(wrapper.getComponent({ name: 'RecordVersionHistory' }).props()).toMatchObject({
        family: 'record',
        recordId: 'synthetic-record',
        sourceId: 'synthetic-source',
        metric: kind,
      })
      expect(wrapper.text()).toContain('Не учитывается')
      expect(wrapper.find('[data-testid="history"] button').exists()).toBe(true)
      wrapper.unmount()
    },
  )
  it('keeps the original day and section while filters and periods browse another range', async () => {
    route.query = {
      kind: 'sleep',
      period: '7',
      end: '2026-09-20',
      returnDay: '2026-10-03',
      returnSection: 'daily',
      returnKey: 'synthetic-return',
    }
    const wrapper = show()
    expect(wrapper.getComponent({ name: 'PageHeader' }).props('back')).toEqual({
      name: 'personal',
      query: {
        day: '2026-10-03',
        returnDay: '2026-10-03',
        returnSection: 'daily',
        returnKey: 'synthetic-return',
      },
    })
    await wrapper.get('#history-kind').setValue('weight')
    expect(replace).toHaveBeenLastCalledWith({
      query: { ...route.query, kind: 'weight', period: undefined },
    })
    const periodQueries = wrapper
      .findAllComponents(RouterLinkStub)
      .map((link) => link.props('to'))
      .flatMap((to) => (typeof to === 'object' && 'query' in to && to.query ? [to.query] : []))
    expect(periodQueries.find((query) => query.period === 30)).toMatchObject({
      returnDay: '2026-10-03',
      returnSection: 'daily',
      returnKey: 'synthetic-return',
      end: '2026-09-20',
    })
  })

  it('retains incomplete intervals and labels replaced reports in all-source history', async () => {
    route.query = { kind: 'sleep' }
    sessionZone.value = 'Europe/Moscow'
    const resolution = sleepDecision()
    const night = resolution.sessions[0]!
    history.value = History.parse({
      from: '2026-10-01',
      to: '2026-10-05',
      records: [night, { ...night, id: 'old-night', end_at: '2026-10-05T04:00:00Z' }].map(
        (session) => ({
          ...session,
          kind: 'sleep',
          method: 'device_report',
          revision: 1,
          timezone: 'Europe/Moscow',
          data: { value: null, stages: [] },
        }),
      ),
      daily: [{ date: '2026-10-05', kind: 'sleep', value: null, status: 'available', resolution }],
    })
    const wrapper = show()
    expect(wrapper.get('[data-testid="daily-resolution"]').text()).toContain('00:35 — 08:33')
    expect(wrapper.get('[data-testid="history"]').findAll('li')).toHaveLength(1)
    expect(wrapper.get('[data-testid="history"]').text()).toContain(
      'Длительность сна пока не определена',
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Все источники')!
      .trigger('click')
    const rows = wrapper.get('[data-testid="history"]')
    expect(rows.findAll('li')).toHaveLength(2)
    expect(rows.text()).toContain('00:35 — 08:33')
    expect(rows.text()).toContain('00:35 — 07:00')
    expect(rows.text()).toContain('Заменено обновлённым отчётом сна')
    expect(rows.text()).not.toContain('Нет значения')
  })
})

describe('body history current profile context', () => {
  it('opens the caliper form from an empty body history', async () => {
    const wrapper = show()
    expect(history.value.records).toHaveLength(0)
    const add = wrapper.get('header button')
    expect(add.text()).toContain('Добавить замер калипером')
    expect(add.attributes('disabled')).toBeUndefined()
    expect(wrapper.getComponent(CaliperStub).props('open')).toBe(false)
    await add.trigger('click')
    expect(wrapper.getComponent(CaliperStub).props('open')).toBe(true)
    expect(wrapper.getComponent(CaliperStub).props('profile')).toEqual(profile)
    expect(wrapper.get('header nav a').text()).toBe('Журнал замеров')
    const journal = wrapper
      .findAllComponents(RouterLinkStub)
      .find((link) => link.text() === 'Журнал замеров')!
    expect(journal.props('to')).toEqual({ name: 'personal', query: { caliper: '1' } })
  })

  it('defaults a new caliper record to today while browsing a historical period', async () => {
    route.query = { kind: 'body', period: '365', end: '2025-12-31' }
    const wrapper = show()
    await wrapper.get('header button').trigger('click')
    const panel = wrapper.getComponent(CaliperStub)
    expect(panel.props('day')).toBe('2026-10-05')
    expect(panel.props('today')).toBe('2026-10-05')
    expect(panel.props('day')).not.toBe(route.query.end)
  })

  it('passes the current overview profile into history and opens its explicit edit panel', async () => {
    const wrapper = show()
    expect(wrapper.getComponent(HistoryStub).props('profile')).toEqual(profile)
    expect(wrapper.getComponent(ProfileStub).props('open')).toBe(false)
    await wrapper.getComponent(HistoryStub).trigger('click')
    expect(wrapper.getComponent(ProfileStub).props('open')).toBe(true)
    overview.value = { profile: { ...profile, height_cm: '180', revision: 4 } }
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent(HistoryStub).props('profile').height_cm).toBe('180')
    expect(wrapper.getComponent(ProfileStub).props('profile').revision).toBe(4)
  })
  it('keeps read-only users out of the profile editor', () => {
    canWrite.value = false
    const wrapper = show()
    expect(wrapper.getComponent(HistoryStub).props('canWrite')).toBe(false)
    expect(wrapper.findComponent(ProfileStub).exists()).toBe(false)
    expect(wrapper.find('header button').exists()).toBe(false)
    expect(wrapper.findComponent(CaliperStub).exists()).toBe(false)
    expect(wrapper.get('header nav a').text()).toBe('Журнал замеров')
  })
  it('keeps measurements accessible and offers retry when the profile cannot be loaded', async () => {
    overview.value = undefined
    const wrapper = show()
    expect(wrapper.get('[role="status"]').text()).toContain('Загружаем профиль')
    expect(wrapper.findComponent(HistoryStub).exists()).toBe(true)
    expect(wrapper.findComponent(ProfileStub).exists()).toBe(false)
    expect(wrapper.get('header button').attributes('disabled')).toBeDefined()
    expect(wrapper.findComponent(CaliperStub).exists()).toBe(false)
    overviewError.value = true
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[role="alert"]').text()).toContain('Не удалось загрузить профиль')
    expect(wrapper.get('header button').attributes('disabled')).toBeDefined()
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(retryOverview).toHaveBeenCalledOnce()
    overview.value = { profile }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.getComponent(HistoryStub).props('profile')).toEqual(profile)
    expect(wrapper.get('header button').attributes('disabled')).toBeUndefined()
    await wrapper.get('header button').trigger('click')
    expect(wrapper.getComponent(CaliperStub).props('open')).toBe(true)
  })
  it('keeps the history on screen with a retry when a refresh fails', async () => {
    historyRefreshFailed.value = true
    const wrapper = show()
    const notice = wrapper.get('[data-state="refresh-failed"]')
    expect(notice.text()).toContain('Не удалось обновить')
    expect(wrapper.findComponent(HistoryStub).exists()).toBe(true)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
    await notice.get('button').trigger('click')
    expect(retryHistory).toHaveBeenCalledOnce()
    historyRefreshFailed.value = false
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(false)
  })
  it('explains omitted incomplete records and links just the missing account metadata', async () => {
    overview.value = { profile: { ...profile, birth_date: null } }
    const wrapper = show()
    expect(wrapper.get('[data-testid="caliper-history-hint"]').text()).toContain('Неполные замеры')
    const notice = wrapper.get('[data-testid="caliper-history-profile-notice"]')
    expect(notice.attributes('role')).toBe('alert')
    expect(
      notice
        .findAllComponents(RouterLinkStub)
        .map((link: VueWrapper<InstanceType<typeof RouterLinkStub>>) => link.props('to')),
    ).toEqual([{ name: 'personal-profile', query: { field: 'birth' } }])
    overview.value = { profile: { ...profile, gender: null } }
    await wrapper.vm.$nextTick()
    expect(
      notice
        .findAllComponents(RouterLinkStub)
        .map((link: VueWrapper<InstanceType<typeof RouterLinkStub>>) => link.props('to')),
    ).toEqual([{ name: 'personal-profile', query: { field: 'gender' } }])
    route.query.kind = 'weight'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="caliper-history-hint"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="caliper-history-profile-notice"]').exists()).toBe(false)
  })
  it('keeps an open measurement form while a timezone day change loads a different overview', async () => {
    const wrapper = show()
    await wrapper.get('header button').trigger('click')
    const panel = wrapper.getComponent(CaliperStub).vm
    sessionZone.value = 'America/Adak'
    overview.value = undefined
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent(CaliperStub).vm).toBe(panel)
    expect(wrapper.getComponent(CaliperStub).props('open')).toBe(true)
    expect(wrapper.getComponent(CaliperStub).props('profile')).toEqual(profile)
    expect(wrapper.getComponent(CaliperStub).props('today')).toBe('2026-10-04')
    expect(wrapper.get('[role="status"]').text()).toContain('Загружаем профиль')
    expect(wrapper.get('header button').attributes('disabled')).toBeDefined()
    overview.value = { profile: { ...profile, timezone: 'America/Adak', revision: 4 } }
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent(CaliperStub).vm).toBe(panel)
    expect(wrapper.getComponent(CaliperStub).props('profile').revision).toBe(4)
  })
})

describe('typed all-metric history', () => {
  it('renders actual sensor/report units while ordinary observations remain editable separately', () => {
    route.query = { kind: 'all' }
    const resolution = {
      status: 'available',
      selected: [],
      alternatives: [],
      fingerprint: 'synthetic',
      resolver_version: 'v1',
    }
    history.value = History.parse({
      from: '2026-10-05',
      to: '2026-10-05',
      records: [],
      daily: [
        {
          date: '2026-10-05',
          kind: 'heart_rate',
          value: '72',
          unit: 'bpm',
          status: 'available',
          target: null,
          resolution,
        },
        {
          date: '2026-10-05',
          kind: 'hrv',
          value: '18.5',
          unit: 'ms',
          status: 'available',
          target: null,
          resolution,
        },
        {
          date: '2026-10-05',
          kind: 'stress',
          value: '20',
          unit: 'index',
          status: 'available',
          target: null,
          resolution,
        },
        {
          date: '2026-10-05',
          kind: 'workouts',
          value: '1',
          unit: 'тренировок',
          status: 'available',
          target: null,
          resolution,
        },
      ],
    })
    const wrapper = show()
    expect(wrapper.findAll('[data-testid="daily-resolution"]')).toHaveLength(4)
    expect(wrapper.text()).toContain('72 уд/мин')
    expect(wrapper.text()).toContain('18,5 мс')
    expect(wrapper.text()).toContain('20 индекс')
    expect(wrapper.text()).toContain('1 тренировок')
    expect(wrapper.text()).not.toContain('undefined')
    wrapper.unmount()
  })
  it.each(['active_energy', 'distance'])(
    'keeps raw %s reports beside normalized observation history',
    (kind) => {
      route.query = { kind }
      const wrapper = show()
      expect(wrapper.getComponent({ name: 'ActivityReportHistory' }).props('kind')).toBe(kind)
      wrapper.unmount()
    },
  )
})
