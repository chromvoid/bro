/**
 * Where «Дела» stands, read from the address: a list or a scope, the date view, the order and a
 * project's layout. «Дела» opens where the person left off on this device, and a project keeps its
 * order and layout here.
 */
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import type { useTaskLists } from '@/sections/circle/useCircle'
import { VIEWS, type View } from './api'
import {
  lastPlace,
  projectLayout,
  projectOrder,
  rememberLayout,
  rememberOrder,
  rememberPlace,
  type TaskOrder,
} from './places'
import { taskLayoutQuery } from './taskFilters'

// The views most days need sit in the row; the others wait in a menu at its end.
const QUICK_VIEWS: View[] = ['today', 'upcoming', 'open']
export const otherViews = VIEWS.filter((name) => name !== 'all' && !QUICK_VIEWS.includes(name))

export function useTaskPlace(lists: ReturnType<typeof useTaskLists>) {
  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const listId = computed(() => (typeof route.query.list === 'string' ? route.query.list : ''))
  const currentList = computed(
    () => lists.data.value?.lists.find((item) => item.id === listId.value) ?? null,
  )
  // Until the lists arrive it is unknown whether the chosen list is a project shown as a board.
  const listsPending = computed(() => !!listId.value && lists.isPending.value)
  const isProject = computed(() => currentList.value?.list_kind === 'project')
  // A list opens with all its open tasks; «Сегодня» is the overview across every list.
  const defaultView = computed<View>(() => (listId.value ? 'open' : 'today'))
  const view = computed<View>(() =>
    VIEWS.includes(route.query.view as View) ? (route.query.view as View) : defaultView.value,
  )
  const scope = computed(() =>
    ['personal', 'assigned'].includes(String(route.query.scope))
      ? String(route.query.scope)
      : 'all',
  )
  const sort = computed(() =>
    isProject.value
      ? projectOrder(listId.value, route.query.sort)
      : ['due', 'created', ...(listId.value ? ['manual'] : [])].includes(String(route.query.sort))
        ? String(route.query.sort)
        : 'priority',
  )
  watch([listId, sort, isProject], ([list, order, project]) => {
    if (project && list) rememberOrder(list, order as TaskOrder)
  })
  // A project opens as its board, which ignores the date views; other lists have no board. A
  // project switched to its list stays a list on this device.
  const board = computed(
    () =>
      isProject.value &&
      (route.query.layout === 'board' ||
        (route.query.layout !== 'list' && projectLayout(listId.value) !== 'list')),
  )
  const viewTo = (name: View) => ({
    query: { ...route.query, view: name === defaultView.value ? undefined : name },
  })
  const quickViews = computed(() =>
    QUICK_VIEWS.map((name) => ({
      value: name,
      label: t(`tasks.quickViews.${name}`),
      to: viewTo(name),
    })),
  )
  const otherView = computed(() => (otherViews.includes(view.value) ? view.value : null))
  function showView(name: unknown) {
    if (VIEWS.includes(name as View)) void router.push(viewTo(name as View))
  }
  function setLayout(layout: 'board' | 'list') {
    rememberLayout(listId.value, layout)
    void router.replace({ query: taskLayoutQuery({ ...route.query, view: undefined }, layout) })
  }
  // «Дела» opens where the person left off on this device; a place chosen explicitly is kept.
  watch(
    [() => route.query, () => lists.data.value],
    ([current, data]) => {
      if (route.name !== 'tasks' || Object.keys(current).length || !data) return
      const place = lastPlace()
      if (place?.list && data.lists.some((item) => item.id === place.list))
        void router.replace({ query: { list: place.list } })
      else if (place?.scope) void router.replace({ query: { scope: place.scope } })
    },
    { immediate: true },
  )
  watch(
    [listId, scope],
    ([list, chosen]) => {
      // An address without a place is about to be sent to the last one: it chooses nothing.
      if (route.name !== 'tasks' || !Object.keys(route.query).length) return
      rememberPlace(list ? { list } : chosen !== 'all' ? { scope: chosen } : {})
    },
    { immediate: true },
  )
  const order = computed({
    get: () => sort.value,
    set: (value) => void router.replace({ query: { ...route.query, sort: value } }),
  })
  const sorts = computed(() => ['priority', 'due', 'created', ...(listId.value ? ['manual'] : [])])

  return {
    listId,
    currentList,
    listsPending,
    isProject,
    view,
    scope,
    sort,
    /** A project shown as its board. */
    board,
    viewTo,
    quickViews,
    otherView,
    showView,
    setLayout,
    /** The order, as the list's menu sets it. */
    order,
    sorts,
  }
}
