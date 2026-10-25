/**
 * A failed background refresh (a stream resync on a flaky phone network) keeps what a screen
 * loaded and offers to retry; the error state stays for data that never loaded.
 */
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import { setDeveloperMode } from '@/core/developer'
import { NetworkError } from '@/core/http'
import AccountsView from './admin/AccountsView.vue'
import AdminView from './admin/AdminView.vue'
import ChatSettingsView from './admin/ChatSettingsView.vue'
import ResearchSourcesView from './admin/ResearchSourcesView.vue'
import RunView from './admin/RunView.vue'
import UsersView from './admin/UsersView.vue'
import WorkersView from './admin/WorkersView.vue'
import ChallengeView from './circle/ChallengeView.vue'
import ChallengesView from './circle/ChallengesView.vue'
import ChatView from './circle/ChatView.vue'
import CircleView from './circle/CircleView.vue'
import FriendsView from './circle/FriendsView.vue'
import ListView from './circle/ListView.vue'
import MembersView from './circle/MembersView.vue'
import SharedView from './circle/SharedView.vue'
import SpaceView from './circle/SpaceView.vue'
import DevicesView from './devices/DevicesView.vue'
import FoodView from './food/FoodView.vue'
import NotificationSettingsView from './notifications/NotificationSettingsView.vue'
import NotificationsView from './notifications/NotificationsView.vue'
import PersonalView from './personal/PersonalView.vue'
import CaliperCard from './personal/CaliperCard.vue'
import SourcesView from './personal/SourcesView.vue'
import CapabilitiesView from './settings/CapabilitiesView.vue'
import SettingsView from './settings/SettingsView.vue'
import SharingView from './settings/SharingView.vue'
import TaskDiscussion from './tasks/TaskDiscussion.vue'
import TaskSteps from './tasks/TaskSteps.vue'
import TasksView from './tasks/TasksView.vue'
import TimersView from './tasks/TimersView.vue'
import PersonalCard from './today/PersonalCard.vue'
import TasksCard from './today/TasksCard.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  failed: vi.fn(),
  responses: [] as Array<[string, unknown]>,
  offline: false,
  route: { params: {}, query: {} } as {
    name?: string
    params: Record<string, string>
    query: object
  },
}))
vi.mock('@/core/client', () => ({
  api: { get: mocks.get, send: vi.fn(), post: vi.fn(() => Promise.resolve({})) },
}))
vi.mock('vue-sonner', () => ({
  toast: Object.assign(vi.fn(), { error: mocks.failed, success: vi.fn() }),
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'owner', timezone: 'UTC', system_admin: true, scopes: [] },
    can: () => true,
    account: null,
  }),
}))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => mocks.route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), resolve: () => ({ href: '/' }) }),
}))

/** Answers each read by the longest matching path prefix; offline, every read fails. */
function answer(path: string) {
  if (mocks.offline) return Promise.reject(new NetworkError(false))
  const match = mocks.responses
    .filter(([prefix]) => path.startsWith(prefix))
    .sort(([a], [b]) => b.length - a.length)[0]
  return match ? Promise.resolve(structuredClone(match[1])) : Promise.reject(new Error(path))
}

interface Screen {
  name: string
  component: Component
  props?: Record<string, unknown>
  route?: { name?: string; params?: Record<string, string>; query?: object }
  stubs?: Record<string, Component | boolean>
  responses: Record<string, unknown>
  /** Text the loaded screen shows. */
  shows: string
}

const metric = (kind: string, value: string | null = null) => ({
  kind,
  status: value ? 'available' : 'no_data',
  value,
  unit: kind === 'weight' ? 'kg' : '',
  target: null,
  records: [],
})
const overview = {
  date: '2026-10-03',
  timezone: 'UTC',
  profile: {
    user_id: 'owner',
    timezone: 'UTC',
    height_cm: '170',
    display_units: 'metric',
    revision: 1,
  },
  metrics: {
    weight: metric('weight', '72.4'),
    sleep: metric('sleep'),
    steps: metric('steps', '8312'),
    active_minutes: metric('active_minutes'),
  },
  preferences: {},
  sources: [
    { id: 'scale', name: 'Весы в ванной', kinds: ['weight'], status: 'connected', revision: 1 },
  ],
}
const resolutionSettings = {
  from: '2026-10-03',
  to: '2026-10-03',
  timezone: 'UTC',
  computed_at: '2026-10-03T12:00:00Z',
  daily: [],
  series: [],
  next_cursor: null,
  fingerprint: 'synthetic-resolution',
  policies: [],
}
const space = { id: 'family', name: 'Семья', revision: 1, role: 'owner' }
const lists = {
  lists: [
    {
      id: 'groceries',
      name: 'Покупки на неделю',
      space_id: 'family',
      member_access: 'edit',
      revision: 1,
      effective: 'manage',
    },
  ],
}

const members = {
  members: [{ user_id: 'owner', role: 'owner', name: 'Мама' }],
}
const challenge = {
  id: 'steps',
  name: 'Неделя без сахара',
  start_date: '2026-10-01',
  end_date: '2026-10-07',
  starts_at: '2026-10-01T00:00:00Z',
  tolerance: '5',
}
const users = {
  users: [
    {
      id: 'owner',
      login: 'mama',
      name: 'Мама',
      status: 'active',
      system_admin: true,
      revision: 1,
      scopes: [],
    },
  ],
  available_scopes: [],
}
const task = (id: string, title: string) => ({
  id,
  list_id: 'groceries',
  title,
  status: 'open',
  revision: 1,
})

const oatmeal = {
  id: 'oatmeal',
  revision: 1,
  quantity: '200',
  unit: 'g',
  local_date: '2026-10-03',
  name: 'Овсянка',
  nutrients: { energy_kcal: '420' },
}

const nutritionDay = {
  day_revision: 1,
  completed: false,
  date: '2026-10-03',
  entries: [oatmeal],
  items: [oatmeal],
  totals: { energy_kcal: { known: '420', complete: true } },
  goal: null,
  remaining_kcal: null,
  remaining_complete: false,
  drafts: [],
}
const nutritionRange = {
  days: [
    {
      date: '2026-10-03',
      logged: true,
      energy_kcal: '420',
      energy_complete: true,
      protein_g: '0',
      protein_complete: false,
      fat_g: '0',
      carbohydrate_g: '0',
      completed: false,
      target: null,
      macro_targets: null,
      kcal_by_kind: { none: '420' },
    },
  ],
}

/** Keep a panel's visibility and close behavior without Sheet's browser-only teleport/focus. */
const personalPanel = {
  props: ['open', 'title'],
  emits: ['update:open'],
  template: `<section v-if="open" role="dialog" :aria-label="title">
    <button data-testid="refresh-panel-close" @click="$emit('update:open', false)">Закрыть</button>
    <slot />
  </section>`,
}

const run = {
  id: 'run',
  goal: 'Обновить зависимости',
  status: 'running',
  deadline: '2026-10-03T12:00:00Z',
  revision: 1,
}

const SCREENS: Screen[] = [
  {
    name: 'a task discussion',
    component: TaskDiscussion,
    props: { channelId: 'task-chat', canWrite: true },
    responses: {
      '/v1/channels/task-chat/messages': {
        messages: [
          {
            id: 'message',
            actor_user_id: 'owner',
            author: 'user',
            created_at: '2026-10-03T10:00:00Z',
            text: 'Беру на себя',
          },
        ],
        next_cursor: null,
      },
    },
    shows: 'Беру на себя',
  },
  {
    name: 'the personal day',
    component: PersonalView,
    route: { query: { day: '2026-10-03' } },
    stubs: { Panel: personalPanel },
    responses: {
      '/v1/personal/overview': overview,
      '/v1/personal/resolved': resolutionSettings,
      '/v1/personal/history': { from: '2026-09-03', to: '2026-10-03', records: [], daily: [] },
      '/v1/personal/caliper': { records: [], truncated: false, limit: 100 },
      '/v1/nutrition/day': nutritionDay,
      '/v1/nutrition/range': nutritionRange,
    },
    shows: '72,4',
  },
  {
    name: 'personal sources',
    component: SourcesView,
    responses: {
      '/v1/personal/sources': {
        sources: [
          {
            id: 'scale',
            name: 'Весы в ванной',
            kinds: ['weight'],
            status: 'connected',
            revision: 1,
            metric_keys: ['weight'],
            device: { kind: 'custom' },
          },
        ],
      },
      '/v1/personal/resolved': resolutionSettings,
      '/v1/personal/rings': { rings: [] },
    },
    shows: 'Весы в ванной',
  },
  {
    name: 'sharing settings',
    component: SharingView,
    responses: {
      '/v1/me/sharing': {
        sections: [{ id: 'personal-section', name: 'personal', owner_id: 'owner', revision: 1 }],
        grants: [
          {
            resource_id: 'personal-section',
            user_id: 'partner',
            permission: 'summary',
            name: 'Аня',
          },
        ],
      },
      '/v1/spaces': { spaces: [space] },
      '/v1/tasks/lists': lists,
      '/v1/relationships': { relationships: [] },
    },
    shows: 'Покупки на неделю',
  },
  {
    name: 'installation capabilities',
    component: CapabilitiesView,
    responses: {
      '/v1/conversation/modules': {
        modules: [{ id: 'nutrition', name: 'Дневник питания', status: 'ready' }],
        plugins: [],
        checked_at: '2026-10-03T10:00:00Z',
      },
    },
    shows: 'Дневник питания',
  },
  {
    name: 'the food diary',
    component: FoodView,
    responses: {
      '/v1/nutrition/day': {
        day_revision: 1,
        completed: false,
        date: '2026-10-03',
        entries: [oatmeal],
        items: [oatmeal],
        totals: { energy_kcal: { known: '420', complete: true } },
        goal: null,
        remaining_kcal: null,
        remaining_complete: false,
        drafts: [],
      },
    },
    shows: 'Овсянка',
  },
  {
    name: 'tasks',
    component: TasksView,
    route: { name: 'tasks' },
    responses: {
      '/v1/tasks/items': {
        items: [task('milk', 'Молоко')],
        total: 1,
        next_cursor: null,
        today: '2026-10-03',
        timezone: 'UTC',
      },
      '/v1/tasks/lists': lists,
    },
    shows: 'Молоко',
  },
  {
    name: 'subtasks of a task',
    component: TaskSteps,
    props: { task: task('repair', 'Ремонт'), canWrite: true },
    responses: {
      '/v1/tasks/items': {
        items: [task('paint', 'Купить краску')],
        total: 1,
        next_cursor: null,
        today: '2026-10-03',
        timezone: 'UTC',
      },
    },
    shows: 'Купить краску',
  },
  {
    name: 'what others opened to me',
    component: SharedView,
    responses: {
      '/v1/me/sharing': {
        sections: [],
        grants: [],
        shared: [
          {
            id: 'anya-personal',
            name: 'personal',
            owner_id: 'partner',
            permission: 'summary',
            owner_name: 'Аня',
          },
        ],
      },
    },
    shows: 'Аня',
  },
  {
    name: 'circle spaces',
    component: CircleView,
    responses: { '/v1/spaces': { spaces: [space] }, '/v1/shared': { shared: [] } },
    shows: 'Семья',
  },
  {
    name: 'friends',
    component: FriendsView,
    responses: {
      '/v1/relationships': {
        relationships: [
          {
            user_id: 'partner',
            sender_id: 'owner',
            recipient_id: 'partner',
            state: 'accepted',
            name: 'Аня',
          },
        ],
      },
    },
    shows: 'Аня',
  },
  {
    name: 'a space',
    component: SpaceView,
    route: { params: { space: 'family' } },
    responses: {
      '/v1/spaces': { spaces: [space] },
      '/v1/spaces/family/members': members,
      '/v1/channels': { channels: [] },
      '/v1/tasks/lists': lists,
    },
    shows: 'Покупки на неделю',
  },
  {
    name: 'space members',
    component: MembersView,
    route: { params: { space: 'family' } },
    responses: { '/v1/spaces': { spaces: [space] }, '/v1/spaces/family/members': members },
    shows: 'Мама',
  },
  {
    name: 'a shared list',
    component: ListView,
    route: { params: { space: 'family', list: 'groceries' } },
    responses: {
      '/v1/spaces': { spaces: [space] },
      '/v1/spaces/family/members': members,
      '/v1/tasks/lists': lists,
      '/v1/tasks/items': { items: [task('milk', 'Молоко')], next_cursor: null },
    },
    shows: 'Молоко',
  },
  {
    name: 'a space chat',
    component: ChatView,
    route: { params: { channel: 'family-chat' } },
    responses: {
      '/v1/channels': {
        channels: [{ id: 'family-chat', kind: 'space', name: 'Семья', space_id: 'family' }],
      },
      '/v1/spaces': { spaces: [space] },
      '/v1/channels/family-chat/messages': {
        messages: [
          {
            id: 'message',
            actor_user_id: 'owner',
            author: 'user',
            created_at: '2026-10-03T10:00:00Z',
            text: 'Ужин в семь',
          },
        ],
        next_cursor: null,
      },
    },
    shows: 'Ужин в семь',
  },
  {
    name: 'space challenges',
    component: ChallengesView,
    route: { params: { space: 'family' } },
    responses: { '/v1/spaces': { spaces: [space] }, '/v1/challenges': { challenges: [challenge] } },
    shows: 'Неделя без сахара',
  },
  {
    name: 'a challenge',
    component: ChallengeView,
    route: { params: { space: 'family', challenge: 'steps' } },
    responses: {
      '/v1/spaces': { spaces: [space] },
      '/v1/challenges/steps': {
        challenge,
        ranking: [{ user_id: 'partner', name: 'Аня', days: 3, streak: 2, place: 1 }],
      },
    },
    shows: 'Аня',
  },
  {
    name: 'notifications',
    component: NotificationsView,
    responses: {
      '/v1/notifications': {
        notifications: [
          {
            id: 'note',
            source: 'timers',
            topic: 'timer',
            severity: 'info',
            title: 'Таймер «Чайник» сработал',
            body: '',
            link: null,
            state: 'active',
            resolution: null,
            count: 1,
            updated_at: '2026-10-03T10:00:00Z',
            read_at: null,
            snoozed_until: null,
            unread: false,
          },
        ],
        unread: 0,
        before: null,
      },
    },
    shows: 'Таймер «Чайник» сработал',
  },
  {
    name: 'notification settings',
    component: NotificationSettingsView,
    responses: {
      '/v1/notifications/settings': {
        quiet_start: null,
        quiet_end: null,
        muted_topics: [],
        revision: 1,
        topics: [{ topic: 'family.dinner', severity: 'info' }],
      },
      '/v1/push/config': { webpush: null, kinds: [] },
    },
    shows: 'family.dinner',
  },
  {
    name: 'settings with daily limits',
    component: SettingsView,
    responses: { '/v1/me/limits': { limits: [{ kind: 'assistant', maximum: 50, used: 3 }] } },
    shows: 'Обращения к ассистенту',
  },
  {
    name: 'devices',
    component: DevicesView,
    responses: {
      '/v1/devices': {
        devices: [
          { id: 'phone', kind: 'agent', name: 'Codex на ноутбуке', created_at: '2026-10-01' },
        ],
      },
    },
    shows: 'Codex на ноутбуке',
  },
  {
    name: 'users',
    component: UsersView,
    responses: {
      '/v1/admin/users': users,
      '/v1/admin/access': { grants: [] },
      '/v1/accounts/snapshot': { accounts: [] },
    },
    shows: 'Мама',
  },
  {
    name: 'the administration audit',
    component: AdminView,
    responses: {
      '/v1/admin/audit': {
        events: [
          { id: 1, action: 'user.create', actor_user_id: 'owner', created_at: '2026-10-03' },
        ],
      },
      '/v1/admin/users': users,
    },
    shows: 'Мама',
  },
  {
    name: 'chat model settings',
    component: ChatSettingsView,
    responses: {
      '/v1/admin/chat-settings': {
        revision: 1,
        providers: { codex: { model: 'gpt-5.6-terra', reasoning_effort: 'medium' } },
        defaults: { codex: { model: 'gpt-5.6-terra', reasoning_effort: 'medium' } },
        catalog: {
          codex: [{ id: 'gpt-5.6-terra', name: 'GPT-5.6 Terra', reasoning_efforts: ['medium'] }],
        },
      },
    },
    shows: 'GPT-5.6 Terra',
  },
  {
    name: 'subscription accounts',
    component: AccountsView,
    responses: {
      '/v1/accounts/snapshot': {
        accounts: [
          { id: 'codex', provider: 'codex', label: 'Codex семьи', enabled: true, revision: 1 },
        ],
      },
      '/v1/admin/access': { grants: [] },
      '/v1/admin/users': users,
    },
    shows: 'Codex семьи',
  },
  {
    name: 'workers',
    component: WorkersView,
    responses: { '/v1/workers/snapshot': { runs: [run] } },
    shows: 'Обновить зависимости',
  },
  {
    name: 'a worker run',
    component: RunView,
    route: { params: { run: 'run' } },
    responses: { '/v1/workers/runs/run': run },
    shows: 'До крайнего времени',
  },
  {
    name: 'research sources',
    component: ResearchSourcesView,
    responses: {
      '/v1/research/sources': {
        sources: [
          {
            id: 'cbr',
            slug: 'cbr',
            name: 'Банк России',
            topics: ['currency'],
            domains: ['cbr.ru'],
            entry_url: 'https://www.cbr.ru/currency_base/daily/',
            role: 'official_statistics',
            priority: 90,
            status: 'active',
            origin: 'seed',
            best_for: '',
            limitations: '',
            revision: 1,
          },
        ],
        topics: [{ slug: 'currency', label: 'Курсы валют', status: 'active' }],
        settings: {
          unlisted_stats: false,
          model_discovery: false,
          revision: 1,
        },
      },
    },
    shows: 'Банк России',
  },
  {
    name: 'running timers and reminders',
    component: TimersView,
    responses: {
      '/v1/timers/running': {
        timers: [
          {
            id: 'timer',
            label: 'Чайник',
            duration_seconds: 300,
            due_at: '2026-10-03T12:05:00Z',
            revision: 1,
            remaining_seconds: 300,
          },
        ],
      },
      '/v1/timers/reminders': {
        reminders: [
          { id: 'reminder', text: 'Позвонить маме', due_at: '2026-10-04T09:00:00Z', revision: 1 },
        ],
      },
    },
    shows: 'Позвонить маме',
  },
]

let client: QueryClient
let wrapper: VueWrapper | undefined
beforeEach(() => {
  mocks.get.mockReset().mockImplementation(answer)
  mocks.failed.mockReset()
  mocks.offline = false
  // The installation inventory is for developer mode.
  setDeveloperMode(true)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  client.clear()
})

function show(screen: Screen) {
  mocks.responses = Object.entries(screen.responses)
  mocks.route = reactive({ params: {}, query: {}, ...screen.route })
  // Queries with their own retry policy retry at once here.
  client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  wrapper = mount(screen.component, {
    props: screen.props,
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: {
        PageHeader: true,
        RouterLink: { template: '<a><slot /></a>' },
        UnresolvedWrites: true,
        DayNav: true,
        DdxCard: true,
        ...screen.stubs,
      },
    },
  })
  return wrapper
}
const notices = (view: VueWrapper) => view.findAll('[data-state="refresh-failed"]')
/** Why reads failed, for a test that did not see the screen it expected. */
const failures = () =>
  client
    .getQueryCache()
    .getAll()
    .filter((query) => query.state.error)
    .map((query) => `${JSON.stringify(query.queryKey)}: ${query.state.error}`)
    .join('\n')

describe.each(SCREENS)('$name', (screen) => {
  it('keeps the loaded screen with a retry when a refresh fails', async () => {
    const view = show(screen)
    await vi.waitFor(() => expect(view.text(), failures()).toContain(screen.shows))
    mocks.offline = true
    await client.invalidateQueries()
    await flushPromises()
    expect(view.text()).toContain(screen.shows)
    expect(view.find('[data-state="error"]').exists()).toBe(false)
    expect(notices(view).length).toBeGreaterThan(0)

    mocks.offline = false
    for (const notice of notices(view)) await notice.get('button').trigger('click')
    await flushPromises()
    expect(notices(view)).toHaveLength(0)
    expect(view.text()).toContain(screen.shows)
  })

  it('shows the error state when the screen never loaded', async () => {
    mocks.offline = true
    const view = show(screen)
    await vi.waitFor(() => expect(view.find('[data-state="error"]').exists()).toBe(true))
    expect(notices(view)).toHaveLength(0)
  })
})

it('keeps the sources listed when only today’s decisions never loaded', async () => {
  const screen = SCREENS.find((item) => item.component === SourcesView)!
  mocks.get.mockImplementation((path: string) =>
    path.startsWith('/v1/personal/resolved')
      ? Promise.reject(new NetworkError(false))
      : answer(path),
  )
  const view = show(screen)
  await vi.waitFor(() =>
    expect(view.get('[data-testid="source-devices"]').text()).toContain(screen.shows),
  )
  expect(view.find('[data-state="error"]').exists()).toBe(false)
  expect(view.get('[data-testid="source-row-weight"]').text()).toContain('Весы в ванной')
})

it('keeps raw caliper measurements when only their background refresh fails', async () => {
  const personal = SCREENS.find((screen) => screen.component === PersonalView)!
  const view = show({
    ...personal,
    responses: {
      ...personal.responses,
      '/v1/personal/caliper': {
        records: [
          {
            id: 'synthetic-caliper',
            source_id: 'synthetic-caliper-source',
            measured_at: '2026-10-03T09:00:00Z',
            timezone: 'UTC',
            folds_mm: { abdomen: '20.5', suprailiac: '15' },
            gender: 'male',
            age_years: 35,
            body_fat_percent: null,
            calculation: {
              status: 'incomplete',
              method: 'jackson_pollock_3_siri',
              missing_fields: ['chest', 'thigh'],
              algorithm_version: 'jackson_pollock_3_siri_v1',
            },
            revision: 1,
            body_fat_record_id: null,
          },
        ],
        truncated: false,
        limit: 100,
      },
    },
  })
  const card = () => view.get('[data-testid="caliper-card"]')
  await vi.waitFor(() => expect(view.text(), failures()).toContain(personal.shows))
  await view.get('[data-testid="caliper-history-open"]').trigger('click')
  await vi.waitFor(() => expect(card().text(), failures()).toContain('20,5 мм'))
  expect(view.getComponent(CaliperCard).props('profile')).toEqual(overview.profile)
  mocks.get.mockImplementation((path: string) =>
    path.startsWith('/v1/personal/caliper')
      ? Promise.reject(new NetworkError(false))
      : answer(path),
  )
  await client.invalidateQueries({ queryKey: ['personal', 'caliper'] })
  await flushPromises()
  expect(card().text()).toContain('20,5 мм')
  expect(card().text()).toContain('грудь, бедро')
  expect(view.text()).toContain(personal.shows)
  expect(view.find('[data-state="error"]').exists()).toBe(false)
  expect(card().findAll('[data-state="refresh-failed"]')).toHaveLength(1)
  expect(view.getComponent(CaliperCard).props('profile')).toEqual(overview.profile)

  await view.get('[role="dialog"] [data-testid="refresh-panel-close"]').trigger('click')
  expect(view.find('[data-testid="caliper-card"]').exists()).toBe(false)
  await view.get('[data-testid="caliper-history-open"]').trigger('click')
  await flushPromises()
  expect(card().text()).toContain('20,5 мм')
  expect(card().text()).toContain('грудь, бедро')
  expect(card().find('[data-state="error"]').exists()).toBe(false)
  expect(card().findAll('[data-state="refresh-failed"]')).toHaveLength(1)

  mocks.get.mockImplementation(answer)
  await card().get('[data-state="refresh-failed"] button').trigger('click')
  await flushPromises()
  expect(notices(view)).toHaveLength(0)
  expect(card().text()).toContain('20,5 мм')
})

it('keeps an open body profile and its draft when the overview refresh fails', async () => {
  const personal = SCREENS.find((screen) => screen.component === PersonalView)!
  const view = show(personal)
  await vi.waitFor(() => expect(view.text(), failures()).toContain(personal.shows))
  await view.get('[data-testid="profile-open"]').trigger('click')
  const height = () => view.get<HTMLInputElement>('[data-testid="profile-height"]')
  expect(height().element.value).toBe('170')
  await height().setValue('180.5')
  mocks.get.mockImplementation((path: string) =>
    path.startsWith('/v1/personal/overview')
      ? Promise.reject(new NetworkError(false))
      : answer(path),
  )
  await client.invalidateQueries({ queryKey: ['personal', 'overview'] })
  await flushPromises()
  expect(view.find('[role="dialog"]').exists()).toBe(true)
  expect(height().element.value).toBe('180.5')
  expect(view.text()).toContain(personal.shows)
  expect(view.find('[data-state="error"]').exists()).toBe(false)
  expect(notices(view)).toHaveLength(1)

  mocks.get.mockImplementation(answer)
  await notices(view)[0]!.get('button').trigger('click')
  await flushPromises()
  expect(notices(view)).toHaveLength(0)
  expect(height().element.value).toBe('180.5')
  expect(view.find('[role="dialog"]').exists()).toBe(true)
})

describe.each([
  { name: 'a task discussion', more: 'Более ранние сообщения' },
  { name: 'subtasks of a task', more: 'Показать ещё' },
  { name: 'tasks', more: 'Показать ещё' },
])('$name', ({ name, more }) => {
  it('says that a further page failed and keeps the list', async () => {
    const screen = SCREENS.find((item) => item.name === name)!
    const paged = Object.entries(screen.responses).map(([path, body]) => [
      path,
      body && typeof body === 'object' && 'next_cursor' in body
        ? { ...body, next_cursor: 'older' }
        : body,
    ])
    const view = show({ ...screen, responses: Object.fromEntries(paged) })
    await vi.waitFor(() => expect(view.text(), failures()).toContain(screen.shows))
    mocks.offline = true
    await view
      .findAll('button')
      .find((button) => button.text() === more)!
      .trigger('click')
    await flushPromises()
    expect(mocks.failed).toHaveBeenCalledWith(expect.stringContaining('Нет соединения'))
    expect(view.text()).toContain(screen.shows)
    expect(view.find('[data-state="error"]').exists()).toBe(false)
    expect(notices(view)).toHaveLength(0)
  })
})

describe.each<Screen>([
  {
    name: 'the weight on Today',
    component: PersonalCard,
    props: { day: '2026-10-03' },
    responses: {
      '/v1/personal/overview': overview,
      '/v1/personal/resolved': resolutionSettings,
      '/v1/personal/history': { from: '2026-09-03', to: '2026-10-03', records: [], daily: [] },
    },
    shows: '72,4',
  },
  {
    name: 'tasks on Today',
    component: TasksCard,
    responses: {
      '/v1/tasks/items': {
        items: [task('milk', 'Молоко')],
        total: 1,
        next_cursor: null,
        today: '2026-10-03',
        timezone: 'UTC',
      },
    },
    shows: 'Молоко',
  },
])('$name', (card) => {
  // A glance: the screen it opens says that a refresh failed.
  it('keeps what it loaded when a refresh fails', async () => {
    const view = show(card)
    await vi.waitFor(() => expect(view.text(), failures()).toContain(card.shows))
    mocks.offline = true
    await client.invalidateQueries()
    await flushPromises()
    expect(view.text()).toContain(card.shows)
    expect(view.text()).not.toContain('Не удалось загрузить')
  })
})
