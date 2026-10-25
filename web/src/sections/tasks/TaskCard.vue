<script setup lang="ts">
import {
  ArrowUp,
  CalendarClock,
  Check,
  Clock,
  CornerDownRight,
  Ellipsis,
  ListChecks,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LinkedText from '@/components/LinkedText'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dayLabel } from '@/core/dates'
import { cn } from '@/lib/utils'
import { STATES, stateOf, type Task, type TaskState } from './api'
import { dateChip } from './dates'
import TaskCover from './TaskCover.vue'
import TaskDiscussionBadge from './TaskDiscussionBadge.vue'
import { QUEUE_COMMANDS, type QueueCommand } from './queue'

/**
 * A task on a project's board. The column already says the state, so the card carries only what
 * tells tasks apart: the date (sections/tasks/dates.ts says its colour), high priority, checklist
 * and subtask progress, who does it and what it waits for. «⋯» sits in the bottom corner: the
 * title keeps the card's full width and only the last line steps aside for it.
 */
const props = defineProps<{
  task: Task
  today: string
  assignee?: string | null
  /** Offers «Переместить в…»: the «⋯» button, a long press on a phone, Shift + ←/→. */
  canMove?: boolean
  /** A phone: «⋯» and a long press ask the board for its move sheet instead of a menu. */
  sheet?: boolean
  /** The column it stands in: right after a drop the task's own copy is a step behind. */
  column?: TaskState
  /** Just moved here: tinted for a moment so the eye finds it. */
  flash?: boolean
  queueMode?: 'manual' | 'sorted' | 'filtered'
}>()
const emit = defineEmits<{
  select: [task: Task]
  discussion: [task: Task]
  move: [task: Task, state: TaskState]
  reorder: [task: Task, command: QueueCommand]
  manual: []
  actions: [task: Task]
}>()
const { t } = useI18n()
const menu = ref(false)
const where = computed(() => props.column ?? stateOf(props.task))

// A long press on a touch screen opens the menu instead of the task; moving the finger scrolls.
let press: ReturnType<typeof setTimeout> | undefined
let pressed = false
let origin = { x: 0, y: 0 }
function down(event: PointerEvent) {
  if (!props.canMove || event.pointerType !== 'touch') return
  pressed = false
  origin = { x: event.clientX, y: event.clientY }
  press = setTimeout(() => {
    pressed = true
    actions()
  }, 450)
}
function moved(event: PointerEvent) {
  if (press && Math.hypot(event.clientX - origin.x, event.clientY - origin.y) > 8) cancel()
}
function cancel() {
  clearTimeout(press)
  press = undefined
}
function open() {
  // The click that ends a long press only closes it.
  if (pressed) pressed = false
  else emit('select', props.task)
}
// A right click (and a long press on Android) opens the same menu.
function context(event: MouseEvent) {
  if (!props.canMove) return
  event.preventDefault()
  actions()
}
function actions() {
  if (props.sheet) emit('actions', props.task)
  else menu.value = true
}
function step(event: KeyboardEvent, by: number) {
  if (!props.canMove || !event.shiftKey) return
  const next = STATES[STATES.indexOf(where.value) + by]
  if (!next) return
  event.preventDefault()
  emit('move', props.task, next)
}

function reorder(event: KeyboardEvent, command: QueueCommand) {
  if (!props.canMove || !event.altKey || where.value === 'done') return
  event.preventDefault()
  if (props.queueMode === 'sorted') emit('manual')
  else if (props.queueMode === 'manual') emit('reorder', props.task, command)
}

const TONES = {
  danger: 'bg-destructive/10 text-destructive',
  warning: 'bg-warning-surface text-warning',
  muted: 'bg-muted text-muted-foreground',
} as const
const date = computed(() => {
  const chip = dateChip(props.task, props.today)
  return chip
    ? {
        text: t(chip.label, { date: dayLabel(chip.day, props.today) }),
        tone: TONES[chip.tone],
        soon: chip.tone === 'warning',
      }
    : null
})
const checklist = computed(() => {
  const items = props.task.checklist ?? []
  return items.length ? `${items.filter((item) => item.done).length}/${items.length}` : null
})
const subtasks = computed(() =>
  props.task.subtasks?.total ? `${props.task.subtasks.done}/${props.task.subtasks.total}` : null,
)
const initials = computed(() =>
  (props.assignee ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]!.toUpperCase())
    .join(''),
)
const done = computed(() => stateOf(props.task) === 'done')
const meta = computed(() => {
  const marks = [date.value, checklist.value, subtasks.value, initials.value]
  return props.task.priority === 1 || !!props.task.discussion?.total || marks.some(Boolean)
})
/** What a waiting task waits for, when it says. */
const waiting = computed(() =>
  props.task.state === 'waiting' ? props.task.waiting_reason || null : null,
)
/** The bottom line of the card, which leaves room for «⋯» in the corner. */
const last = computed(() => {
  if (waiting.value) return 'waiting'
  if (meta.value) return 'meta'
  return props.task.parent ? 'parent' : 'title'
})
const room = (line: 'title' | 'parent' | 'meta' | 'waiting') =>
  props.canMove && last.value === line ? 'pe-9' : ''
const chip =
  'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium tabular-nums'
</script>

<template>
  <div class="relative" data-testid="task-card">
    <div
      :class="
        cn(
          'bg-card hover:border-primary relative w-full rounded-xl border p-3 text-left transition-colors duration-700',
          flash && 'bg-accent border-primary duration-0',
        )
      "
    >
      <button
        type="button"
        class="focus-visible:ring-ring absolute inset-0 rounded-xl select-none [-webkit-touch-callout:none] focus-visible:ring-2 focus-visible:outline-none"
        :data-task="task.id"
        :aria-label="task.title"
        :aria-keyshortcuts="
          canMove ? 'Shift+ArrowLeft Shift+ArrowRight Alt+ArrowUp Alt+ArrowDown' : undefined
        "
        @click="open"
        @pointerdown="down"
        @pointermove="moved"
        @pointerup="cancel"
        @pointercancel="cancel"
        @contextmenu="context"
        @keydown.left="step($event, -1)"
        @keydown.right="step($event, 1)"
        @keydown.up="reorder($event, 'up')"
        @keydown.down="reorder($event, 'down')"
      />
      <span :class="cn('pointer-events-none relative flex items-start gap-2', room('title'))">
        <Check
          v-if="done"
          class="text-muted-foreground mt-0.5 size-4 shrink-0"
          :aria-label="t('tasks.states.done')"
        />
        <LinkedText
          :text="task.title"
          :class="[
            'line-clamp-3 min-w-0 flex-1 font-medium break-words',
            done && 'text-muted-foreground',
          ]"
        />
        <TaskCover :task="task" />
      </span>
      <span
        v-if="task.parent"
        :class="
          cn(
            'text-muted-foreground pointer-events-none relative mt-2 block text-sm',
            room('parent'),
          )
        "
      >
        {{ t('tasks.parent', { title: task.parent.title }) }}
        <span
          v-if="task.status === 'open' && (task.parent.deleted_at || task.parent.state === 'done')"
          class="mt-1 block"
        >
          {{ t(task.parent.deleted_at ? 'tasks.parentDeleted' : 'tasks.parentCompleted') }}
        </span>
      </span>
      <span
        v-if="meta"
        :class="
          cn('pointer-events-none relative mt-2 flex flex-wrap items-center gap-1.5', room('meta'))
        "
      >
        <!-- A near or slipped date carries a clock, not only amber («Просрочено» says itself). -->
        <span v-if="date" :class="[chip, date.tone]"
          ><CalendarClock v-if="date.soon" class="size-3.5" aria-hidden="true" />{{
            date.text
          }}</span
        >
        <span
          v-if="task.priority === 1 && !done"
          :class="[chip, 'text-destructive px-0']"
          :title="t('tasks.priorities.1')"
          ><ArrowUp class="size-3.5" aria-hidden="true" />{{ t('tasks.important') }}</span
        >
        <span v-if="checklist" :class="[chip, 'bg-muted text-muted-foreground']"
          ><ListChecks class="size-3.5" aria-hidden="true" /><span class="sr-only">{{
            t('tasks.checklist')
          }}</span
          >{{ checklist }}</span
        >
        <span v-if="subtasks" :class="[chip, 'bg-muted text-muted-foreground']"
          ><CornerDownRight class="size-3.5" aria-hidden="true" /><span class="sr-only">{{
            t('tasks.subtasks')
          }}</span
          >{{ subtasks }}</span
        >
        <TaskDiscussionBadge :discussion="task.discussion" @open="emit('discussion', task)" />
        <span
          v-if="initials"
          class="bg-accent text-accent-foreground ms-auto grid size-6 place-items-center rounded-full text-[0.625rem] font-semibold"
          :title="assignee ?? undefined"
          ><span aria-hidden="true">{{ initials }}</span
          ><span class="sr-only">{{ t('tasks.assignedTo', { name: assignee }) }}</span></span
        >
      </span>
      <span
        v-if="waiting"
        :class="
          cn(
            'text-warning pointer-events-none relative mt-2 flex items-start gap-1.5 text-sm',
            room('waiting'),
          )
        "
        ><Clock class="mt-0.5 size-3.5 shrink-0" aria-hidden="true" /><LinkedText
          :text="waiting"
          class="min-w-0"
      /></span>
    </div>
    <button
      v-if="canMove && sheet"
      type="button"
      class="text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-ring absolute right-0 bottom-0 grid size-11 place-items-center rounded-xl focus-visible:ring-2 focus-visible:outline-none"
      :aria-label="t('tasks.board.moveTask', { title: task.title })"
      aria-haspopup="dialog"
      @click="emit('actions', task)"
    >
      <Ellipsis class="size-4" />
    </button>
    <DropdownMenu v-else-if="canMove" v-model:open="menu">
      <DropdownMenuTrigger as-child>
        <button
          type="button"
          class="text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-ring absolute right-1.5 bottom-1.5 grid size-8 place-items-center rounded-lg focus-visible:ring-2 focus-visible:outline-none pointer-coarse:size-10"
          :aria-label="t('tasks.board.moveTask', { title: task.title })"
        >
          <Ellipsis class="size-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" class="min-w-56">
        <DropdownMenuLabel>{{ t('tasks.board.moveTo') }}</DropdownMenuLabel>
        <DropdownMenuItem
          v-for="state in STATES"
          :key="state"
          :disabled="state === where"
          @select="emit('move', task, state)"
          >{{ t(`tasks.states.${state}`) }}<Check v-if="state === where" class="ms-auto"
        /></DropdownMenuItem>
        <template v-if="where !== 'done' && queueMode">
          <DropdownMenuSeparator />
          <DropdownMenuLabel>{{ t('tasks.queue.label') }}</DropdownMenuLabel>
          <template v-if="queueMode === 'manual'">
            <DropdownMenuItem
              v-for="command in QUEUE_COMMANDS"
              :key="command"
              @select="emit('reorder', task, command)"
              >{{ t(`tasks.queue.${command}`) }}</DropdownMenuItem
            >
          </template>
          <DropdownMenuItem v-else-if="queueMode === 'sorted'" @select="emit('manual')">
            {{ t('tasks.queue.useManual') }}
          </DropdownMenuItem>
          <DropdownMenuLabel v-else class="max-w-64 whitespace-normal font-normal">
            {{ t('tasks.queue.filtered') }}
          </DropdownMenuLabel>
        </template>
        <DropdownMenuSeparator />
        <DropdownMenuItem @select="emit('select', task)">{{
          t('tasks.board.open')
        }}</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
</template>
