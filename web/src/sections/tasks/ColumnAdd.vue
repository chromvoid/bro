<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useLedger } from '@/core/operations'
import { TASKS, TaskPage, TaskReceipt, boardQuery, type Task, type TaskState } from './api'
import { useTaskActions } from './useTaskActions'

/**
 * One composer for the board. A successful write leaves it ready for the next task. Pasting
 * several lines offers one task per line, or all of them as one task.
 */
const props = defineProps<{ listId: string; state: TaskState }>()
const emit = defineEmits<{ select: [task: Task]; reordered: [] }>()
const { t } = useI18n()
const ledger = useLedger(TASKS)
const actions = useTaskActions()
const title = ref('')
const reason = ref('')
const due = ref('')
const priority = ref('2')
const busy = ref(false)
const created = ref<Task | null>(null)
/** How many tasks the last paste of several lines added. */
const createdMany = ref(0)
const moved = ref(false)
const id = useId()
const MAX_TITLE = 300

/** Lines pasted into the title, waiting for «Добавить N задач» or «Одной задачей». */
const pasted = ref<string[] | null>(null)
const progress = ref(0)
function paste(event: ClipboardEvent) {
  const lines = (event.clipboardData?.getData('text/plain') ?? '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  if (lines.length < 2) return
  event.preventDefault()
  pasted.value = lines
}
function asOne() {
  title.value = (pasted.value ?? []).join(' ').slice(0, MAX_TITLE)
  pasted.value = null
  document.getElementById(`${id}-title`)?.focus()
}

function create(text: string) {
  return ledger.operate({
    kind: 'task.create',
    payload: {
      title: text,
      state: props.state,
      ...(due.value ? { due_on: due.value } : {}),
      ...(priority.value !== '2' ? { priority: Number(priority.value) } : {}),
      ...(props.state === 'waiting' && reason.value.trim()
        ? { waiting_reason: reason.value.trim() }
        : {}),
    },
    target_resource_id: props.listId,
  })
}
/** Ready for the next task: the fields empty, the title focused. */
async function cleared() {
  title.value = ''
  reason.value = ''
  due.value = ''
  priority.value = '2'
  busy.value = false
  await nextTick()
  document.getElementById(`${id}-title`)?.focus()
}

async function add() {
  const text = title.value.trim()
  if (!text || busy.value) return
  busy.value = true
  try {
    const receipt = TaskReceipt.parse(await create(text))
    created.value = receipt.tasks?.[0] ?? null
    createdMany.value = 0
    moved.value = false
    await cleared()
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

/** One task per pasted line, in order; a failure keeps the lines not yet added. */
async function addLines() {
  const lines = pasted.value
  if (!lines?.length || busy.value) return
  busy.value = true
  progress.value = 0
  try {
    for (const line of lines) {
      await create(line.slice(0, MAX_TITLE))
      progress.value += 1
    }
    created.value = null
    createdMany.value = lines.length
    pasted.value = null
    await cleared()
  } catch (error) {
    pasted.value = lines.slice(progress.value)
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

async function toTop() {
  const task = created.value
  if (!task || busy.value) return
  busy.value = true
  try {
    // New cards are roots. Exclude independently visible children before paging so the first
    // neighbour belongs to this task's hierarchy level, even after many orphaned children.
    const params = boardQuery({ listId: props.listId, state: props.state, sort: 'manual' })
    params.set('parent_id', 'root')
    const page = TaskPage.parse(await api.get(`/v1/tasks/items?${params}`))
    const first = page.items[0]
    if (first && first.id !== task.id) {
      const changed = await actions.setState(
        task,
        props.state,
        t('tasks.queue.moved'),
        task.waiting_reason ?? undefined,
        first.id,
      )
      if (!changed) return
    }
    moved.value = true
    emit('reordered')
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="space-y-4" data-testid="board-composer" @submit.prevent="add">
    <div class="space-y-2">
      <Label :for="`${id}-title`">{{ t('tasks.board.newTask') }}</Label>
      <Input
        :id="`${id}-title`"
        v-model="title"
        :maxlength="MAX_TITLE"
        :disabled="busy"
        :aria-label="t('tasks.board.newTaskIn', { state: t(`tasks.states.${state}`) })"
        @paste="paste"
      />
    </div>
    <div class="grid grid-cols-2 gap-3">
      <div class="min-w-0 space-y-2">
        <Label :for="`${id}-due`">{{ t('tasks.board.dueOn') }}</Label>
        <Input :id="`${id}-due`" v-model="due" type="date" :disabled="busy" />
      </div>
      <div class="min-w-0 space-y-2">
        <Label :for="`${id}-priority`">{{ t('tasks.priority') }}</Label>
        <NativeSelect :id="`${id}-priority`" v-model="priority" :disabled="busy">
          <option v-for="value in [2, 1, 3]" :key="value" :value="String(value)">
            {{ t(`tasks.priorities.${value}`) }}
          </option>
        </NativeSelect>
      </div>
    </div>
    <div v-if="state === 'waiting'" class="space-y-2">
      <Label :for="`${id}-reason`">{{ t('tasks.waitingReason') }}</Label>
      <Input :id="`${id}-reason`" v-model="reason" maxlength="2000" :disabled="busy" />
    </div>
    <div
      v-if="pasted"
      class="bg-muted space-y-2 rounded-xl p-3"
      role="group"
      :aria-label="t('tasks.board.pasted', { count: pasted.length })"
      data-testid="board-pasted"
    >
      <p class="text-sm">{{ t('tasks.board.pasted', { count: pasted.length }) }}</p>
      <div class="flex flex-wrap gap-2">
        <Button type="button" size="sm" :disabled="busy" @click="addLines">
          {{
            busy
              ? t('tasks.board.addingMany', { done: progress, count: pasted.length })
              : t('tasks.board.addMany', { count: pasted.length }, pasted.length)
          }}
        </Button>
        <Button type="button" size="sm" variant="outline" :disabled="busy" @click="asOne">
          {{ t('tasks.board.asOne') }}
        </Button>
      </div>
    </div>
    <p class="text-muted-foreground text-sm">{{ t('tasks.board.optionalFields') }}</p>
    <Button type="submit" :disabled="!title.trim() || busy">{{ t('tasks.add') }}</Button>
    <p v-if="createdMany" role="status" class="bg-muted rounded-xl p-3 text-sm">
      {{ t('tasks.board.createdMany', { count: createdMany }) }}
    </p>
    <div v-if="created" class="bg-muted space-y-2 rounded-xl p-3">
      <p role="status" class="text-sm">{{ t('tasks.board.created', { title: created.title }) }}</p>
      <div class="flex flex-wrap gap-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          :disabled="busy"
          @click="emit('select', created)"
        >
          {{ t('tasks.board.openCreated') }}
        </Button>
        <Button type="button" size="sm" variant="outline" :disabled="busy || moved" @click="toTop">
          {{ t(moved ? 'tasks.board.atTop' : 'tasks.board.toTop') }}
        </Button>
      </div>
    </div>
  </form>
</template>
