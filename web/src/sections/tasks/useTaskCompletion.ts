/** A refused completion can only be confirmed for the exact operation and task revision shown. */
import { getCurrentScope, onScopeDispose, shallowRef } from 'vue'
import { ApiError } from '@/core/http'
import type { Operation } from '@/core/operations'
import { z } from '@/core/zod'

const Detail = z.object({
  task_id: z.string(),
  revision: z.number().int().positive(),
  open_subtasks: z.number().int().nonnegative(),
  incomplete_checklist: z.number().int().nonnegative(),
  parent_id: z.string().nullish(),
})
const Reason = z.enum(['open_subtasks', 'incomplete_checklist', 'parent_closed'])
export interface CompletionRefusal {
  reason: z.infer<typeof Reason>
  detail: z.infer<typeof Detail>
  canConfirm: boolean
}

export function completionRefusal(error: unknown): Omit<CompletionRefusal, 'canConfirm'> | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null
  const reason = Reason.safeParse(error.code)
  const detail = Detail.safeParse(error.detail)
  return reason.success && detail.success ? { reason: reason.data, detail: detail.data } : null
}

export function useTaskCompletion(send: (operation: Operation) => Promise<unknown>) {
  const pending = shallowRef<CompletionRefusal | null>(null)
  let settle: ((confirmed: boolean) => void) | undefined
  let generation = 0

  function answer(confirmed: boolean) {
    const resolve = settle
    settle = undefined
    pending.value = null
    resolve?.(confirmed)
  }
  const cancel = () => {
    generation++
    answer(false)
  }
  const confirm = () => {
    if (pending.value?.canConfirm) answer(true)
  }
  if (getCurrentScope()) onScopeDispose(cancel)

  /** null means the user returned to their task; it is neither a success nor an error. */
  async function operate(operation: Operation): Promise<unknown> {
    const started = generation
    // Snapshot nested changes too: editing a form while the prompt is open cannot change consent.
    const original = JSON.parse(JSON.stringify(operation)) as Operation
    try {
      return await send(original)
    } catch (error) {
      const refusal = completionRefusal(error)
      if (!refusal) throw error
      if (started !== generation) return null
      if (pending.value) throw error
      const { detail } = refusal
      const changes = original.payload.changes as Record<string, unknown> | undefined
      const completing =
        original.kind === 'task.complete' ||
        original.kind === 'task.undo' ||
        (original.kind === 'task.set_state' && original.payload.state === 'done') ||
        (original.kind === 'task.update' &&
          (changes?.state === 'done' || changes?.status === 'done')) ||
        (original.kind === 'result.accept' && original.payload.complete_task === true)
      const canConfirm =
        refusal.reason === 'incomplete_checklist' &&
        completing &&
        detail.open_subtasks === 0 &&
        detail.incomplete_checklist > 0 &&
        original.expected_revisions?.[detail.task_id] === detail.revision &&
        (original.payload.task_id === detail.task_id || original.kind === 'result.accept')
      const confirmed = await new Promise<boolean>((resolve) => {
        settle = resolve
        pending.value = { ...refusal, canConfirm }
      })
      if (!confirmed) return null
      // A concurrent edit still refuses this request. Never update the revision or retry consent.
      return send({
        ...original,
        payload: { ...original.payload, confirm_checklist_revision: detail.revision },
      })
    }
  }

  return { pending, operate, cancel, confirm }
}

export type TaskCompletion = ReturnType<typeof useTaskCompletion>
