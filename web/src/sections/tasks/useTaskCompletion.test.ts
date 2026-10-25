import { effectScope } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import type { Operation } from '@/core/operations'
import { completionRefusal, useTaskCompletion } from './useTaskCompletion'

const operation: Operation = {
  kind: 'task.complete',
  payload: { task_id: 'task' },
  expected_revisions: { task: 7 },
}
function refusal(reason = 'incomplete_checklist', revision = 7) {
  return new ApiError(
    409,
    reason,
    {
      task_id: 'task',
      revision,
      incomplete_checklist: 2,
      open_subtasks: reason === 'open_subtasks' ? 3 : 0,
      ...(reason === 'parent_closed' ? { parent_id: 'parent' } : {}),
    },
    null,
    null,
  )
}

describe('completion consent', () => {
  it('sends no override until the person confirms the exact task revision', async () => {
    const send = vi.fn().mockRejectedValueOnce(refusal()).mockResolvedValueOnce({ saved: true })
    const guard = useTaskCompletion(send)
    const write = guard.operate(operation)
    await Promise.resolve()
    expect(send).toHaveBeenCalledExactlyOnceWith(operation)
    expect(guard.pending.value?.canConfirm).toBe(true)
    guard.confirm()
    await expect(write).resolves.toEqual({ saved: true })
    expect(send).toHaveBeenLastCalledWith({
      ...operation,
      payload: { task_id: 'task', confirm_checklist_revision: 7 },
    })
    expect(guard.pending.value).toBeNull()
  })

  it('keeps the frozen changes and both result and task revisions when accepting a result', async () => {
    const send = vi.fn().mockRejectedValueOnce(refusal()).mockResolvedValueOnce({})
    const guard = useTaskCompletion(send)
    const accept: Operation = {
      kind: 'result.accept',
      payload: { result_id: 'result', complete_task: true },
      expected_revisions: { result: 2, task: 7 },
    }
    const write = guard.operate(accept)
    await Promise.resolve()
    accept.payload.complete_task = false
    accept.expected_revisions!.task = 10
    guard.confirm()
    await write
    expect(send).toHaveBeenLastCalledWith({
      kind: 'result.accept',
      payload: { result_id: 'result', complete_task: true, confirm_checklist_revision: 7 },
      expected_revisions: { result: 2, task: 7 },
    })
  })

  it('does not complete or report success when the person returns', async () => {
    const send = vi.fn().mockRejectedValue(refusal())
    const guard = useTaskCompletion(send)
    const write = guard.operate(operation)
    await Promise.resolve()
    guard.cancel()
    await expect(write).resolves.toBeNull()
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('does not change the draft being confirmed after the warning opens', async () => {
    const send = vi.fn().mockRejectedValueOnce(refusal()).mockResolvedValueOnce({})
    const guard = useTaskCompletion(send)
    const changes = { state: 'done', title: 'Original draft' }
    const write = guard.operate({
      ...operation,
      kind: 'task.update',
      payload: { task_id: 'task', changes },
    })
    await Promise.resolve()
    changes.title = 'Another draft'
    guard.confirm()
    await write
    expect(send).toHaveBeenLastCalledWith({
      kind: 'task.update',
      payload: {
        task_id: 'task',
        changes: { state: 'done', title: 'Original draft' },
        confirm_checklist_revision: 7,
      },
      expected_revisions: { task: 7 },
    })
  })

  it.each(['open_subtasks', 'parent_closed'])('cannot override %s', async (reason) => {
    const send = vi.fn().mockRejectedValue(refusal(reason))
    const guard = useTaskCompletion(send)
    const write = guard.operate(operation)
    await Promise.resolve()
    expect(guard.pending.value?.canConfirm).toBe(false)
    guard.confirm()
    expect(guard.pending.value?.reason).toBe(reason)
    guard.cancel()
    await write
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('cannot confirm an answer for another revision', async () => {
    const guard = useTaskCompletion(vi.fn().mockRejectedValue(refusal('incomplete_checklist', 8)))
    const write = guard.operate(operation)
    await Promise.resolve()
    expect(guard.pending.value?.canConfirm).toBe(false)
    guard.cancel()
    await write
  })

  it('surfaces a concurrent change after confirmation without another automatic attempt', async () => {
    const stale = new ApiError(409, 'revision_conflict', null, null, null)
    const send = vi.fn().mockRejectedValueOnce(refusal()).mockRejectedValueOnce(stale)
    const guard = useTaskCompletion(send)
    const write = guard.operate(operation)
    await Promise.resolve()
    guard.confirm()
    await expect(write).rejects.toBe(stale)
    expect(guard.pending.value).toBeNull()
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('cancels consent when the owning view is removed', async () => {
    const scope = effectScope()
    const guard = scope.run(() => useTaskCompletion(vi.fn().mockRejectedValue(refusal())))!
    const write = guard.operate(operation)
    await Promise.resolve()
    scope.stop()
    await expect(write).resolves.toBeNull()
  })

  it('does not open a late refusal after switching away from the task', async () => {
    let reject!: (error: unknown) => void
    const guard = useTaskCompletion(() => new Promise((_, refuse) => (reject = refuse)))
    const write = guard.operate(operation)
    guard.cancel()
    reject(refusal())
    await expect(write).resolves.toBeNull()
    expect(guard.pending.value).toBeNull()
  })

  it('does not interpret malformed guards or unrelated conflicts as completion consent', () => {
    expect(completionRefusal(new ApiError(409, 'incomplete_checklist', {}, null, null))).toBeNull()
    expect(completionRefusal(new ApiError(409, 'revision_conflict', {}, null, null))).toBeNull()
    expect(completionRefusal(new ApiError(422, 'open_subtasks', {}, null, null))).toBeNull()
  })
})
