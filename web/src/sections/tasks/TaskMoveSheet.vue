<script setup lang="ts">
import { Check } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { STATES, type Task, type TaskState } from './api'
import { QUEUE_COMMANDS, type QueueCommand } from './queue'
import { STATE_DOTS } from './stateDots'

/**
 * Moving a card on a phone, from its «⋯» or a long press: the four states as large rows with how
 * many tasks each holds, the place in the queue, and the task itself. «Ожидание» asks what for in
 * the same sheet. Wide screens keep the card's menu.
 */
const task = defineModel<Task | null>('task', { required: true })
const props = defineProps<{
  /** The column the card stands in. */
  column: TaskState | null
  counts: Record<TaskState, number | string>
  queueMode: 'manual' | 'sorted' | 'filtered'
}>()
const emit = defineEmits<{
  move: [task: Task, state: TaskState, reason?: string]
  reorder: [task: Task, command: QueueCommand]
  manual: []
  select: [task: Task]
}>()
const { t } = useI18n()
const open = computed({
  get: () => !!task.value,
  set: (value) => {
    if (!value) task.value = null
  },
})
const asking = ref(false)
const reason = ref('')
watch(
  task,
  () => {
    asking.value = false
    reason.value = task.value?.waiting_reason ?? ''
  },
  { immediate: true },
)

/** Runs an action on the task and closes the sheet first: one modal at a time. */
function act(run: (current: Task) => void) {
  const current = task.value
  if (!current) return
  task.value = null
  run(current)
}
async function choose(state: TaskState) {
  if (state !== 'waiting' || props.column === 'waiting')
    return act((current) => emit('move', current, state))
  // The pressed row goes away with the list: the question takes the focus.
  asking.value = true
  await nextTick()
  document.getElementById('move-waiting-reason')?.focus()
}
function wait() {
  act((current) => emit('move', current, 'waiting', reason.value))
}
</script>

<template>
  <Panel
    v-model:open="open"
    :title="asking ? t('tasks.board.waitingTitle') : t('tasks.board.moveTo')"
    :description="task?.title"
    :autofocus="false"
    data-testid="task-move-sheet"
  >
    <form v-if="task && asking" class="space-y-4" @submit.prevent="wait">
      <div class="space-y-2">
        <Label for="move-waiting-reason">{{ t('tasks.waitingReason') }}</Label>
        <Input id="move-waiting-reason" v-model="reason" maxlength="2000" />
      </div>
      <Button type="submit" class="w-full">{{ t('tasks.board.moveWaiting') }}</Button>
    </form>
    <template v-else-if="task">
      <ul class="-mx-2 space-y-1">
        <li v-for="state in STATES" :key="state">
          <button
            type="button"
            class="hover:bg-accent focus-visible:ring-ring/50 aria-[current=true]:bg-accent flex min-h-12 w-full items-center gap-3 rounded-xl px-3 text-left outline-none focus-visible:ring-3"
            :aria-current="state === column ? 'true' : undefined"
            :disabled="state === column"
            @click="choose(state)"
          >
            <span
              :class="['size-2.5 shrink-0 rounded-full', STATE_DOTS[state]]"
              aria-hidden="true"
            />
            <span class="min-w-0 flex-1 font-medium">{{ t(`tasks.states.${state}`) }}</span>
            <Check v-if="state === column" class="size-5 shrink-0" aria-hidden="true" />
            <span class="text-muted-foreground text-sm tabular-nums" aria-hidden="true">{{
              counts[state]
            }}</span>
          </button>
        </li>
      </ul>
      <template v-if="column !== 'done'">
        <div
          v-if="queueMode === 'manual'"
          role="group"
          :aria-label="t('tasks.queue.label')"
          class="grid grid-cols-2 gap-2"
        >
          <Button
            v-for="command in QUEUE_COMMANDS"
            :key="command"
            variant="outline"
            @click="act((current) => emit('reorder', current, command))"
            >{{ t(`tasks.queue.${command}`) }}</Button
          >
        </div>
        <Button
          v-else-if="queueMode === 'sorted'"
          variant="outline"
          class="w-full"
          @click="act(() => emit('manual'))"
          >{{ t('tasks.queue.useManual') }}</Button
        >
        <p v-else class="text-muted-foreground text-sm">{{ t('tasks.queue.filtered') }}</p>
      </template>
      <Button
        variant="secondary"
        class="w-full"
        @click="act((current) => emit('select', current))"
        >{{ t('tasks.board.open') }}</Button
      >
    </template>
  </Panel>
</template>
