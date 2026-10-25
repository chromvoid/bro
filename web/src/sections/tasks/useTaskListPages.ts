/**
 * The tasks of a list view, page by page, with what the filters' summary and an empty view say
 * about them and what each row adds under its title. A board reads its own pages
 * (useTaskBoardPages) and reports its count here (`boardResults`).
 */
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { dayIn, dayLabel } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import type { useTaskLists } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import { TaskPage, stateOf, taskQuery, type Task } from './api'
import { taskDates, type DateTone } from './dates'
import type { useTaskFilters } from './useTaskFilters'
import type { useTaskPlace } from './useTaskPlace'

export const TONES: Record<DateTone, string> = {
  danger: 'text-destructive',
  warning: 'text-warning',
  muted: '',
}

export function useTaskListPages(
  place: ReturnType<typeof useTaskPlace>,
  filtering: ReturnType<typeof useTaskFilters>,
  lists: ReturnType<typeof useTaskLists>,
) {
  const { t } = useI18n()
  const session = useSession()
  const queries = useQueryClient()
  const { listId, listsPending, view, scope, sort, board } = place
  const filters = filtering.filters
  const boardResults = ref<{ total: number | null; pending: boolean }>({
    total: null,
    pending: true,
  })
  watch([listId, filtering.queryKey], () => {
    boardResults.value = { total: null, pending: true }
  })
  const list = useInfiniteQuery({
    enabled: computed(() => !board.value && !listsPending.value),
    queryKey: computed(() => [
      'tasks',
      'items',
      view.value,
      scope.value,
      listId.value,
      sort.value,
      ...filtering.queryKey.value,
    ]),
    initialPageParam: null as string | null,
    queryFn: async ({ pageParam, signal }) => {
      const page = TaskPage.parse(
        await api.get(
          `/v1/tasks/items?${taskQuery({ view: view.value, scope: scope.value, listId: listId.value, sort: sort.value, filters: filters.value, cursor: pageParam })}`,
          signal,
        ),
      )
      return { ...page, readAt: Date.now() }
    },
    getNextPageParam: (last) => last.next_cursor,
  })
  const tasks = computed(() => list.data.value?.pages.flatMap((page) => page.items) ?? [])
  const resultTotal = computed(() =>
    board.value ? boardResults.value.total : (list.data.value?.pages[0]?.total ?? null),
  )
  const resultsPending = computed(() =>
    board.value ? boardResults.value.pending : list.isFetching.value,
  )
  // A date view that came out empty says whether other open tasks exist instead of looking empty.
  const narrowed = computed(
    () => !filtering.narrowed.value && ['today', 'upcoming'].includes(view.value),
  )
  // The lists count their open tasks, so no extra read is needed to say that some exist elsewhere.
  const hidden = computed(() => {
    if (!narrowed.value) return false
    const all = lists.data.value?.lists ?? []
    const counted = listId.value
      ? all.filter((item) => item.id === listId.value)
      : scope.value === 'personal'
        ? all.filter((item) => item.personal_user_id)
        : scope.value === 'all'
          ? all.filter((item) => item.project_state !== 'archived')
          : []
    return counted.some(
      (item) =>
        (item.counts?.todo ?? 0) + (item.counts?.in_progress ?? 0) + (item.counts?.waiting ?? 0) >
        0,
    )
  })
  const today = computed(
    () => list.data.value?.pages[0]?.today ?? dayIn(session.me?.timezone ?? 'UTC'),
  )
  async function more() {
    const result = await list.fetchNextPage()
    if (!result.isError) return
    if (result.error instanceof ApiError && result.error.status === 409) {
      toast(t('tasks.stale'))
      await queries.resetQueries({ queryKey: ['tasks', 'items'] })
    } else toast.error(errorText(result.error, t))
  }
  // What the row adds under the title. «К выполнению» goes without saying, the list only where
  // the view mixes lists (one's own by its chip name), and only a date takes a colour.
  function meta(task: Task): Array<{ text: string; tone?: DateTone }> {
    const parts: Array<{ text: string; tone?: DateTone }> = []
    const state = stateOf(task)
    if (state === 'in_progress' || state === 'waiting')
      parts.push({ text: t(`tasks.states.${state}`) })
    const list = lists.data.value?.lists.find((item) => item.id === task.list_id)
    if (list && !listId.value && scope.value !== 'personal')
      parts.push({ text: list.personal_user_id ? t('tasks.places.personal') : list.name })
    if (task.parent_id)
      parts.push({
        text: task.parent ? t('tasks.parent', { title: task.parent.title }) : t('tasks.subtask'),
      })
    if (task.priority === 1) parts.push({ text: t('tasks.priorities.1') })
    for (const date of taskDates(task, today.value))
      parts.push({
        text: t(date.label, { date: dayLabel(date.day, today.value) }),
        tone: date.tone,
      })
    return parts
  }

  return {
    /** The count a board reported, until the list or the filters change. */
    boardResults,
    list,
    tasks,
    resultTotal,
    resultsPending,
    /** Open tasks exist that the date view leaves out. */
    hidden,
    today,
    more,
    meta,
  }
}
