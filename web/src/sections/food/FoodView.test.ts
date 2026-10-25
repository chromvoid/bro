import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick, ref } from 'vue'
import { i18n } from '@/i18n'
import FoodView from './FoodView.vue'

const mocks = vi.hoisted(() => ({
  target: null as unknown,
  days: [] as string[],
  account: null as unknown,
  read: null as { value: boolean } | null,
  rich: null as unknown,
}))
vi.mock('@/stores/session', () => ({ useSession: () => mocks.account }))
vi.mock('@/core/layout', async () => {
  const { ref } = await import('vue')
  return { useRoomFor: () => ref(false) }
})
vi.mock('./itemTarget', async (original) => ({
  ...(await original<typeof import('./itemTarget')>()),
  useFoodItemTarget: () => mocks.target,
}))
vi.mock('./useFood', async () => {
  const { computed, ref } = await import('vue')
  const { Day } = await import('./api')
  return {
    useFood: () => ({ unresolved: ref([]) }),
    useFoodDay: (day: { value: string }) => ({
      data: computed(() => {
        mocks.days.push(day.value)
        if (day.value === '2026-10-05') return Day.parse(mocks.rich)
        return Day.parse({
          date: day.value,
          day_revision: 1,
          completed: false,
          entries: [],
          items:
            day.value === '2026-10-04'
              ? [
                  {
                    id: 'entry',
                    name: 'Synthetic food',
                    revision: 2,
                    quantity: '100',
                    unit: 'g',
                    local_date: day.value,
                  },
                ]
              : [],
          totals: {},
          goal: null,
          remaining_kcal: null,
          remaining_complete: false,
          drafts: [],
        })
      }),
      isPending: ref(false),
      isLoadingError: ref(false),
      isRefetchError: ref(false),
      isFetching: ref(false),
    }),
  }
})

async function screen(path = '/food?kind=entry&item=entry&day=2026-10-03') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { name: 'food', path: '/food', component: FoodView },
      { name: 'food-stats', path: '/food/stats', component: FoodView },
      { name: 'assistant', path: '/assistant', component: FoodView },
    ],
  })
  await router.push(path)
  const wrapper = mount(FoodView, {
    global: {
      plugins: [router, i18n],
      stubs: {
        PageHeader: true,
        DayNav: true,
        WeekStrip: true,
        UnresolvedWrites: true,
        RefreshFailed: true,
        AddFoodPanel: true,
        SearchPanel: true,
        PortionPanel: true,
        DishesPanel: true,
        LabelPanel: true,
        GoalPanel: true,
        Panel: true,
        Progress: true,
        EntryPanel: {
          props: ['entry', 'open'],
          template: '<div v-if="open" data-testid="selected-entry">{{ entry.id }}</div>',
        },
        DishPanel: {
          props: ['dish', 'open'],
          template: '<div v-if="open" data-testid="selected-dish">{{ dish.id }}</div>',
        },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  mocks.days = []
  mocks.read = ref(true)
  mocks.account = {
    me: { id: 'owner', timezone: 'UTC' },
    account: null,
    can: () => mocks.read?.value !== false,
  }
  mocks.target = {
    data: ref({ kind: 'entry', id: 'entry', date: '2026-10-04', revision: 2, status: 'active' }),
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    isFetching: ref(false),
  }
})

describe('food result deep link', () => {
  it('closes cached details as soon as read access is revoked', async () => {
    const { wrapper } = await screen()
    expect(wrapper.find('[data-testid="selected-entry"]').exists()).toBe(true)
    mocks.read!.value = false
    await nextTick()
    expect(wrapper.find('[data-testid="selected-entry"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="food-target-unavailable"]').text()).toContain(
      'Запись недоступна',
    )
    wrapper.unmount()
  })
  it('loads the actual date and opens a cached canonical target immediately', async () => {
    const { wrapper } = await screen()
    expect(mocks.days.at(-1)).toBe('2026-10-04')
    expect(wrapper.get('[data-testid="selected-entry"]').text()).toBe('entry')
    wrapper.unmount()
  })
  it('clears an old panel when a new missing identity is selected', async () => {
    const { wrapper, router } = await screen()
    const target = mocks.target as { data: ReturnType<typeof ref> }
    target.data.value = undefined
    await router.push('/food?kind=entry&item=missing')
    await nextTick()
    expect(wrapper.find('[data-testid="selected-entry"]').exists()).toBe(false)
    target.data.value = {
      kind: 'entry',
      id: 'missing',
      date: '2026-10-04',
      revision: 1,
      status: 'active',
    }
    await nextTick()
    expect(wrapper.get('[data-testid="food-target-unavailable"]').text()).toContain(
      'Запись недоступна',
    )
    expect(wrapper.find('[data-testid="selected-entry"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

const component = (id: string, name: string) => ({
  id,
  name,
  revision: 1,
  quantity: '100',
  unit: 'g',
  local_date: '2026-10-05',
  meal_id: 'lunch-meal',
  meal_kind: 'lunch',
  logged_at: '2026-10-05T12:00:00Z',
})

describe('reading a day', () => {
  it('marks partial sums, estimates and dishes in words, and offers goals for macros', async () => {
    mocks.target = { ...(mocks.target as object), data: ref(undefined) }
    mocks.rich = {
      date: '2026-10-05',
      day_revision: 1,
      completed: false,
      entries: [],
      items: [
        {
          id: 'dish',
          kind: 'dish',
          name: 'суп дня',
          quantity: '300',
          unit: 'g',
          revision: 1,
          local_date: '2026-10-05',
          components: [component('a', 'бульон'), component('b', 'морковь')],
          nutrients: { energy_kcal: '120' },
        },
        {
          ...component('c', 'хлеб'),
          meal_id: 'lunch-meal',
          estimated: true,
          nutrients: { energy_kcal: '80' },
        },
      ],
      totals: {
        energy_kcal: { known: '200', complete: false },
        protein_g: { known: '10', complete: true },
        fat_g: { known: '5', complete: true },
        fiber_g: { known: '0', complete: false },
      },
      goal: {
        id: 'goal',
        calorie_target: '2000',
        effective_local_date: '2026-10-01',
        revision: 1,
        macro_targets: { protein_g: '100' },
      },
      remaining_kcal: null,
      remaining_complete: false,
      drafts: [],
    }
    const { wrapper } = await screen('/food?day=2026-10-05')
    const summary = wrapper.get('[data-testid="day-summary"]')
    expect(summary.text()).toContain('≥')
    expect(summary.text()).toContain('не меньше\u00a0200')
    expect(wrapper.get('[data-testid="macro-protein_g"]').text()).not.toContain('Задать цель')
    expect(wrapper.get('[data-testid="macro-fat_g"]').text()).toContain('Задать цель')
    // No item states its fibre: no data, not «≥ 0 г».
    const fiber = wrapper.get('[data-testid="macro-fiber_g"]').text()
    expect(fiber).not.toContain('Задать цель')
    expect(fiber).toContain('—')
    expect(fiber).toContain('Нет данных')
    expect(fiber).not.toContain('≥')
    const entries = wrapper.get('[data-testid="entries"]')
    expect(entries.text()).toContain('Суп дня')
    expect(entries.text()).toContain('блюдо из 2 продуктов')
    expect(entries.text()).toContain('≈')
    expect(wrapper.get('[data-testid="estimated-legend"]').text()).toContain('приблизительно')
    // One lunch: its calories stand in the «Обед» heading only.
    expect(wrapper.findAll('[data-testid="meal-header"]')[0]!.text()).not.toContain('ккал')
    wrapper.unmount()
  })
})
