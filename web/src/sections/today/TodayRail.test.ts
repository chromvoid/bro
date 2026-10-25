import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import TodayRail from './TodayRail.vue'

const scopes = vi.hoisted(() => new Set<string>())
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { timezone: 'UTC' },
    can: (scope: string) => scopes.has(scope),
  }),
}))

function rail() {
  return mount(TodayRail, {
    global: {
      plugins: [i18n],
      stubs: { FoodCard: true, TimersCard: true, TasksCard: true, RouterLink: true },
    },
  })
}

describe('the day beside the conversation', () => {
  it('shows the cards of «Сегодня» this account may read', () => {
    scopes.clear()
    ;['nutrition.read', 'timers.read', 'tasks.read'].forEach((scope) => scopes.add(scope))
    const wrapper = rail()
    expect(wrapper.get('aside').attributes('aria-labelledby')).toBe('today-rail-title')
    expect(wrapper.find('food-card-stub').attributes('day')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(wrapper.find('timers-card-stub').exists()).toBe(true)
    expect(wrapper.find('tasks-card-stub').exists()).toBe(true)
  })

  it('leaves out what the account cannot read', () => {
    scopes.clear()
    scopes.add('tasks.read')
    const wrapper = rail()
    expect(wrapper.find('food-card-stub').exists()).toBe(false)
    expect(wrapper.find('timers-card-stub').exists()).toBe(false)
    expect(wrapper.find('tasks-card-stub').exists()).toBe(true)
  })

  it('is absent when the account can read none of its cards', () => {
    scopes.clear()
    scopes.add('session.control')
    expect(rail().find('aside').exists()).toBe(false)
  })
})
