import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import PageHeader from '@/components/PageHeader.vue'
import SessionDetail from './SessionDetail.vue'
import SessionView from './SessionView.vue'
import { ActivitySession } from './api'

const mocks = vi.hoisted(() => ({
  session: null as unknown,
  catalog: null as unknown,
  operate: vi.fn(),
  failed: vi.fn(),
  unresolved: null as unknown,
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'Europe/Moscow' }, can: () => true }),
}))
vi.mock('./useActivity', () => ({
  useActivitySession: () => mocks.session,
  useExercises: () => mocks.catalog,
  useActivity: () => ({
    onResolved: () => () => {},
    operate: mocks.operate,
    failed: mocks.failed,
    unresolved: mocks.unresolved,
  }),
}))
const session = ActivitySession.parse({
  id: 'synthetic',
  revision: 4,
  kind: 'strength',
  date: '2026-10-05',
  timezone: 'Europe/Moscow',
  started_at: null,
  duration_minutes: null,
  distance_km: null,
  exercises: [],
})
function query<T>(data: T) {
  return {
    data: ref(data),
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    isFetching: ref(false),
    error: ref(null),
    refetch: vi.fn(),
  }
}
const origin = {
  tab: 'journal',
  day: '2026-10-04',
  end: '2026-10-08',
  period: '7',
  kind: 'strength',
}
async function screen(context = origin) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/activity', name: 'activity', component: { template: '<div />' } },
      {
        path: '/activity/:session',
        name: 'activity-session',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ name: 'activity-session', params: { session: 'synthetic' }, query: context })
  await router.isReady()
  const wrapper = mount(SessionView, {
    global: {
      plugins: [i18n, router],
      stubs: {
        PageHeader: true,
        SessionDetail: true,
        SessionEffort: true,
        SessionForm: true,
        UnresolvedWrites: true,
      },
    },
  })
  return { wrapper, router }
}

describe('Activity session navigation', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-10T09:00:00Z'))
    mocks.session = query({ session })
    mocks.catalog = query({ exercises: [] })
    mocks.unresolved = ref([])
    mocks.operate.mockReset().mockResolvedValue(undefined)
    mocks.failed.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  it('returns to the original journal filters after viewing or deleting a session', async () => {
    const { wrapper, router } = await screen()
    expect(wrapper.getComponent(PageHeader).props('back')).toEqual({
      name: 'activity',
      query: origin,
    })
    wrapper.getComponent(SessionDetail).vm.$emit('delete')
    await flushPromises()
    await wrapper
      .get('[role="alert"]')
      .findAll('button')
      .find((button) => button.text() === 'Удалить')!
      .trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'session.delete',
      { session_id: 'synthetic' },
      { synthetic: 4 },
    )
    expect(router.currentRoute.value.name).toBe('activity')
    expect(router.currentRoute.value.query).toEqual(origin)
  })

  it('opens exercise dynamics while keeping the selected day and independent period end', async () => {
    const { wrapper, router } = await screen()
    wrapper.getComponent(SessionDetail).vm.$emit('progress', 'bench')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      ...origin,
      tab: 'progress',
      metric: 'strength',
      exercise: 'bench',
    })
  })
})
