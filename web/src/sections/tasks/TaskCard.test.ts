import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { Task } from './api'
import TaskCard from './TaskCard.vue'

vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({
    t: (key: string, values?: Record<string, string>) =>
      values ? `${key}(${Object.values(values).join(',')})` : key,
  }),
}))

const card = (fields: Record<string, unknown>, assignee: string | null = null) =>
  mount(TaskCard, {
    props: {
      task: Task.parse({
        id: 't',
        title: 'Выбрать плитку',
        status: 'open',
        revision: 1,
        ...fields,
      }),
      today: '2026-10-06',
      assignee,
    },
  })

describe('TaskCard', () => {
  it('opens unread discussion independently and hides the badge when there are no messages', async () => {
    const wrapper = card({ discussion: { total: 4, unread: 2 } })
    const badge = wrapper.get('[data-task-discussion]')
    expect(badge.attributes('aria-label')).toBe('tasks.discussionBadge.unread(4,2)')
    expect(badge.text()).toBe('4')
    await badge.trigger('click')
    expect(wrapper.emitted('discussion')?.[0]?.[0]).toMatchObject({ id: 't' })
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.emitted('move')).toBeUndefined()
    expect(
      card({ discussion: { total: 0, unread: 0 } })
        .find('[data-task-discussion]')
        .exists(),
    ).toBe(false)
    expect(
      card({ discussion: { total: 4, unread: 0 } })
        .get('[data-task-discussion]')
        .attributes('aria-label'),
    ).toBe('tasks.discussionBadge.messages(4,0)')
  })

  it('opens title and waiting links independently of selection and movement', async () => {
    const wrapper = card({
      title: 'Read https://example.com/task.',
      state: 'waiting',
      waiting_reason: 'See https://example.com/wait',
    })
    await wrapper.setProps({ canMove: true })
    const links = wrapper.findAll('a')
    expect(links.map((link) => link.attributes('href'))).toEqual([
      'https://example.com/task',
      'https://example.com/wait',
    ])
    expect(wrapper.find('button a').exists()).toBe(false)
    await links[0]!.trigger('click')
    await links[0]!.trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    await links[0]!.trigger('contextmenu')
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.emitted('move')).toBeUndefined()
    await wrapper.get('[data-task="t"]').trigger('click')
    expect(wrapper.emitted('select')).toHaveLength(1)
  })

  it('leaves the state to the column and shows what tells tasks apart', () => {
    const wrapper = card(
      {
        state: 'in_progress',
        due_on: '2026-10-09',
        priority: 1,
        checklist: [
          { id: 'a', text: 'Замерить', done: true },
          { id: 'b', text: 'Сравнить', done: false },
        ],
        subtasks: { total: 3, done: 1 },
        assigned_to: 'u',
      },
      'Мария Волкова',
    )
    const text = wrapper.text()
    expect(text).not.toContain('tasks.states')
    expect(text).toContain('tasks.important')
    expect(text).toContain('1/2')
    expect(text).toContain('1/3')
    expect(text).toContain('МВ')
    expect(wrapper.find('.bg-warning-surface').text()).toContain('tasks.due')
  })

  it('marks an overdue date red, a distant one quiet, and a waiting reason', () => {
    expect(
      card({ due_on: '2026-10-05', overdue: true }).find('.text-destructive').text(),
    ).toContain('tasks.overdue')
    expect(card({ due_on: '2026-10-20' }).find('.bg-muted').text()).toContain('tasks.due')
    expect(card({ state: 'waiting', waiting_reason: 'Счёт от магазина' }).text()).toContain(
      'Счёт от магазина',
    )
  })

  it('keeps a done title readable and marks completion with a check', () => {
    const wrapper = card({ state: 'done', status: 'done', due_on: '2026-10-01', priority: 1 })
    expect(wrapper.text()).toBe('Выбрать плитку')
    expect(wrapper.find('[aria-label="tasks.states.done"]').exists()).toBe(true)
    expect(wrapper.find('.line-clamp-3').text()).toBe('Выбрать плитку')
    expect(wrapper.find('.line-through').exists()).toBe(false)
  })

  it('explains why an open child is shown outside its closed parent', () => {
    const parent = { id: 'parent', title: 'Ремонт', state: 'done', deleted_at: null }
    const wrapper = card({ parent_id: parent.id, parent })
    expect(wrapper.text()).toContain('tasks.parent(Ремонт)')
    expect(wrapper.text()).toContain('tasks.parentCompleted')
    expect(
      card({
        parent_id: parent.id,
        parent: { ...parent, deleted_at: '2026-10-01T12:00:00Z' },
      }).text(),
    ).toContain('tasks.parentDeleted')
    expect(
      card({ parent_id: parent.id, parent, status: 'done', state: 'done' }).text(),
    ).not.toContain('tasks.parentCompleted')
  })

  it('moves a card to the next column with Shift and an arrow, not past the ends', async () => {
    const wrapper = mount(TaskCard, {
      props: {
        task: Task.parse({ id: 't', title: 'Плитка', status: 'open', state: 'todo', revision: 1 }),
        today: '2026-10-06',
        canMove: true,
      },
    })
    const button = wrapper.get('[data-task="t"]')
    await button.trigger('keydown', { key: 'ArrowLeft', shiftKey: true })
    await button.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('move')).toBeUndefined()
    await button.trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    expect(wrapper.emitted('move')![0]![1]).toBe('in_progress')
  })
  it('offers keyboard queue movement only in manual order on open cards', async () => {
    const wrapper = card({ state: 'todo' })
    await wrapper.setProps({ canMove: true, queueMode: 'manual' })
    await wrapper.get('[data-task="t"]').trigger('keydown', { key: 'ArrowDown', altKey: true })
    expect(wrapper.emitted('reorder')?.[0]?.[1]).toBe('down')
    await wrapper.setProps({ queueMode: 'sorted' })
    await wrapper.get('[data-task="t"]').trigger('keydown', { key: 'ArrowUp', altKey: true })
    expect(wrapper.emitted('manual')).toEqual([[]])
    await wrapper.setProps({ queueMode: 'filtered' })
    await wrapper.get('[data-task="t"]').trigger('keydown', { key: 'ArrowDown', altKey: true })
    expect(wrapper.emitted('reorder')).toHaveLength(1)
  })

  it('on a phone asks the board for its move sheet from «⋯», a long press or a right click', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = card({ state: 'todo' })
      await wrapper.setProps({ canMove: true, sheet: true })
      const more = wrapper.get('button[aria-label="tasks.board.moveTask(Выбрать плитку)"]')
      expect(more.attributes('aria-haspopup')).toBe('dialog')
      expect(more.classes()).toContain('size-11')
      await more.trigger('click')
      const target = wrapper.get('[data-task="t"]')
      await target.trigger('pointerdown', { pointerType: 'touch', clientX: 10, clientY: 10 })
      vi.advanceTimersByTime(450)
      // The click that ends the long press does not open the task as well.
      await target.trigger('click')
      await target.trigger('contextmenu')
      expect(wrapper.emitted('actions')).toHaveLength(3)
      expect(wrapper.emitted('actions')![0]![0]).toMatchObject({ id: 't' })
      expect(wrapper.emitted('select')).toBeUndefined()
      expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps the title the full width and leaves room for «⋯» only on the bottom line', async () => {
    const title = (wrapper: ReturnType<typeof card>) =>
      wrapper.get('.line-clamp-3').element.parentElement!
    const plain = card({ state: 'todo' })
    await plain.setProps({ canMove: true, sheet: true })
    expect(title(plain).classList).toContain('pe-9')
    const dated = card({ state: 'todo', due_on: '2026-12-01' })
    await dated.setProps({ canMove: true, sheet: true })
    expect(title(dated).classList).not.toContain('pe-9')
    expect(dated.get('[data-task="t"]').element.parentElement!.classList).not.toContain('pe-11')
    const waiting = card({ state: 'waiting', due_on: '2026-12-01', waiting_reason: 'Ответ' })
    await waiting.setProps({ canMove: true })
    expect(waiting.get('.text-warning').classes()).toContain('pe-9')
    expect(waiting.get('.text-warning').element.previousElementSibling!.classList).not.toContain(
      'pe-9',
    )
    // Without moves there is no «⋯» and nothing to leave room for.
    expect(title(card({ state: 'todo' })).classList).not.toContain('pe-9')
  })
})
