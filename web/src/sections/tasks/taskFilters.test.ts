import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { RadioGroup } from '@/components/ui/radio-group'
import { EMPTY_TASK_FILTERS, type TaskFilterState } from './taskFilters'
import TaskFilters from './TaskFilters.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, values?: Record<string, unknown>) =>
      values ? `${key}(${Object.values(values).join(',')})` : key,
  }),
}))

const state = (patch: Partial<TaskFilterState> = {}): TaskFilterState => ({
  ...EMPTY_TASK_FILTERS,
  ...patch,
})
const stubs = {
  Popover: { template: '<div><slot /></div>' },
  PopoverTrigger: { template: '<div><slot /></div>' },
  PopoverContent: { template: '<div><slot /></div>' },
}

describe('TaskFilters', () => {
  it('adds no summary row to the default board', () => {
    const wrapper = mount(TaskFilters, {
      props: { filters: state(), placement: 'summary', total: 130 },
    })
    expect(wrapper.find('[data-testid="task-filters-summary"]').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('uses a header action and patches independent conditions without clearing the others', async () => {
    const wrapper = mount(TaskFilters, {
      props: { filters: state({ overdue: true }), shared: true },
      global: { stubs },
    })
    expect(wrapper.get('[data-testid="task-filters-action"]').attributes('aria-label')).toBe(
      'tasks.filters.actionCount(1)',
    )
    await wrapper.get('[role="checkbox"]').trigger('click')
    expect(wrapper.emitted('change')?.[0]).toEqual([{ important: true }])
    wrapper.getComponent(RadioGroup).vm.$emit('update:modelValue', 'me')
    wrapper.getComponent(RadioGroup).vm.$emit('update:modelValue', 'unassigned')
    expect(wrapper.emitted('change')?.slice(-2)).toEqual([
      [{ assignee: 'me' }],
      [{ assignee: 'unassigned' }],
    ])
  })

  it('offers assignee choices only for shared projects', () => {
    const wrapper = mount(TaskFilters, {
      props: { filters: state() },
      global: { stubs },
    })
    expect(wrapper.findComponent(RadioGroup).exists()).toBe(false)
    expect(wrapper.text()).not.toContain('tasks.filters.assignees.me')
  })

  it('shows the exact total, active search and project scope, including a zero result', async () => {
    const wrapper = mount(TaskFilters, {
      props: {
        filters: state({ q: 'literal query', important: true, assignee: 'me' }),
        placement: 'summary',
        projectName: 'Synthetic project',
        total: 0,
      },
    })
    expect(wrapper.get('[role="status"]').text()).toContain('tasks.filters.results(0)')
    expect(wrapper.text()).toContain('tasks.filters.projectScope(Synthetic project)')
    expect(wrapper.text()).toContain('tasks.filters.noneInProject')
    expect(wrapper.get('[data-task-filter="q"]').text()).toContain(
      'tasks.filters.query(literal query)',
    )
    await wrapper.get('[data-task-filter="important"]').trigger('click')
    expect(wrapper.emitted('change')?.[0]).toEqual([{ important: false }])
    await wrapper.get('[data-testid="task-filters-whole-history"]').trigger('click')
    expect(wrapper.emitted('wholeHistory')).toHaveLength(1)
  })

  it('does not present an old or unavailable result count as a current exact count', async () => {
    const wrapper = mount(TaskFilters, {
      props: { filters: state({ overdue: true }), placement: 'summary', total: 0, pending: true },
    })
    expect(wrapper.get('[role="status"]').text()).toBe('tasks.filters.updating')
    expect(wrapper.text()).not.toContain('tasks.filters.none')
    await wrapper.setProps({ pending: false, total: null })
    expect(wrapper.get('[role="status"]').text()).toBe('tasks.filters.updating')
    await wrapper.setProps({ total: 130 })
    expect(wrapper.get('[role="status"]').text()).toBe('tasks.filters.results(130)')
  })

  it('keeps whole-history scope visible and offers a single reset action', async () => {
    const wrapper = mount(TaskFilters, {
      props: {
        filters: state({ q: 'query', completed_scope: 'all' }),
        placement: 'summary',
        total: 1,
      },
    })
    expect(wrapper.get('[data-task-filter="completed_scope"]').text()).toContain(
      'tasks.filters.allHistory',
    )
    expect(wrapper.find('[data-testid="task-filters-whole-history"]').exists()).toBe(false)
    await wrapper.get('[data-task-filter="completed_scope"]').trigger('click')
    expect(wrapper.emitted('change')?.[0]).toEqual([{ completed_scope: 'recent' }])
    const reset = wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.filters.reset')
    await reset!.trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })
})
