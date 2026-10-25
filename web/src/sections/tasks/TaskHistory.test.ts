import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import TaskHistory from './TaskHistory.vue'
import { evictList } from './useTaskBoardPages'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
const session = reactive({ me: { id: 'reader', timezone: 'Europe/Moscow' } })
vi.mock('@/core/client', () => ({ api: mocks }))
vi.mock('@/stores/session', () => ({ useSession: () => session }))
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))

const event = (id: string, actor = 'Анна') => ({
  id,
  created_at: '2026-10-08T11:30:00+00:00',
  actor: { id: 'actor', name: actor },
  kind: 'updated',
  changes: { description: {} },
  task_revision: 2,
  reason: null,
})
const page = (items = [event('event')], next_cursor: string | null = null) => ({
  items,
  people: [],
  next_cursor,
})
const denied = () => new ApiError(403, 'forbidden', null, null, null)
const mounted: Array<{ wrapper: VueWrapper; client: QueryClient }> = []
function screen(props: Partial<{ taskId: string | null; revision: number; active: boolean }> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(TaskHistory, {
    props: { taskId: 'task', revision: 2, ...props },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] },
  })
  mounted.push({ wrapper, client })
  return { wrapper, client }
}
function deferred() {
  let resolve!: (result: unknown) => void
  const promise = new Promise((yes) => (resolve = yes))
  return { promise, resolve }
}
const more = (wrapper: VueWrapper) =>
  wrapper.findAll('button').find((button) => button.text() === 'tasks.history.loadMore')!

beforeEach(() => {
  mocks.get.mockReset()
  session.me = { id: 'reader', timezone: 'Europe/Moscow' }
})
afterEach(() => {
  for (const { wrapper, client } of mounted.splice(0)) {
    wrapper.unmount()
    client.clear()
  }
})

describe('task history panel', () => {
  it('shows an author, zoned timestamp, action and safe changes without interpreting text', async () => {
    mocks.get.mockResolvedValueOnce({
      ...page(),
      items: [
        {
          ...event('event', '<img src=x onerror=alert(1)>'),
          kind: 'unassigned',
          changes: { assigned_to: { before: 'assignee', after: null }, description: {} },
          reason: 'member_left',
        },
      ],
      people: [{ id: 'assignee', name: 'Иван' }],
    })
    const { wrapper } = screen()
    await flushPromises()
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-10-08T11:30:00+00:00')
    expect(wrapper.get('time').text()).toContain('14:30')
    expect(wrapper.text()).toContain('tasks.history.kinds.unassigned')
    expect(wrapper.text()).toContain('Иван')
    expect(wrapper.text()).toContain('tasks.history.unassigned')
    expect(wrapper.text()).toContain('tasks.history.memberLeft')
    expect(wrapper.text()).toContain('tasks.history.fields.description')
    expect(mocks.get).toHaveBeenCalledWith(
      '/v1/tasks/items/task/history?limit=50',
      expect.any(AbortSignal),
    )
  })

  it('loads the next cursor and displays each event once', async () => {
    mocks.get.mockResolvedValueOnce(page([event('newer')], 'cursor / older'))
    const { wrapper } = screen()
    await flushPromises()
    mocks.get.mockResolvedValueOnce(page([event('newer'), event('older')]))
    await more(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.get.mock.calls[1]![0]).toBe(
      '/v1/tasks/items/task/history?limit=50&cursor=cursor+%2F+older',
    )
    expect(
      wrapper.findAll('[data-history-event]').map((item) => item.attributes('data-history-event')),
    ).toEqual(['newer', 'older'])
    expect(more(wrapper)).toBeUndefined()
  })

  it('explains automatic unassignment after account deletion using the anonymized actor', async () => {
    mocks.get.mockResolvedValueOnce({
      ...page(),
      items: [
        {
          ...event('deleted-account', 'Удалённый пользователь'),
          kind: 'unassigned',
          changes: { assigned_to: { before: 'deleted-user', after: null } },
          reason: 'account_deleted',
        },
      ],
      people: [{ id: 'deleted-user', name: 'Удалённый пользователь' }],
    })
    const { wrapper } = screen()
    await flushPromises()
    expect(wrapper.text()).toContain('tasks.history.accountDeleted')
    expect(wrapper.text()).toContain('Удалённый пользователь')
    expect(wrapper.text()).not.toContain('tasks.history.memberLeft')
    expect(wrapper.text()).not.toContain('deleted-user')
  })

  it('discards a previous task response after switching tasks during a read', async () => {
    const first = deferred()
    mocks.get.mockReturnValueOnce(first.promise).mockResolvedValueOnce(page([event('next')]))
    const { wrapper } = screen()
    const firstSignal = mocks.get.mock.calls[0]![1] as AbortSignal
    await wrapper.setProps({ taskId: 'next/task' })
    await flushPromises()
    expect(firstSignal.aborted).toBe(true)
    first.resolve(page([event('old-private')]))
    await flushPromises()
    expect(mocks.get.mock.calls[1]![0]).toContain('/next%2Ftask/history')
    expect(wrapper.find('[data-history-event="next"]').exists()).toBe(true)
    expect(wrapper.find('[data-history-event="old-private"]').exists()).toBe(false)
  })

  it('separates accounts even when they have the same task selected', async () => {
    const previous = deferred()
    mocks.get.mockReturnValueOnce(previous.promise).mockResolvedValueOnce(page([event('new-user')]))
    const { wrapper, client } = screen()
    session.me = { id: 'other-reader', timezone: 'UTC' }
    await flushPromises()
    previous.resolve(page([event('old-user')]))
    await flushPromises()
    expect(wrapper.find('[data-history-event="new-user"]').exists()).toBe(true)
    expect(wrapper.find('[data-history-event="old-user"]').exists()).toBe(false)
    expect(client.getQueryData(['tasks', 'history', 'task', 'other-reader'])).toBeDefined()
  })

  it('refetches after a task revision changes and keeps the deletion event readable', async () => {
    mocks.get.mockResolvedValueOnce(page([event('updated')]))
    const { wrapper } = screen()
    await flushPromises()
    mocks.get.mockResolvedValueOnce({
      ...page(),
      items: [{ ...event('deleted'), kind: 'deleted', changes: { deleted_at: {} } }],
    })
    await wrapper.setProps({ revision: 3 })
    await flushPromises()
    expect(wrapper.text()).toContain('tasks.history.kinds.deleted')
    expect(wrapper.find('[data-history-event="updated"]').exists()).toBe(false)
  })

  it('clears all pages and people when a later page is refused', async () => {
    mocks.get.mockResolvedValueOnce({
      ...page([event('private')], 'older'),
      people: [{ id: 'person', name: 'Private person' }],
    })
    const { wrapper, client } = screen()
    await flushPromises()
    mocks.get.mockRejectedValueOnce(denied())
    await more(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-history-event]').exists()).toBe(false)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true)
    expect(wrapper.emitted('denied')).toHaveLength(1)
    expect(client.getQueryData(['tasks', 'history', 'task', 'reader'])).toEqual({
      pages: [],
      pageParams: [],
    })
    mocks.get.mockResolvedValueOnce(page([event('restored-access')]))
    await wrapper.get('[data-state="error"] button').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(3)
    expect(mocks.get.mock.calls.at(-1)![0]).toBe('/v1/tasks/items/task/history?limit=50')
    expect((mocks.get.mock.calls.at(-1)![1] as AbortSignal).aborted).toBe(false)
    expect(wrapper.find('[data-history-event="restored-access"]').exists()).toBe(true)
  })

  it('clears history immediately when the parent task read loses access', async () => {
    mocks.get.mockResolvedValueOnce(page([event('private')]))
    const { wrapper, client } = screen()
    await flushPromises()
    await wrapper.setProps({ error: new ApiError(404, 'not_found', null, null, null) })
    await flushPromises()
    expect(wrapper.find('[data-history-event]').exists()).toBe(false)
    expect(client.getQueryData(['tasks', 'history', 'task', 'reader'])).toEqual({
      pages: [],
      pageParams: [],
    })
    await wrapper.get('[data-state="error"] button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })

  it('uses the common task query identity so revoking a list evicts account-scoped history too', async () => {
    mocks.get.mockResolvedValueOnce(page([event('private')]))
    const { wrapper, client } = screen()
    await flushPromises()
    client.setQueryData(['tasks', 'item', 'task'], {
      id: 'task',
      list_id: 'revoked',
      title: 'Private task',
      revision: 2,
    })
    const independent = { pages: [page([event('independent')])], pageParams: [null] }
    client.setQueryData(['tasks', 'history', 'other-task', 'reader'], independent)
    evictList(client, 'revoked', denied())
    await flushPromises()
    expect(wrapper.find('[data-history-event]').exists()).toBe(false)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true)
    const cached = client.getQueryData<{ pages: unknown[] }>(['tasks', 'history', 'task', 'reader'])
    // A mounted disabled observer may recreate its empty query after eviction.
    expect(cached?.pages ?? []).toEqual([])
    expect(client.getQueryData(['tasks', 'history', 'other-task', 'reader'])).toEqual(independent)
  })

  it('does not request history from an inactive tab and clears the view with no selected task', async () => {
    const { wrapper } = screen({ active: false })
    await flushPromises()
    expect(mocks.get).not.toHaveBeenCalled()
    mocks.get.mockResolvedValueOnce(page([event('visible')]))
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(wrapper.find('[data-history-event="visible"]').exists()).toBe(true)
    await wrapper.setProps({ taskId: null })
    expect(wrapper.find('[data-history-event]').exists()).toBe(false)
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })
})
