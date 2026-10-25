<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { useElementBounding, useMediaQuery, useWindowScroll } from '@vueuse/core'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { VueDraggable, type DraggableEvent } from 'vue-draggable-plus'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { errorText } from '@/core/errors'
import { useRoomFor } from '@/core/layout'
import { cn } from '@/lib/utils'
import { useMembers } from '@/sections/circle/useCircle'
import { STATES, stateOf, type Task, type TaskState } from './api'
import ColumnAdd from './ColumnAdd.vue'
import { STATE_DOTS } from './stateDots'
import TaskCard from './TaskCard.vue'
import TaskCompletionDialog from './TaskCompletionDialog.vue'
import TaskMoveSheet from './TaskMoveSheet.vue'
import { useBoardColumns } from './useBoardColumns'
import { useTaskActions } from './useTaskActions'
import { useTaskBoardPages } from './useTaskBoardPages'
import { dropTarget, queueTarget, type QueueCommand } from './queue'
import { hasTaskNarrowing, readTaskFilters } from './taskFilters'

/**
 * A project's board: four columns from one server snapshot. Each can load more; refreshes
 * replace all loaded pages together and preserve the reader's visible task.
 *
 * A phone shows one column at a time, swiped sideways, with all four states as tabs above it; a
 * card moves through the move sheet from its «⋯» or a long press. Wide screens show the columns
 * side by side, move from the card's menu and also take a card dragged to another column;
 * Shift + ←/→ moves the focused card.
 */
const props = defineProps<{
  listId: string
  sort: string
  q: string
  today: string
  spaceId: string | null
  canWrite: boolean
  filters?: Record<string, string>
  /** The project's progress: on a phone it stands under «Готово» in place of a row of its own. */
  progress?: { done: number; total: number } | null
}>()
const emit = defineEmits<{
  select: [task: Task]
  discussion: [task: Task]
  results: [value: { total: number | null; pending: boolean }]
}>()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const actions = useTaskActions()
const wide = useMediaQuery('(min-width: 768px)')
const members = useMembers(computed(() => props.spaceId))
const names = computed(
  () => new Map((members.data.value?.members ?? []).map((member) => [member.user_id, member.name])),
)

const scroller = ref<HTMLElement | null>(null)
// Docking the task panel can narrow the board even on a wide screen. Four columns need at
// least 16.5rem each and three gaps; otherwise retain scrolling and the state tabs.
const allColumns = useRoomFor(scroller, 68.25)
const board = useTaskBoardPages(
  () => ({ listId: props.listId, sort: props.sort, q: props.q, filters: props.filters }),
  scroller,
)
const criteria = computed(() =>
  JSON.stringify([props.listId, props.sort, props.q, Object.entries(props.filters ?? {}).sort()]),
)
const filters = computed(() => readTaskFilters({ ...props.filters, q: props.q }))
watch(
  [() => board.data.value?.total, board.isFetching],
  ([total, pending]) => emit('results', { total: total ?? null, pending }),
  { immediate: true },
)
const queueMode = computed(() =>
  hasTaskNarrowing(filters.value) ? 'filtered' : props.sort === 'manual' ? 'manual' : 'sorted',
)
const columns = STATES.map((state) => ({
  state,
  tasks: computed(() => board.data.value?.columns[state].items ?? []),
}))
// Neighbour navigation reads the coherent server snapshot, never Sortable's provisional order.
const loadedColumns = computed(() =>
  columns.map((column) => ({ state: column.state, tasks: column.tasks.value })),
)
const readAtByTask = computed<Record<string, number>>(() => board.data.value?.readAtByTask ?? {})
defineExpose({ loadedColumns, readAtByTask })
const hasMore = (state: TaskState) => !!board.data.value?.columns[state].next_cursor
const title = (state: TaskState) =>
  state === 'done' && filters.value.completed_scope === 'recent'
    ? t('tasks.board.doneWeek')
    : t(`tasks.states.${state}`)
const count = (item: (typeof columns)[number]) => board.data.value?.columns[item.state].total ?? '…'
const counts = computed(() => {
  const entries = columns.map((item) => [item.state, count(item)] as const)
  return Object.fromEntries(entries) as Record<TaskState, number | string>
})

// Sortable's provisional copy is never appended to the coherent server snapshot.
const shown = reactive({} as Record<TaskState, Task[]>)
const dragging = ref(false)
const moving = ref(false)
function reset() {
  for (const item of columns) shown[item.state] = [...item.tasks.value]
}
watch(
  () => columns.map((item) => item.tasks.value),
  () => {
    if (!dragging.value || !board.data.value) reset()
  },
  { immediate: true },
)

async function more(state: TaskState) {
  await board.more(state)
  if (board.moreError[state]) toast.error(errorText(board.moreError[state], t))
}
function manual() {
  void router.replace({ query: { ...route.query, sort: 'manual' } })
}
async function reorder(task: Task, command: QueueCommand) {
  if (
    task.list_id !== props.listId ||
    moving.value ||
    board.loadingMore[stateOf(task)] ||
    queueMode.value !== 'manual' ||
    stateOf(task) === 'done'
  )
    return
  const state = stateOf(task)
  const started = criteria.value
  let target = queueTarget(shown[state], task, command, hasMore(state))
  while (target && 'more' in target) {
    await more(state)
    if (criteria.value !== started || queueMode.value !== 'manual' || board.moreError[state]) return
    target = queueTarget(
      board.data.value?.columns[state].items ?? [],
      task,
      command,
      hasMore(state),
    )
  }
  if (target && 'beforeId' in target) await move(task, state, undefined, target)
}

// The moved card is tinted for a moment where it landed.
const flashed = ref<string | null>(null)
let fade: ReturnType<typeof setTimeout> | undefined
function flash(id: string) {
  flashed.value = id
  clearTimeout(fade)
  fade = setTimeout(() => (flashed.value = null), 900)
}

// Waiting asks what for before the task moves.
const asking = ref<Task | null>(null)
const reason = ref('')
let waitingBefore: string | null | undefined
const askOpen = computed({
  get: () => !!asking.value,
  set: (value) => {
    if (!value) {
      asking.value = null
      reset()
    }
  },
})
async function move(
  task: Task,
  state: TaskState,
  why?: string,
  placement?: { beforeId: string | null | undefined },
) {
  if (moving.value || task.list_id !== props.listId || !board.data.value) return
  const beforeId =
    state === 'done' || queueMode.value !== 'manual'
      ? undefined
      : placement
        ? placement.beforeId
        : stateOf(task) !== state
          ? null
          : undefined
  if (state === 'waiting' && stateOf(task) !== 'waiting' && why === undefined) {
    reason.value = task.waiting_reason ?? ''
    asking.value = task
    waitingBefore = beforeId
    reset()
    return
  }
  moving.value = true
  const started = criteria.value
  try {
    // A card that went to a column out of sight can be followed from the confirmation.
    const show =
      stateOf(task) !== state && !allColumns.value
        ? { label: t('tasks.board.show'), run: () => void reveal(task.id, state) }
        : undefined
    const moved = await actions.setState(
      task,
      state,
      stateOf(task) === state
        ? t('tasks.queue.moved')
        : t('tasks.board.moved', { state: t(`tasks.states.${state}`) }),
      why ?? task.waiting_reason ?? undefined,
      beforeId,
      show,
    )
    if (!moved) {
      reset()
      return
    }
    if (criteria.value !== started) return
    await board.refetch()
    if (criteria.value !== started) return
    flash(task.id)
    await nextTick()
    document.querySelector<HTMLElement>(`[data-task="${task.id}"]`)?.focus({ preventScroll: true })
  } catch (error) {
    reset()
    toast.error(errorText(error, t))
  } finally {
    moving.value = false
  }
}
async function answer() {
  const task = asking.value
  if (!task) return
  asking.value = null
  await move(task, 'waiting', reason.value, { beforeId: waitingBefore })
}
watch(
  criteria,
  () => {
    actions.completion.cancel()
    asking.value = null
    waitingBefore = undefined
    reason.value = ''
    dragging.value = false
    reset()
  },
  { flush: 'sync' },
)

function dropped(event: DraggableEvent<Task>, state: TaskState) {
  const task = event.data
  if (!board.data.value || task.list_id !== props.listId) {
    reset()
    return
  }
  const positional = queueMode.value === 'manual' && state !== 'done'
  const beforeId = positional
    ? dropTarget(shown[state], task, event.newIndex ?? 0, hasMore(state))
    : undefined
  if (positional && beforeId === undefined) {
    toast(t('tasks.queue.partialTail'))
    if (stateOf(task) === state) {
      reset()
      return
    }
  }
  void move(task, state, undefined, { beforeId })
}
function ended() {
  dragging.value = false
  reset()
}

// On phones and medium screens the tabs follow the horizontal board.
const { top: boardTop } = useElementBounding(scroller)
const { current, jump, scrolled } = useBoardColumns(scroller, allColumns, wide)
function selectTask(task: Task) {
  current.value = STATES.indexOf(stateOf(task))
  emit('select', task)
}
/** Shows a card in its column and tints it, as after a move. */
async function reveal(id: string, state: TaskState) {
  jump(STATES.indexOf(state))
  await nextTick()
  const card = document.querySelector<HTMLElement>(`[data-task="${id}"]`)
  card?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  card?.focus({ preventScroll: true })
  flash(id)
}
// The move sheet of a phone, for the card whose «⋯» or long press asked for it.
const acting = ref<Task | null>(null)
function openDiscussion(task: Task) {
  current.value = STATES.indexOf(stateOf(task))
  emit('discussion', task)
}
const adding = ref<TaskState | null>(null)
const addOpen = computed({
  get: () => adding.value !== null,
  set: (value) => {
    if (!value) adding.value = null
  },
})
const currentState = computed(() => STATES[current.value] ?? 'todo')
const progressShown = computed(() => !wide.value && !!props.progress?.total)
const floatingAdd = computed(
  () => props.canWrite && (wide.value ? boardTop.value < 0 : currentState.value !== 'done'),
)
// On a phone the add button names its column and folds to «+» while the page scrolls down.
const addLabel = computed(() =>
  currentState.value === 'todo'
    ? t('tasks.board.add')
    : t('tasks.board.addIn', { state: t(`tasks.states.${currentState.value}`) }),
)
const { y: pageY } = useWindowScroll()
const folded = ref(false)
watch(pageY, (now, before) => {
  if (now < 24) folded.value = false
  else if (now > before + 4) folded.value = true
  else if (now < before - 4) folded.value = false
})
function openCreated(task: Task) {
  adding.value = null
  selectTask(task)
}
function reordered() {
  void router.replace({ query: { ...route.query, sort: 'manual' } })
}
watch(
  () => props.listId,
  () => {
    current.value = 0
    adding.value = null
    scroller.value?.scrollTo({ left: 0 })
  },
)
onBeforeUnmount(() => clearTimeout(fade))
</script>

<template>
  <RefreshFailed :query="board" />
  <p v-if="queueMode === 'filtered'" class="text-muted-foreground text-sm" role="status">
    {{ t('tasks.queue.filtered') }}
  </p>
  <ErrorState
    v-if="board.isLoadingError.value"
    :error="board.error.value"
    @retry="board.refetch()"
  />
  <!-- Four equal tabs, all in sight on a 360 px phone: a short name over the dot and count. -->
  <nav
    v-if="!allColumns"
    class="bg-background sticky top-(--app-top) z-10 -mx-4 grid grid-cols-4 border-b px-4 md:mx-0 md:px-0"
    :aria-label="t('tasks.board.columns')"
  >
    <button
      v-for="(item, index) in columns"
      :key="item.state"
      type="button"
      :aria-current="current === index ? 'true' : undefined"
      :title="title(item.state)"
      class="text-muted-foreground aria-[current=true]:border-foreground aria-[current=true]:text-foreground flex min-h-12 min-w-0 flex-col items-center justify-center gap-0.5 border-b-2 border-transparent px-1 py-1 text-sm font-medium"
      @click="jump(index)"
    >
      <span class="max-w-full truncate">{{ t(`tasks.board.short.${item.state}`) }}</span>
      <span class="flex items-center gap-1.5 text-xs tabular-nums">
        <span
          :class="['size-2 shrink-0 rounded-full', STATE_DOTS[item.state]]"
          aria-hidden="true"
        />
        <template v-if="item.state === 'done' && progressShown && progress">
          <span aria-hidden="true">{{ progress.done }}/{{ progress.total }}</span>
          <span class="sr-only">{{ t('tasks.projectProgress', progress) }}</span>
        </template>
        <template v-else>{{ count(item) }}</template>
      </span>
    </button>
  </nav>
  <div
    ref="scroller"
    class="-mx-4 flex snap-x snap-mandatory scroll-px-4 items-start gap-3 overflow-x-auto scroll-auto px-4 pb-2 [scrollbar-width:none] md:mx-0 md:scroll-px-0 md:px-0 [&::-webkit-scrollbar]:hidden"
    data-testid="task-board"
    :aria-label="t('tasks.board.label')"
    role="region"
    @scroll.passive="scrolled"
    @scrollend="scrolled"
  >
    <!-- On a phone the cards are the column: no panel around them, their text gets the width. -->
    <section
      v-for="item in columns"
      :key="item.state"
      :class="[
        'md:bg-muted/40 relative flex shrink-0 snap-start flex-col gap-2 md:rounded-2xl md:p-3',
        allColumns ? 'w-[calc((100%-2.25rem)/4)]' : 'w-[85%] md:w-[19rem]',
      ]"
      :aria-labelledby="`board-${item.state}`"
      :data-state="item.state"
    >
      <!-- On a phone the jumps above already name the column. -->
      <div v-if="wide" class="flex items-center gap-2">
        <h2
          :id="`board-${item.state}`"
          class="flex min-w-0 flex-1 items-center gap-2 px-1 text-sm font-medium"
        >
          <span
            :class="['size-2 shrink-0 rounded-full', STATE_DOTS[item.state]]"
            aria-hidden="true"
          />
          {{ title(item.state) }}
          <span class="text-muted-foreground ms-auto tabular-nums">{{ count(item) }}</span>
        </h2>
        <Button
          v-if="canWrite && item.state !== 'done'"
          variant="ghost"
          size="icon-sm"
          :aria-label="t('tasks.board.addIn', { state: t(`tasks.states.${item.state}`) })"
          @click="adding = item.state"
          ><Plus class="size-4"
        /></Button>
      </div>
      <h2 v-else :id="`board-${item.state}`" class="sr-only">{{ title(item.state) }}</h2>
      <LoadingState v-if="board.isPending.value" :rows="2" />
      <template v-else-if="board.data.value">
        <!-- An empty column stays a place to drop a card; its note lets the drop through. -->
        <div class="relative">
          <VueDraggable
            v-model="shown[item.state]"
            :group="{ name: `board-${listId}`, pull: canWrite, put: canWrite }"
            :sort="queueMode === 'manual' && item.state !== 'done'"
            :disabled="!canWrite || !wide || moving"
            :animation="150"
            filter="a, [data-task-discussion]"
            :prevent-on-filter="false"
            ghost-class="opacity-40"
            class="flex min-h-12 flex-col gap-2"
            :data-drop="item.state"
            @start="dragging = true"
            @end="ended"
            @add="dropped($event, item.state)"
            @update="dropped($event, item.state)"
          >
            <TaskCard
              v-for="task in shown[item.state]"
              :key="task.id"
              :task="task"
              :today="today"
              :assignee="task.assigned_to ? (names.get(task.assigned_to) ?? null) : null"
              :can-move="canWrite"
              :sheet="!wide"
              :column="item.state"
              :flash="flashed === task.id"
              :queue-mode="queueMode"
              @select="selectTask"
              @discussion="openDiscussion"
              @move="move"
              @reorder="reorder"
              @manual="manual"
              @actions="acting = $event"
            />
          </VueDraggable>
          <p
            v-if="!shown[item.state]?.length"
            class="text-muted-foreground pointer-events-none absolute inset-0 grid place-items-center rounded-xl border border-dashed p-3 text-center text-sm"
          >
            {{
              hasTaskNarrowing(filters)
                ? t('tasks.filters.noneInColumn')
                : item.state === 'done'
                  ? t(
                      filters.completed_scope === 'all'
                        ? 'tasks.empty.done'
                        : 'tasks.board.noneDone',
                    )
                  : t('tasks.board.empty')
            }}
          </p>
        </div>
        <Button
          v-if="hasMore(item.state)"
          variant="ghost"
          size="sm"
          :disabled="board.loadingMore[item.state] || board.isFetching.value"
          @click="more(item.state)"
          >{{ t('tasks.more') }}</Button
        >
        <RouterLink
          v-if="item.state === 'done' && filters.completed_scope === 'recent'"
          :to="{
            query: {
              ...route.query,
              view: 'done',
              layout: 'list',
              completed_scope: 'all',
              task: undefined,
            },
          }"
          class="text-muted-foreground hover:text-foreground px-1 text-sm underline-offset-4 hover:underline"
          >{{ t('tasks.board.allDone') }}</RouterLink
        >
      </template>
    </section>
  </div>
  <div
    v-if="floatingAdd"
    class="pointer-events-none fixed inset-x-0 bottom-[calc(var(--app-bottom)+0.75rem)] z-20 flex justify-end px-4 md:sticky md:inset-x-auto md:-mt-11 md:px-0"
    data-testid="board-floating-add"
  >
    <Button
      v-if="!wide"
      :class="
        cn('pointer-events-auto min-h-11 rounded-full shadow-lg', folded ? 'size-14 px-0' : 'px-5')
      "
      :aria-label="t('tasks.board.addIn', { state: t(`tasks.states.${currentState}`) })"
      :data-folded="folded || undefined"
      @click="adding = currentState"
    >
      <Plus :class="folded ? 'size-6' : undefined" />
      <span v-if="!folded">{{ addLabel }}</span>
    </Button>
    <DropdownMenu v-else>
      <DropdownMenuTrigger as-child>
        <Button class="pointer-events-auto min-h-11 rounded-full px-5 shadow-lg">
          <Plus />{{ t('tasks.board.add') }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem
          v-for="state in STATES.filter((state) => state !== 'done')"
          :key="state"
          @select="adding = state"
        >
          {{ t(`tasks.states.${state}`) }}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
  <Panel
    v-model:open="addOpen"
    :title="t('tasks.board.newTaskIn', { state: adding ? t(`tasks.states.${adding}`) : '' })"
  >
    <ColumnAdd
      v-if="adding"
      :key="`${listId}-${adding}`"
      :list-id="listId"
      :state="adding"
      @select="openCreated"
      @reordered="reordered"
    />
  </Panel>
  <Panel v-model:open="askOpen" :title="t('tasks.board.waitingTitle')">
    <form v-if="asking" class="space-y-4" @submit.prevent="answer">
      <p class="font-medium">{{ asking.title }}</p>
      <div class="space-y-2">
        <Label for="board-waiting-reason">{{ t('tasks.waitingReason') }}</Label>
        <Input id="board-waiting-reason" v-model="reason" maxlength="2000" />
      </div>
      <Button type="submit">{{ t('tasks.board.moveWaiting') }}</Button>
    </form>
  </Panel>
  <TaskMoveSheet
    v-model:task="acting"
    :column="acting ? stateOf(acting) : null"
    :counts="counts"
    :queue-mode="queueMode"
    @move="move"
    @reorder="reorder"
    @manual="manual"
    @select="selectTask"
  />
  <TaskCompletionDialog :completion="actions.completion" @inspect="selectTask" />
</template>
