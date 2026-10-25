import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import ColumnAdd from './ColumnAdd.vue'

const mocks = vi.hoisted(() => ({
  operate: vi.fn(),
  setState: vi.fn(),
  get: vi.fn(),
  error: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('vue-sonner', () => ({ toast: { error: mocks.error } }))
vi.mock('@/core/operations', () => ({ useLedger: () => ({ operate: mocks.operate }) }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('./useTaskActions', () => ({ useTaskActions: () => ({ setState: mocks.setState }) }))
enableAutoUnmount(afterEach)

const task = {
  id: 'new-task',
  title: 'Book a room',
  list_id: 'project',
  status: 'open',
  state: 'waiting',
  revision: 1,
}
const receipt = { operation_id: 'operation', kind: 'task.create', tasks: [task] }
const composer = () =>
  mount(ColumnAdd, {
    props: { listId: 'project', state: 'waiting' },
    attachTo: document.body,
  })

/** A paste of `text` into the field, as the clipboard hands it over. */
function paste(input: Element, text: string) {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', { value: { getData: () => text } })
  input.dispatchEvent(event)
  return event
}
type Create = { payload: { title: string } }
const titles = () =>
  mocks.operate.mock.calls.map(([operation]) => (operation as Create).payload.title)

beforeEach(() => {
  mocks.operate.mockReset().mockResolvedValue(receipt)
  mocks.get.mockReset()
  mocks.setState.mockReset().mockResolvedValue(true)
  mocks.error.mockReset()
})

describe('board composer', () => {
  it('creates without implicit dates and stays focused for the next task', async () => {
    const wrapper = composer()
    const title = wrapper.get<HTMLInputElement>('input[maxlength="300"]')
    await title.setValue(task.title)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.create',
      payload: { title: task.title, state: 'waiting' },
      target_resource_id: 'project',
    })
    expect(title.element.value).toBe('')
    expect(document.activeElement).toBe(title.element)
    expect(wrapper.get('[role="status"]').text()).toBe('tasks.board.created')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.board.openCreated')!
      .trigger('click')
    expect(wrapper.emitted('select')).toEqual([[task]])
  })

  it('retains every field after a failed write and sends only explicit optional values', async () => {
    mocks.operate.mockRejectedValueOnce(new Error('Unavailable'))
    const wrapper = composer()
    const title = wrapper.get<HTMLInputElement>('input[maxlength="300"]')
    const due = wrapper.get<HTMLInputElement>('input[type="date"]')
    const reason = wrapper.get<HTMLInputElement>('input[maxlength="2000"]')
    await title.setValue('Book a room')
    await due.setValue('2026-11-04')
    await reason.setValue('Reply from the hotel')
    await wrapper.get('select').setValue('1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(title.element.value).toBe('Book a room')
    expect(due.element.value).toBe('2026-11-04')
    expect(reason.element.value).toBe('Reply from the hotel')
    expect(wrapper.get<HTMLSelectElement>('select').element.value).toBe('1')
    expect(mocks.error).toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith({
      kind: 'task.create',
      payload: {
        title: 'Book a room',
        state: 'waiting',
        due_on: '2026-11-04',
        priority: 1,
        waiting_reason: 'Reply from the hotel',
      },
      target_resource_id: 'project',
    })
    expect(due.element.value).toBe('')
    expect(reason.element.value).toBe('')
  })

  it('queries the first manual root before paging so orphaned children cannot be neighbours', async () => {
    mocks.get.mockResolvedValue({
      items: [{ ...task, id: 'first' }, task],
      next_cursor: null,
      total: 2,
      today: '2026-10-08',
      timezone: 'UTC',
    })
    const wrapper = composer()
    await wrapper.get('input[maxlength="300"]').setValue(task.title)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.board.toTop')!
      .trigger('click')
    await flushPromises()
    const url = new URL(mocks.get.mock.calls[0]![0] as string, 'https://example.test')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      view: 'open',
      scope: 'all',
      list_id: 'project',
      state: 'waiting',
      sort: 'manual',
      parent_id: 'root',
      completed_scope: 'recent',
    })
    expect(mocks.setState).toHaveBeenCalledWith(
      task,
      'waiting',
      'tasks.queue.moved',
      undefined,
      'first',
    )
    expect(wrapper.emitted('reordered')).toEqual([[]])
  })

  it('offers pasted lines as one task each, in order, or all as one task', async () => {
    const wrapper = composer()
    const title = wrapper.get<HTMLInputElement>('input[maxlength="300"]')
    const event = paste(title.element, 'Купить хлеб\n\n  Позвонить врачу \r\nЗаписать счёт')
    await nextTick()
    expect(event.defaultPrevented).toBe(true)
    const [, asOne] = wrapper.get('[data-testid="board-pasted"]').findAll('button')
    await asOne!.trigger('click')
    expect(title.element.value).toBe('Купить хлеб Позвонить врачу Записать счёт')
    expect(wrapper.find('[data-testid="board-pasted"]').exists()).toBe(false)
    expect(mocks.operate).not.toHaveBeenCalled()

    paste(title.element, 'Купить хлеб\nПозвонить врачу')
    await nextTick()
    await wrapper.get('[data-testid="board-pasted"] button').trigger('click')
    await flushPromises()
    expect(titles()).toEqual(['Купить хлеб', 'Позвонить врачу'])
    expect(wrapper.get('[role="status"]').text()).toBe('tasks.board.createdMany')
    expect(wrapper.find('[data-testid="board-pasted"]').exists()).toBe(false)
  })

  it('keeps the lines not yet added when one fails, and a single line is an ordinary paste', async () => {
    mocks.operate.mockResolvedValueOnce(receipt).mockRejectedValueOnce(new Error('Unavailable'))
    const wrapper = composer()
    const title = wrapper.get<HTMLInputElement>('input[maxlength="300"]')
    paste(title.element, 'Один\nДва\nТри')
    await nextTick()
    await wrapper.get('[data-testid="board-pasted"] button').trigger('click')
    await flushPromises()
    expect(mocks.error).toHaveBeenCalled()
    await wrapper.get('[data-testid="board-pasted"] button').trigger('click')
    await flushPromises()
    expect(titles()).toEqual(['Один', 'Два', 'Два', 'Три'])
    expect(paste(title.element, 'Одна строка').defaultPrevented).toBe(false)
  })
})
