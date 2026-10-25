import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { Task } from './api'
import TaskMoveSheet from './TaskMoveSheet.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, values?: Record<string, string>) =>
      values ? `${key}(${Object.values(values).join(',')})` : key,
  }),
}))

// The sheet is a Panel; here it renders its slot in place.
const PanelStub = defineComponent({
  props: { open: Boolean, title: String, description: String },
  setup(props, { slots }) {
    return () =>
      props.open
        ? h('section', { 'data-title': props.title, 'data-description': props.description }, [
            slots.default?.(),
          ])
        : null
  },
})

const task = Task.parse({
  id: 't',
  title: 'Выбрать плитку',
  status: 'open',
  state: 'todo',
  waiting_reason: 'Ответ мастера',
  revision: 1,
})
const counts = { todo: 3, in_progress: 1, waiting: 0, done: 2 }
function sheet(props: Record<string, unknown> = {}) {
  return mount(TaskMoveSheet, {
    props: { task, column: 'todo', counts, queueMode: 'manual', ...props },
    global: { stubs: { Panel: PanelStub } },
  })
}
const rows = (wrapper: ReturnType<typeof sheet>) => wrapper.findAll('ul button')

describe('move sheet', () => {
  it('lists the four states with their counts and marks where the task stands', () => {
    const wrapper = sheet()
    expect(wrapper.get('section').attributes('data-title')).toBe('tasks.board.moveTo')
    expect(wrapper.get('section').attributes('data-description')).toBe('Выбрать плитку')
    expect(rows(wrapper).map((row) => row.text())).toEqual([
      'tasks.states.todo3',
      'tasks.states.in_progress1',
      'tasks.states.waiting0',
      'tasks.states.done2',
    ])
    expect(rows(wrapper)[0]!.attributes('aria-current')).toBe('true')
    expect(rows(wrapper)[0]!.attributes('disabled')).toBeDefined()
    expect(rows(wrapper)[1]!.attributes('aria-current')).toBeUndefined()
  })

  it('moves to another state and closes', async () => {
    const wrapper = sheet()
    await rows(wrapper)[1]!.trigger('click')
    expect(wrapper.emitted('move')?.[0]).toEqual([task, 'in_progress'])
    expect(wrapper.emitted('update:task')?.[0]).toEqual([null])
  })

  it('asks what the task waits for in the same sheet', async () => {
    const wrapper = sheet()
    await rows(wrapper)[2]!.trigger('click')
    expect(wrapper.emitted('move')).toBeUndefined()
    expect(wrapper.get('section').attributes('data-title')).toBe('tasks.board.waitingTitle')
    const field = wrapper.get<HTMLInputElement>('#move-waiting-reason')
    expect(field.element.value).toBe('Ответ мастера')
    await field.setValue('Счёт')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('move')?.[0]).toEqual([task, 'waiting', 'Счёт'])
  })

  it('places the task in the queue in manual order and opens it', async () => {
    const wrapper = sheet()
    const queue = wrapper.get('[role="group"]').findAll('button')
    expect(queue.map((button) => button.text())).toEqual([
      'tasks.queue.top',
      'tasks.queue.up',
      'tasks.queue.down',
      'tasks.queue.end',
    ])
    await queue[0]!.trigger('click')
    expect(wrapper.emitted('reorder')?.[0]).toEqual([task, 'top'])
    const again = sheet()
    const open = again.findAll('button').find((button) => button.text() === 'tasks.board.open')
    await open!.trigger('click')
    expect(again.emitted('select')?.[0]).toEqual([task])
  })

  it('offers its own order when sorted, explains a filter, and has no queue in «Готово»', async () => {
    const sorted = sheet({ queueMode: 'sorted' })
    expect(sorted.find('[role="group"]').exists()).toBe(false)
    const manual = sorted
      .findAll('button')
      .find((button) => button.text() === 'tasks.queue.useManual')
    await manual!.trigger('click')
    expect(sorted.emitted('manual')).toHaveLength(1)
    expect(sheet({ queueMode: 'filtered' }).text()).toContain('tasks.queue.filtered')
    const done = sheet({ column: 'done' })
    expect(done.find('[role="group"]').exists()).toBe(false)
  })
})
