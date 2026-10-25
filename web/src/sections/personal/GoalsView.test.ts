import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import GoalsView from './GoalsView.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  writable: true,
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'owner', timezone: 'UTC' },
    can: (scope: string) => scope === 'personal.read' || mocks.writable,
  }),
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))
vi.mock('./usePersonal', async (original) => ({
  ...(await original<typeof import('./usePersonal')>()),
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed, unresolved: ref([]) }),
}))
const metric = (kind: string) => ({ kind, status: 'no_data', value: null, unit: '', target: null })
const overview = () => ({
  date: '2026-10-09',
  timezone: 'UTC',
  profile: { user_id: 'owner', timezone: 'UTC', display_units: 'imperial', revision: 4 },
  metrics: {
    weight: metric('weight'),
    sleep: metric('sleep'),
    steps: metric('steps'),
    active_minutes: metric('active_minutes'),
  },
  preferences: {
    distance: { kind: 'distance', target: '1609.344', revision: 2 },
    active_energy: { kind: 'active_energy', target: '113.719', revision: 3 },
  },
  sources: [],
})
beforeEach(() => {
  vi.clearAllMocks()
  mocks.writable = true
  mocks.get.mockResolvedValue(overview())
  mocks.operate.mockResolvedValue({})
})
function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(GoalsView, {
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: {
        PageHeader: true,
        UnresolvedWrites: true,
        RouterLink: true,
      },
    },
  })
  return { wrapper, client }
}
describe('personal activity goals and units', () => {
  it('keeps an unchanged mile draft exact and converts a changed one to meters', async () => {
    const { wrapper, client } = show()
    await flushPromises()
    const row = () => wrapper.get('[data-testid="target-distance"]')
    await row().get('button').trigger('click')
    expect(row().get<HTMLInputElement>('input').element.value).toBe('1')
    await row().get('form').trigger('submit')
    expect(mocks.operate).toHaveBeenLastCalledWith('preference.set', {
      kind: 'distance',
      target: '1609.344',
      revision: 2,
    })
    await flushPromises()
    await row().get('button').trigger('click')
    await row().get('input').setValue('1,25')
    await row().get('form').trigger('submit')
    expect(mocks.operate).toHaveBeenLastCalledWith('preference.set', {
      kind: 'distance',
      target: '2011.68',
      revision: 2,
    })
    wrapper.unmount()
    client.clear()
  })
  it('preserves fractional calorie targets and clears them explicitly', async () => {
    const { wrapper, client } = show()
    await flushPromises()
    const row = () => wrapper.get('[data-testid="target-active_energy"]')
    await row().get('button').trigger('click')
    await row().get('form').trigger('submit')
    expect(mocks.operate).toHaveBeenLastCalledWith('preference.set', {
      kind: 'active_energy',
      target: '113.719',
      revision: 3,
    })
    await flushPromises()
    await row().get('button').trigger('click')
    await row().get('input').setValue('')
    await row().get('form').trigger('submit')
    expect(mocks.operate).toHaveBeenLastCalledWith('preference.set', {
      kind: 'active_energy',
      target: null,
      revision: 3,
    })
    wrapper.unmount()
    client.clear()
  })
  it('changes the owner profile units through its revision without rewriting goals', async () => {
    const { wrapper, client } = show()
    await flushPromises()
    mocks.operate.mockResolvedValue({
      profile: { ...overview().profile, display_units: 'metric', revision: 5 },
    })
    await wrapper.get('[data-testid="display-units-control"] button').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledExactlyOnceWith(
      'profile.update',
      { display_units: 'metric' },
      { owner: 4 },
    )
    expect(wrapper.get('[data-testid="target-distance"]').text()).toContain('1,61 км')
    expect(
      client.getQueriesData<{ preferences: unknown }>({
        queryKey: ['personal', 'overview'],
      })[0]?.[1]?.preferences,
    ).toEqual(overview().preferences)
    wrapper.unmount()
    client.clear()
  })
  it('shows preferences without write controls to a reader', async () => {
    mocks.writable = false
    const { wrapper, client } = show()
    await flushPromises()
    expect(wrapper.get('[data-testid="target-distance"]').text()).toContain('1 мили')
    expect(wrapper.get('[data-testid="target-active_energy"]').text()).toContain('114 ккал')
    expect(wrapper.get('[data-testid="target-distance"]').find('button').exists()).toBe(false)
    expect(wrapper.get('[data-testid="display-units-control"]').find('button').exists()).toBe(false)
    wrapper.unmount()
    client.clear()
  })
})
