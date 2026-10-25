import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import Suggestions from './Suggestions.vue'

const state = vi.hoisted(() => ({
  scopes: new Set<string>(),
  diary: null as unknown as { data: { value: unknown }; isPending: { value: boolean } },
  tasks: null as unknown as { data: { value: unknown }; isPending: { value: boolean } },
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { timezone: 'Europe/Moscow' },
    can: (scope: string) => state.scopes.has(scope),
  }),
}))
vi.mock('@/sections/food/useFood', () => ({ useFoodDay: () => state.diary }))
vi.mock('@/sections/today/useTodayTasks', () => ({ useTodayTasks: () => state.tasks }))

const EMPTY_DAY = { items: [], drafts: [] }
const TASKS = { items: [{ id: 't', title: 'Позвонить', status: 'open', revision: 1 }] }

function suggestions(examples: string[] = []) {
  return mount(Suggestions, { props: { examples }, global: { plugins: [i18n] } })
}
const labels = (wrapper: ReturnType<typeof suggestions>) =>
  wrapper.findAll('button').map((button) => button.text())

beforeEach(() => {
  vi.useFakeTimers()
  // 09:00 in Moscow.
  vi.setSystemTime(new Date('2026-10-07T06:00:00Z'))
  state.scopes = new Set(['nutrition.read', 'nutrition.write', 'tasks.read'])
  state.diary = { data: ref(EMPTY_DAY), isPending: ref(false) }
  state.tasks = { data: ref(TASKS), isPending: ref(false) }
})
afterEach(() => vi.useRealTimers())

describe('suggestions above the composer', () => {
  it('begins a meal and asks about the day, by the person’s clock', async () => {
    const wrapper = suggestions()
    expect(wrapper.get('[role=group]').attributes('aria-label')).toBe('Подсказки')
    expect(labels(wrapper)).toEqual(['Записать завтрак', 'Что у меня на сегодня?'])
    await wrapper.findAll('button')[0]!.trigger('click')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('start')).toEqual([['На завтрак ']])
    expect(wrapper.emitted('ask')).toEqual([['Что у меня на сегодня?']])
  })

  it('waits for the day to load and shows nothing when nothing applies', async () => {
    state.diary.isPending.value = true
    const wrapper = suggestions()
    expect(wrapper.find('[role=group]').exists()).toBe(false)
    state.diary.isPending.value = false
    state.tasks.data.value = { items: [] }
    state.diary.data.value = { items: [], drafts: [{ id: 'd', meal_kind: 'breakfast' }] }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role=group]').exists()).toBe(false)
  })

  it('reads only what the account may and keeps examples for an empty conversation', () => {
    state.scopes = new Set(['tasks.read'])
    state.diary = { data: ref(undefined), isPending: ref(true) }
    const wrapper = suggestions(['Съел овсянку с бананом', 'Что у меня на сегодня?'])
    expect(labels(wrapper)).toEqual(['Что у меня на сегодня?', 'Съел овсянку с бананом'])
  })
})
