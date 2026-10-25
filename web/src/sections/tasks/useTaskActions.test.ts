import { QueryClient } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { Task } from './api'
import { useTaskActions } from './useTaskActions'

const mocks = vi.hoisted(() => ({
  operate: vi.fn(),
  confirm: vi.fn(),
  get: vi.fn(),
  queries: null as unknown,
}))
vi.mock('@/core/operations', () => ({ useLedger: () => ({ operate: mocks.operate }) }))
vi.mock('@/core/undo', () => ({ useUndo: () => ({ confirm: mocks.confirm }) }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@tanstack/vue-query', async (original) => ({
  ...(await original<typeof import('@tanstack/vue-query')>()),
  useQueryClient: () => mocks.queries,
}))

beforeEach(() => {
  vi.resetAllMocks()
  mocks.queries = new QueryClient()
})

describe('task workflow undo', () => {
  it.each(['in_progress', 'waiting'] as const)(
    'restores %s with the completed receipt revision',
    async (state) => {
      const task = Task.parse({
        id: 'task',
        title: 'Research',
        status: 'open',
        state,
        waiting_reason: state === 'waiting' ? 'Reply from host' : null,
        revision: 3,
      })
      mocks.operate.mockResolvedValue({
        operation_id: 'operation',
        kind: 'task.complete',
        tasks: [{ ...task, status: 'done', state: 'done', waiting_reason: null, revision: 4 }],
      })
      await useTaskActions().transition(task, 'task.complete', 'Completed')
      const undo = mocks.confirm.mock.calls[0]![1] as () => Promise<unknown>
      await undo()
      expect(mocks.operate).toHaveBeenLastCalledWith({
        kind: 'task.set_state',
        payload: {
          task_id: 'task',
          state,
          ...(state === 'waiting' ? { waiting_reason: 'Reply from host' } : {}),
        },
        expected_revisions: { task: 4 },
      })
    },
  )
})

describe('a transition refused as a conflict', () => {
  const task = Task.parse({ id: 'task', title: 'Полить цветы', status: 'open', revision: 1 })

  it('shows the task as it is now before the caller says why', async () => {
    const queries = mocks.queries as QueryClient
    queries.setQueryData(['tasks', 'item', 'task'], task)
    const conflict = new ApiError(409, null, null, null, null, 'Задача уже изменена')
    mocks.operate.mockRejectedValue(conflict)
    mocks.get.mockResolvedValue({ ...task, revision: 3 })
    await expect(useTaskActions().transition(task, 'task.delete', 'Trashed')).rejects.toBe(conflict)
    expect(mocks.get).toHaveBeenCalledWith('/v1/tasks/items/task')
    expect(queries.getQueryData<Task>(['tasks', 'item', 'task'])?.revision).toBe(3)
    expect(mocks.confirm).not.toHaveBeenCalled()
  })
  it('still says why when the task cannot be read again', async () => {
    const conflict = new ApiError(409, null, null, null, null, 'Задача уже изменена')
    mocks.operate.mockRejectedValue(conflict)
    mocks.get.mockRejectedValue(new ApiError(404, null, null, null, null))
    await expect(useTaskActions().transition(task, 'task.delete', 'Trashed')).rejects.toBe(conflict)
  })
  it('does not read again for other refusals', async () => {
    const invalid = new ApiError(422, null, null, null, null)
    mocks.operate.mockRejectedValue(invalid)
    await expect(useTaskActions().transition(task, 'task.delete', 'Trashed')).rejects.toBe(invalid)
    expect(mocks.get).not.toHaveBeenCalled()
  })
})

describe('moving a task on the board', () => {
  it.each(['complete', 'set_state'] as const)(
    'does not show a success or offer undo when a guarded %s is cancelled',
    async (kind) => {
      const task = Task.parse({ id: 'task', title: 'Review', status: 'open', revision: 5 })
      mocks.operate.mockRejectedValue(
        new ApiError(
          409,
          'incomplete_checklist',
          { task_id: 'task', revision: 5, open_subtasks: 0, incomplete_checklist: 2 },
          null,
          null,
        ),
      )
      const actions = useTaskActions()
      const write =
        kind === 'complete'
          ? actions.transition(task, 'task.complete', 'Completed')
          : actions.setState(task, 'done', 'Moved')
      await Promise.resolve()
      actions.completion.cancel()
      await expect(write).resolves.toBe(false)
      expect(mocks.confirm).not.toHaveBeenCalled()
      expect(mocks.get).not.toHaveBeenCalled()
    },
  )

  it('sends the reason with «Ожидание» and puts the task back where it was', async () => {
    const task = Task.parse({
      id: 'task',
      title: 'Order the sink',
      status: 'open',
      state: 'in_progress',
      revision: 5,
    })
    mocks.operate.mockResolvedValue({
      operation_id: 'operation',
      kind: 'task.set_state',
      tasks: [{ ...task, state: 'waiting', waiting_reason: 'An invoice', revision: 6 }],
      undo: {
        kind: 'task.undo',
        payload: { task_id: 'task', operation_id: 'operation' },
        expected_revisions: { task: 6 },
      },
    })
    await useTaskActions().setState(task, 'waiting', 'Moved', '  An invoice ')
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.set_state',
      payload: { task_id: 'task', state: 'waiting', waiting_reason: 'An invoice' },
      expected_revisions: { task: 5 },
    })
    const undo = mocks.confirm.mock.calls[0]![1] as () => Promise<unknown>
    await undo()
    expect(mocks.operate).toHaveBeenLastCalledWith({
      kind: 'task.undo',
      payload: { task_id: 'task', operation_id: 'operation' },
      expected_revisions: { task: 6 },
    })
  })
})

describe('the move confirmation', () => {
  it('passes a way to the moved task beside the undo', async () => {
    const task = Task.parse({ id: 'task', title: 'Read', status: 'open', revision: 2 })
    mocks.operate.mockResolvedValue({ operation_id: 'move', kind: 'task.set_state', tasks: [] })
    const show = { label: 'Показать', run: vi.fn() }
    await useTaskActions().setState(task, 'in_progress', 'Moved', undefined, undefined, show)
    expect(mocks.confirm.mock.calls[0]![2]).toBe(show)
  })
})

describe('atomic queue movement', () => {
  const task = Task.parse({ id: 'task', title: 'Read', status: 'open', state: 'todo', revision: 2 })
  it.each([null, 'neighbor'])(
    'sends explicit position %s with the state in one operation',
    async (beforeId) => {
      mocks.operate.mockResolvedValue({ operation_id: 'move', kind: 'task.set_state', tasks: [] })
      await useTaskActions().setState(task, 'in_progress', 'Moved', undefined, beforeId)
      expect(mocks.operate).toHaveBeenCalledWith({
        kind: 'task.set_state',
        payload: { task_id: 'task', state: 'in_progress', before_id: beforeId },
        expected_revisions: { task: 2 },
      })
    },
  )

  it('requires checklist consent when the receipt undo restores a completed task', async () => {
    const undo = {
      kind: 'task.undo',
      payload: { task_id: 'task', operation_id: 'move' },
      expected_revisions: { task: 3 },
    }
    mocks.operate.mockResolvedValueOnce({
      operation_id: 'move',
      kind: 'task.set_state',
      tasks: [{ ...task, revision: 3 }],
      undo,
    })
    const actions = useTaskActions()
    await actions.setState(task, 'todo', 'Moved')
    mocks.operate.mockRejectedValueOnce(
      new ApiError(
        409,
        'incomplete_checklist',
        { task_id: 'task', revision: 3, open_subtasks: 0, incomplete_checklist: 1 },
        null,
        null,
      ),
    )
    const revert = (mocks.confirm.mock.calls[0]![1] as () => Promise<unknown>)()
    await Promise.resolve()
    expect(actions.completion.pending.value?.canConfirm).toBe(true)
    mocks.operate.mockResolvedValueOnce({ saved: true })
    actions.completion.confirm()
    await revert
    expect(mocks.operate).toHaveBeenLastCalledWith({
      ...undo,
      payload: { ...undo.payload, confirm_checklist_revision: 3 },
    })
  })
})
