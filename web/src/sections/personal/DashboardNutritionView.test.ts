import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { api } from '@/core/client'
import { shiftDay } from '@/core/dates'
import ru from '@/locales/ru.json'
import DashboardNutrition from './DashboardNutrition.vue'

const access = vi.hoisted(() => ({ scopes: ['nutrition.read', 'nutrition.write'] }))
vi.mock('@/core/client', () => ({ api: { get: vi.fn() } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ can: (scope: string) => access.scopes.includes(scope) }),
}))
vi.mock('@/sections/food/GoalPanel.vue', () => ({
  default: {
    props: ['day', 'goal', 'open'],
    template:
      '<div data-testid="goal-panel" :data-day="day" :data-limit="goal?.calorie_target" :data-revision="goal?.revision" :data-open="open" />',
  },
}))

const today = '2026-10-05'
const entry = {
  id: 'entry',
  revision: 1,
  quantity: '1',
  unit: 'g',
  local_date: today,
  estimated: true,
}
function day(date: string) {
  const before = date < '2026-09-30'
  return {
    date,
    day_revision: 1,
    completed: date !== today,
    entries: [{ ...entry, local_date: date }],
    items: [],
    drafts: [],
    remaining_kcal: before ? '70' : '170',
    remaining_complete: true,
    totals: {
      energy_kcal: { known: '2030', complete: true },
      protein_g: { known: '125', complete: true },
      fat_g: { known: '60', complete: true },
      carbohydrate_g: { known: '200', complete: true },
    },
    goal: {
      id: before ? 'old-goal' : 'goal',
      revision: 1,
      calorie_target: before ? '2100' : '2200',
      effective_local_date: before ? '2026-09-01' : '2026-09-30',
      macro_targets: { protein_g: before ? '115' : '120' },
    },
  }
}
function range(start: string, end: string) {
  const days = []
  for (let date = start; date <= end; date = shiftDay(date, 1)) {
    const details = day(date)
    days.push({
      date,
      logged: true,
      energy_kcal: '2030',
      energy_complete: true,
      protein_g: date === '2026-10-03' ? '103' : '125',
      protein_complete: date !== '2026-10-03',
      fat_g: '60',
      carbohydrate_g: '200',
      completed: date !== today,
      target: details.goal.calorie_target,
      macro_targets: details.goal.macro_targets,
      kcal_by_kind: {},
    })
  }
  return { days }
}
const mounted: ReturnType<typeof mount>[] = []
const queryClients: QueryClient[] = []
beforeEach(() => {
  access.scopes = ['nutrition.read', 'nutrition.write']
  vi.mocked(api.get).mockImplementation(async (path) => {
    const url = new URL(path, 'https://synthetic.invalid')
    return url.pathname.endsWith('/range')
      ? range(url.searchParams.get('start')!, url.searchParams.get('end')!)
      : day(url.searchParams.get('date')!)
  })
})
afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  for (const client of queryClients.splice(0)) client.clear()
  vi.clearAllMocks()
})
async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ name: 'food', path: '/food', component: { template: '<div />' } }],
  })
  await router.push('/food')
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  queryClients.push(queryClient)
  const wrapper = mount(DashboardNutrition, {
    props: { day: today, today, timezone: 'Europe/Moscow' },
    global: {
      stubs: {
        Panel: { props: ['open'], template: '<aside v-if="open"><slot /></aside>' },
      },
      plugins: [
        router,
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        [
          VueQueryPlugin,
          {
            queryClient,
          },
        ],
      ],
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('nutrition dashboard interactions', () => {
  it('never reads nutrition without its read scope', async () => {
    access.scopes = ['nutrition.write']
    const wrapper = await render()
    expect(wrapper.find('[data-testid="dashboard-nutrition"]').exists()).toBe(false)
    expect(api.get).not.toHaveBeenCalled()
  })

  it('keeps primary actions on the overview day while inspecting historical limits', async () => {
    const wrapper = await render()
    expect(wrapper.get('[data-testid="nutrition-day-2026-10-03"]').attributes('data-state')).toBe(
      'pending',
    )
    expect(wrapper.get('[data-testid="nutrition-day-2026-09-29"]').attributes('data-state')).toBe(
      'goal',
    )
    await wrapper.get('[data-testid="nutrition-day-2026-09-29"]').trigger('click')
    expect(wrapper.get('[data-testid="nutrition-readout"]').text()).toContain('2 100')
    expect(wrapper.get('[data-testid="nutrition-readout"]').text()).toContain('115')
    expect(wrapper.props('day')).toBe(today)
    expect(wrapper.get('[data-testid="nutrition-diary"]').attributes('href')).toContain(
      `day=${today}`,
    )
    expect(wrapper.get('[data-testid="nutrition-selected-diary"]').attributes('href')).toContain(
      'day=2026-09-29',
    )
    expect(wrapper.get('[data-testid="nutrition-day-description"]').text()).toContain('2 100')
    await wrapper.get('[data-testid="nutrition-goals"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="goal-panel"]').attributes('data-day')).toBe(today)
    expect(wrapper.get('[data-testid="goal-panel"]').attributes('data-limit')).toBe('2200')
    expect(wrapper.get('[data-testid="goal-panel"]').attributes('data-open')).toBe('true')
    expect(api.get).toHaveBeenCalledWith(`/v1/nutrition/day?date=${today}`, expect.any(AbortSignal))
    // A refresh while editing must not give the old form a new revision to overwrite.
    const updated = day(today)
    queryClients[0]!.setQueryData(['nutrition', 'day', today], {
      ...updated,
      goal: { ...updated.goal, revision: 2, calorie_target: '2300' },
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="goal-panel"]').attributes('data-limit')).toBe('2200')
    expect(wrapper.get('[data-testid="goal-panel"]').attributes('data-revision')).toBe('1')
  })

  it('announces a focused day and only opens its panel on activation', async () => {
    const wrapper = await render()
    const bar = wrapper.get('[data-testid="nutrition-day-2026-09-29"]')
    await bar.trigger('focus')
    expect(wrapper.get('[data-testid="nutrition-readout"]').text()).toContain('29 сентября')
    expect(bar.attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="nutrition-day-panel"]').exists()).toBe(false)
    await bar.trigger('click')
    expect(wrapper.find('[data-testid="nutrition-day-panel"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="nutrition-selected-diary"]').attributes('href')).toContain(
      'day=2026-09-29',
    )
  })

  it('closes chart details when changing period or overview day', async () => {
    const wrapper = await render()
    await wrapper.get('[data-testid="nutrition-day-2026-09-29"]').trigger('click')
    await wrapper.get('[data-testid="nutrition-period-14"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="nutrition-day-panel"]').exists()).toBe(false)
    expect(wrapper.findAll('.nutrition-day-selected')).toHaveLength(0)
    await wrapper.get('[data-testid="nutrition-day-2026-09-29"]').trigger('click')
    await wrapper.setProps({ day: '2026-10-04' })
    await flushPromises()
    expect(wrapper.find('[data-testid="nutrition-day-panel"]').exists()).toBe(false)
    expect(wrapper.findAll('.nutrition-day-selected')).toHaveLength(0)
    expect(wrapper.get('[data-testid="nutrition-diary"]').attributes('href')).toContain(
      'day=2026-10-04',
    )
  })

  it('shows unresolved food even when no completed entries are logged', async () => {
    vi.mocked(api.get).mockImplementation(async (path) => {
      const url = new URL(path, 'https://synthetic.invalid')
      if (url.pathname.endsWith('/range'))
        return range(url.searchParams.get('start')!, url.searchParams.get('end')!)
      return {
        ...day(today),
        entries: [],
        drafts: [
          {
            id: 'draft',
            label: 'Unresolved food',
            status: 'unresolved',
            missing_fields: ['snapshot'],
            revision: 1,
          },
        ],
      }
    })
    const wrapper = await render()
    expect(wrapper.get('[data-testid="nutrition-drafts"]').text()).toBe('Незавершённых записей: 1')
    expect(wrapper.get('[data-testid="nutrition-day-2026-10-05"]').attributes('data-state')).toBe(
      'empty',
    )
  })

  it('uses 14 calendar days and hides writes for read-only users', async () => {
    access.scopes = ['nutrition.read']
    const wrapper = await render()
    expect(wrapper.findAll('button').some((button) => button.text() === 'Изменить цели')).toBe(
      false,
    )
    await wrapper.get('[data-testid="nutrition-period-14"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="nutrition-day-"]')).toHaveLength(14)
    expect(api.get).toHaveBeenCalledWith(
      '/v1/nutrition/range?start=2026-09-22&end=2026-10-05',
      expect.any(AbortSignal),
    )
    expect(wrapper.find('[style]').exists()).toBe(false)
  })
})
