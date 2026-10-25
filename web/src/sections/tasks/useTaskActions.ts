/**
 * Completing, reopening, trashing and restoring a task. Each confirmation offers the opposite
 * transition, sent with the revision the receipt returned: it fails if the task changed since.
 */
import { useQueryClient } from '@tanstack/vue-query'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { useLedger } from '@/core/operations'
import { useUndo, type ToastStep } from '@/core/undo'
import { TASKS, Task, TaskReceipt, rememberTasks, type TaskState } from './api'
import { useTaskCompletion, type TaskCompletion } from './useTaskCompletion'

export type Transition = 'task.complete' | 'task.reopen' | 'task.delete' | 'task.restore'

const OPPOSITE: Record<Transition, Transition> = {
  'task.complete': 'task.reopen',
  'task.reopen': 'task.complete',
  'task.delete': 'task.restore',
  'task.restore': 'task.delete',
}

export function useTaskActions(guard?: TaskCompletion) {
  const ledger = useLedger(TASKS)
  const undoable = useUndo()
  const queries = useQueryClient()
  const completion = guard ?? useTaskCompletion(ledger.operate)

  const send = (kind: Transition, id: string, revision: number) =>
    completion.operate({ kind, payload: { task_id: id }, expected_revisions: { [id]: revision } })

  /** The task as it is now, in place of the older copy the screen acted on. */
  async function reread(id: string) {
    try {
      const task = Task.parse(await api.get(`/v1/tasks/items/${encodeURIComponent(id)}`))
      rememberTasks(queries, [task], { source: 'read' })
    } catch {
      // The refetch after the refusal brings it all the same, a moment later.
    }
  }

  /**
   * Applies the transition; rejects with the server's refusal so the caller can say why. Refused
   * as a conflict, the screen shows the task as it is now by then, so a second try is sent with
   * its current revision.
   */
  async function transition(task: Task, kind: Transition, message: string): Promise<boolean> {
    const previousState = task.state
    const waitingReason = task.waiting_reason
    let response: unknown
    try {
      response = await send(kind, task.id, task.revision)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) await reread(task.id)
      throw error
    }
    if (response === null) return false
    const receipt = TaskReceipt.parse(response)
    const after = receipt.tasks?.find((item) => item.id === task.id)
    const undo = () =>
      kind === 'task.complete' && (previousState === 'in_progress' || previousState === 'waiting')
        ? completion.operate({
            kind: 'task.set_state',
            payload: {
              task_id: task.id,
              state: previousState,
              ...(previousState === 'waiting' ? { waiting_reason: waitingReason ?? null } : {}),
            },
            expected_revisions: { [task.id]: after!.revision },
          })
        : send(OPPOSITE[kind], task.id, after!.revision)
    undoable.confirm(message, after ? undo : undefined)
    return true
  }

  /**
   * Moves a task to another column of its board. The confirmation offers to put it back where it
   * was, with what it waited for, and `show`, when given, to go to it. Refused as a conflict, it
   * reads the task again like a transition.
   */
  async function setState(
    task: Task,
    state: TaskState,
    message: string,
    reason?: string,
    beforeId?: string | null,
    show?: ToastStep,
  ) {
    let response: unknown
    try {
      response = await completion.operate({
        kind: 'task.set_state',
        payload: {
          task_id: task.id,
          state,
          ...(beforeId !== undefined ? { before_id: beforeId } : {}),
          ...(state === 'waiting' ? { waiting_reason: reason?.trim() || null } : {}),
        },
        expected_revisions: { [task.id]: task.revision },
      })
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) await reread(task.id)
      throw error
    }
    if (response === null) return false
    const receipt = TaskReceipt.parse(response)
    undoable.confirm(
      message,
      receipt.undo ? () => completion.operate(receipt.undo!) : undefined,
      show,
    )
    return true
  }

  return { transition, setState, completion }
}
