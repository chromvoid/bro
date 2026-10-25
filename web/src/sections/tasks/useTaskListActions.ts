/**
 * What the list of «Дела» does by itself: a new task typed at its top, a row completed or
 * reopened, and a row moved up in a list's own order.
 */
import { ref, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { errorText } from '@/core/errors'
import { useQueryIntent } from '@/core/intent'
import type { useLedger } from '@/core/operations'
import type { useTaskLists } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import type { Task } from './api'
import { useTaskActions } from './useTaskActions'
import type { useTaskFilters } from './useTaskFilters'
import type { useTaskPlace } from './useTaskPlace'

export interface ListActionsOptions {
  /** The field at the top of the list that takes a new task. */
  quickAddField: Readonly<Ref<{ $el: HTMLInputElement } | null>>
  ledger: ReturnType<typeof useLedger>
  place: ReturnType<typeof useTaskPlace>
  filtering: ReturnType<typeof useTaskFilters>
  lists: ReturnType<typeof useTaskLists>
  today: Readonly<Ref<string>>
}

export function useTaskListActions({
  quickAddField,
  ledger,
  place,
  filtering,
  lists,
  today,
}: ListActionsOptions) {
  const { t } = useI18n()
  const session = useSession()
  const { listId, view, board } = place
  const title = ref('')
  // «Добавить задачу» on Today lands here ready to type.
  // The field shows once the lists have loaded, so the focus waits for it.
  const typing = ref(false)
  useQueryIntent('add', () => (typing.value = true))
  watch([typing, quickAddField], ([wanted, field]) => {
    if (!wanted || !field) return
    field.$el.focus()
    typing.value = false
  })
  const adding = ref(false)
  async function add() {
    const text = title.value.trim()
    if (!text || adding.value) return
    adding.value = true
    try {
      const payload: Record<string, unknown> = { title: text }
      if (view.value === 'today' && !board.value) payload.planned_on = today.value
      await ledger.operate({
        kind: 'task.create',
        payload,
        ...(listId.value ? { target_resource_id: listId.value } : {}),
      })
      title.value = ''
      toast.success(t('tasks.added'))
    } catch (error) {
      toast.error(errorText(error, t))
    } finally {
      adding.value = false
    }
  }
  const actions = useTaskActions()
  watch(listId, actions.completion.cancel)
  const writable = (task: Task) =>
    session.can('tasks.write') &&
    ['edit', 'manage'].includes(
      lists.data.value?.lists.find((item) => item.id === task.list_id)?.effective ?? '',
    )
  async function toggle(task: Task) {
    try {
      if (task.status === 'done') await actions.transition(task, 'task.reopen', t('tasks.reopened'))
      else await actions.transition(task, 'task.complete', t('tasks.completed'))
    } catch (error) {
      toast.error(errorText(error, t))
    }
  }
  async function moveUp(task: Task, before: Task) {
    if (filtering.narrowed.value) return
    try {
      await ledger.operate({
        kind: 'task.reorder',
        payload: { task_id: task.id, before_id: before.id },
        expected_revisions: { [task.id]: task.revision },
      })
    } catch (error) {
      toast.error(errorText(error, t))
    }
  }

  return {
    /** The new task's title as typed. */
    title,
    adding,
    add,
    /** Completion asks first when the task still has open steps (TaskCompletionDialog). */
    actions,
    writable,
    toggle,
    moveUp,
  }
}
