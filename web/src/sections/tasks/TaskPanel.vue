<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ChevronLeft, ChevronRight, CircleAlert, MoreHorizontal } from '@lucide/vue'
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import DateField from '@/components/DateField.vue'
import Panel from '@/components/Panel.vue'
import LinkedText from '@/components/LinkedText'
import SegmentedControl from '@/components/SegmentedControl.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { api } from '@/core/client'
import { dayIn, dayLabel, timeIn } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { operationKind, useLedger } from '@/core/operations'
import { useDraft } from '@/core/useDraft'
import { useMembers, useTaskLists } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import { useTaskActions, type Transition } from './useTaskActions'
import { useTaskCompletion } from './useTaskCompletion'
import { evictList } from './useTaskBoardPages'
import {
  IMAGE_LIMITS,
  STATES,
  TASKS,
  Task,
  changesOf,
  fieldsOf,
  stateOf,
  type TaskFields,
  type TaskImage,
  type TaskState,
} from './api'
import { taskImageUrl } from './images'
import TaskAssistant from './TaskAssistant.vue'
import TaskCompletionDialog from './TaskCompletionDialog.vue'
import TaskDiscussion from './TaskDiscussion.vue'
import TaskHistory from './TaskHistory.vue'
import TaskReminder from './TaskReminder.vue'
import TaskSteps from './TaskSteps.vue'
import TaskTextField from './TaskTextField.vue'

defineOptions({ inheritAttrs: false })
const props = withDefaults(
  defineProps<{
    task: Task | null
    today: string
    canWrite?: boolean
    /** A column beside the list on a wide page (Panel's `docked`). */
    docked?: boolean
    /** Adjacent tasks already loaded in this project's current board column. */
    previous?: Task | null
    next?: Task | null
    initialTab?: 'details' | 'assistant' | 'discussion' | 'history'
  }>(),
  { canWrite: false, docked: false, previous: null, next: null, initialTab: 'details' },
)
const emit = defineEmits<{
  select: [task: Task]
  tab: [name: string]
  denied: [taskId: string, error: ApiError]
}>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const session = useSession()
const queries = useQueryClient()
const ledger = useLedger(TASKS)
const completion = useTaskCompletion(ledger.operate)
const actions = useTaskActions(completion)
const busy = ref(false)
const panel = ref<InstanceType<typeof Panel> | null>(null)
const steps = ref<InstanceType<typeof TaskSteps> | null>(null)
// Pictures still uploading into the title and the description; saving waits for them.
const uploading = ref({ title: 0, description: 0 })
const waitingField = ref<InstanceType<typeof TaskTextField> | null>(null)
const textEditSession = ref(0)
const lists = useTaskLists()
const taskList = computed(() =>
  lists.data.value?.lists.find((item) => item.id === props.task?.list_id),
)
const members = useMembers(computed(() => taskList.value?.space_id ?? null))
const tabs = computed(() =>
  props.task?.deleted_at
    ? ['details', 'history']
    : ['details', 'assistant', 'discussion', 'history'],
)
const allowedTab = (value: string) => (tabs.value.includes(value) ? value : 'details')
const tab = ref<string>(allowedTab(props.initialTab))
watch(
  [() => props.initialTab, () => props.task?.deleted_at],
  ([value]) => (tab.value = allowedTab(value)),
)
watch(tab, (value) => {
  if (open.value) emit('tab', value)
})
const destination = ref('')
const transferHistory = ref(false)
const transferOpen = ref(false)
const destinations = computed(
  () =>
    lists.data.value?.lists.filter(
      (item) => item.id !== props.task?.list_id && ['edit', 'manage'].includes(item.effective),
    ) ?? [],
)
const channel = useQuery({
  queryKey: computed(() => ['tasks', 'discussion', props.task?.id]),
  enabled: computed(
    () => open.value && tab.value === 'discussion' && !!props.task && !props.task.deleted_at,
  ),
  queryFn: async ({ signal }) =>
    await api.get<{ channel_id: string }>(
      `/v1/tasks/items/${encodeURIComponent(props.task!.id)}/discussion`,
      signal,
    ),
})
/** The version on the server when a save was refused because it had changed. */
const newer = shallowRef<Task | null>(null)

const emptyFields = (): TaskFields => ({
  title: '',
  description: '',
  planned_on: '',
  due_on: '',
  state: 'todo',
  priority: '2',
  waiting_reason: '',
  assigned_to: '',
  title_images: [],
  description_images: [],
})
// The draft keeps both what the user started from and what they typed.
const draft = useDraft(
  computed(() => (open.value && props.task ? `task:${props.task.id}` : null)),
  () => ({ base: emptyFields(), fields: emptyFields(), revision: 0 }),
)
watch(
  [open, () => props.task?.id],
  () => {
    completion.cancel()
    transferOpen.value = false
    newer.value = null
    // An upload into another card or a closed one never lands here: its field is gone.
    uploading.value = { title: 0, description: 0 }
    if (open.value && props.task) {
      tab.value = allowedTab(props.initialTab)
      destination.value = ''
      transferHistory.value = false
      draft.start({
        base: fieldsOf(props.task),
        fields: fieldsOf(props.task),
        revision: props.task.revision,
      })
      void nextTick(() => panel.value?.resetScroll())
    }
  },
  { immediate: true },
)
const fields = computed(() => draft.value.value.fields)
const trashed = computed(() => !!props.task?.deleted_at)
const changes = computed(() => changesOf(draft.value.value.base, fields.value))
const dirty = computed(() => Object.keys(changes.value).length > 0)
const navigation = computed(() => !props.docked && (!!props.previous || !!props.next))
const navigationDisabled = computed(() => busy.value || dirty.value || uploadingImages.value > 0)
function navigate(task: Task | null) {
  if (
    !task ||
    !props.task ||
    navigationDisabled.value ||
    task.list_id !== props.task.list_id ||
    stateOf(task) !== stateOf(props.task)
  )
    return
  emit('select', task)
}
function historyDenied(error: ApiError) {
  const task = props.task
  if (!task) return
  if (task.list_id) evictList(queries, task.list_id, error)
  open.value = false
  emit('denied', task.id, error)
}
// The store keeps every state of the form, also one without changes: a task changed by its own
// receipt just before the panel closed (completed from here) leaves such a copy. Restored, it
// would show the task as it was; without changes there is nothing to keep, so it starts anew.
watch(
  () => draft.restored.value,
  (restored) => {
    if (!restored || dirty.value || !props.task) return
    draft.value.value = {
      base: fieldsOf(props.task),
      fields: fieldsOf(props.task),
      revision: props.task.revision,
    }
    draft.restored.value = false
  },
)
watch(
  () => props.task?.revision,
  () => {
    if (props.task && open.value && !dirty.value)
      draft.value.value = {
        base: fieldsOf(props.task),
        fields: fieldsOf(props.task),
        revision: props.task.revision,
      }
  },
)
const datesOdd = computed(
  () =>
    fields.value.planned_on && fields.value.due_on && fields.value.planned_on > fields.value.due_on,
)
const zone = computed(() => session.me?.timezone ?? 'UTC')
const compared = computed(() =>
  newer.value
    ? (Object.keys(changes.value) as Array<keyof TaskFields>).map((key) => ({
        key,
        server: fieldsOf(newer.value!)[key],
        yours: fields.value[key],
      }))
    : [],
)

/** A transition confirms itself (with an undo); the panel closes once it is done. */
async function run(task: () => Promise<unknown>, message?: string) {
  if (busy.value) return
  const taskId = props.task?.id
  busy.value = true
  try {
    const result = await task()
    if (result === false || result === null) return
    if (message) toast.success(message)
    if (props.task?.id === taskId) open.value = false
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

async function save(revision = draft.value.value.revision || props.task!.revision) {
  if (!props.canWrite || !props.task || !dirty.value || busy.value || uploadingImages.value) return
  const taskId = props.task.id
  busy.value = true
  try {
    const response = await completion.operate({
      kind: 'task.update',
      payload: { task_id: taskId, changes: changes.value },
      expected_revisions: { [taskId]: revision },
    })
    if (response === null) return
    await draft.clear(`task:${taskId}`)
    toast.success(t('tasks.saved'))
    if (props.task?.id === taskId) open.value = false
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      // Keep the draft; show what changed on the server and let the user apply theirs on top.
      try {
        const current = Task.parse(await api.get(`/v1/tasks/items/${encodeURIComponent(taskId)}`))
        if (props.task?.id === taskId) newer.value = current
      } catch (reread) {
        // The draft stays; without the newer version the user learns why the save failed.
        toast.error(`${t('tasks.conflictTitle')}. ${errorText(reread, t)}`)
      }
    } else toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

// Drops the edits: the card shows the task as it is now, the newer version after a conflict.
async function discard() {
  const current = newer.value ?? props.task
  await draft.clear()
  if (props.task?.id !== current?.id) return
  if (current)
    draft.value.value = {
      base: fieldsOf(current),
      fields: fieldsOf(current),
      revision: current.revision,
    }
  newer.value = null
  uploading.value = { title: 0, description: 0 }
  textEditSession.value++
}

const operate = (kind: Transition, message: string) =>
  run(() => actions.transition(props.task!, kind, message))

// The state changes at once, like a card moved on the board, and can be taken back. Waiting then
// asks what for in its field below. Unsaved edits come first: the change would outdate them.
const states = computed(() =>
  STATES.map((state) => ({ value: state, label: t(`tasks.states.${state}`) })),
)
async function choose(value: string) {
  const task = props.task
  const state = value as TaskState
  if (!task || !props.canWrite || dirty.value || busy.value || state === stateOf(task)) return
  busy.value = true
  try {
    await actions.setState(
      task,
      state,
      t('tasks.board.moved', { state: t(`tasks.states.${state}`) }),
      state === 'waiting' ? (task.waiting_reason ?? '') : undefined,
    )
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
  if (state === 'waiting' && props.task?.id === task.id)
    void nextTick(() => waitingField.value?.edit())
}

async function move() {
  if (!props.task || !destination.value || !transferHistory.value || !props.canWrite || dirty.value)
    return
  await run(
    () =>
      ledger.operate({
        kind: 'task.move',
        payload: { task_id: props.task!.id, list_id: destination.value },
        expected_revisions: { [props.task!.id]: props.task!.revision },
      }),
    t('tasks.saved'),
  )
}

async function inspectCompletion(task: Task) {
  if (task.id !== props.task?.id) {
    emit('select', task)
    return
  }
  tab.value = 'details'
  await nextTick()
  steps.value?.focus()
}

async function openParent() {
  const task = props.task
  if (!task?.parent_id || busy.value) return
  try {
    const parent = Task.parse(
      await api.get(`/v1/tasks/items/${encodeURIComponent(task.parent_id)}`),
    )
    if (props.task?.id === task.id) emit('select', parent)
  } catch (error) {
    toast.error(errorText(error, t))
  }
}

async function detach() {
  const task = props.task
  if (!task?.parent_id || !props.canWrite || busy.value || dirty.value) return
  busy.value = true
  try {
    await ledger.operate({
      kind: 'task.update',
      payload: { task_id: task.id, changes: { parent_id: null } },
      expected_revisions: { [task.id]: task.revision },
    })
    toast.success(t('tasks.saved'))
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

const shown = (value: string | TaskImage[], key: keyof TaskFields) =>
  Array.isArray(value)
    ? value.length
      ? t('tasks.images.count', { count: value.length }, value.length)
      : t('tasks.images.none')
    : key === 'planned_on' || key === 'due_on'
      ? value
        ? dayLabel(value, props.today)
        : t('tasks.noDate')
      : value || '—'
// The task's own address serves its pictures to every reader, and to the uploader before saving.
const imageSource = (image: TaskImage, preview: boolean) =>
  taskImageUrl(props.task!.id, image.id, preview)
const uploadingImages = computed(() => uploading.value.title + uploading.value.description)
</script>

<template>
  <Panel
    v-bind="$attrs"
    ref="panel"
    v-model:open="open"
    :title="task?.title ?? t('tasks.detail')"
    :autofocus="false"
    :docked="docked"
  >
    <template #title><LinkedText :text="task?.title ?? t('tasks.detail')" /></template>
    <template v-if="task && canWrite && !trashed" #actions>
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button
            variant="ghost"
            size="icon-sm"
            class="-mt-1"
            :aria-label="t('tasks.moreActions')"
            :disabled="busy"
          >
            <MoreHorizontal />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <template v-if="destinations.length">
            <DropdownMenuItem :disabled="dirty || busy" @select="transferOpen = true">
              {{ t('tasks.moveToProject') }}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
          </template>
          <DropdownMenuItem
            variant="destructive"
            :disabled="dirty || busy"
            @select="operate('task.delete', t('tasks.trashed'))"
          >
            {{ t('tasks.toTrash') }}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </template>
    <template v-if="task">
      <nav v-if="navigation" class="grid grid-cols-2 gap-2" :aria-label="t('tasks.cardNavigation')">
        <Button
          variant="outline"
          :disabled="!previous || navigationDisabled"
          @click="navigate(previous)"
        >
          <ChevronLeft />{{ t('tasks.previousTask') }}
        </Button>
        <Button variant="outline" :disabled="!next || navigationDisabled" @click="navigate(next)">
          {{ t('tasks.nextTask') }}<ChevronRight />
        </Button>
        <p class="sr-only" aria-live="polite">{{ task.title }}</p>
      </nav>
      <p v-if="navigation && dirty" class="text-muted-foreground text-sm">
        {{ t('tasks.saveBeforeNavigation') }}
      </p>
      <div v-if="task.parent_id" class="space-y-1 rounded-xl border p-3">
        <Button variant="link" class="h-auto max-w-full p-0 whitespace-normal" @click="openParent">
          {{ t('tasks.parent', { title: task.parent?.title ?? t('tasks.detail') }) }}
        </Button>
        <p
          v-if="task.status === 'open' && task.parent?.deleted_at"
          class="text-muted-foreground text-sm"
        >
          {{ t('tasks.parentDeleted') }}
        </p>
        <p
          v-else-if="task.status === 'open' && task.parent?.state === 'done'"
          class="text-muted-foreground text-sm"
        >
          {{ t('tasks.parentCompleted') }}
        </p>
        <Button
          v-if="canWrite && !trashed"
          size="sm"
          variant="ghost"
          :disabled="busy || dirty"
          @click="detach"
        >
          {{ t('tasks.detach') }}
        </Button>
      </div>
      <fieldset
        v-if="canWrite && !trashed"
        :disabled="busy || dirty"
        class="@container space-y-1"
        data-testid="task-state"
      >
        <!-- Four states: two by two on a phone, all visible, none on a second row of its own. -->
        <SegmentedControl
          even
          :segments="states"
          :label="t('tasks.state')"
          :model-value="stateOf(task)"
          @update:model-value="choose"
        />
        <p v-if="dirty" class="text-muted-foreground text-xs">{{ t('tasks.saveBeforeState') }}</p>
      </fieldset>
      <div class="flex flex-wrap items-center gap-2">
        <Badge
          v-if="trashed || !canWrite"
          :variant="trashed ? 'destructive' : task.status === 'done' ? 'success' : 'secondary'"
        >
          {{ trashed ? t('tasks.status.trash') : t(`tasks.states.${stateOf(task)}`) }}
        </Badge>
        <span v-if="task.updated_at" class="text-muted-foreground text-xs">
          {{
            t('tasks.updated', {
              time: `${dayLabel(dayIn(zone, new Date(task.updated_at)), today)} ${timeIn(zone, task.updated_at)}`,
            })
          }}
        </span>
      </div>
      <UnresolvedWrites
        :entries="ledger.unresolved.value"
        :describe="(entry) => t('tasks.unresolved', { kind: operationKind(entry as never) })"
        :check="ledger.check"
        :resend="ledger.resend"
        :abandon="ledger.abandon"
      />
      <!-- Tabs look like the app's other switches: a lifted segment on a grey track. -->
      <div class="@container">
        <div
          class="bg-muted flex flex-wrap gap-1 rounded-xl p-1"
          role="tablist"
          :aria-label="t('tasks.detail')"
        >
          <button
            v-for="name in tabs"
            :key="name"
            type="button"
            role="tab"
            :aria-selected="tab === name"
            class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 aria-selected:bg-background aria-selected:text-foreground dark:aria-selected:border-border dark:aria-selected:bg-accent min-h-11 min-w-max flex-1 basis-36 rounded-lg border border-transparent px-1.5 text-sm font-medium whitespace-nowrap outline-none focus-visible:ring-3 aria-selected:shadow-sm"
            :class="!trashed && '@sm:basis-0'"
            @click="tab = name"
          >
            {{ t(`tasks.tabs.${name}`) }}
          </button>
        </div>
      </div>
      <TaskAssistant
        v-if="tab === 'assistant' && !trashed"
        :task="task"
        :can-write="canWrite"
        @inspect="inspectCompletion"
      />
      <TaskDiscussion
        v-if="tab === 'discussion' && !trashed"
        :key="task.id"
        :channel-id="channel.data.value?.channel_id ?? null"
        :error="channel.error.value"
        :can-write="canWrite"
        :active="open && tab === 'discussion'"
        @retry="channel.refetch()"
      />
      <TaskHistory
        v-if="tab === 'history'"
        :key="task.id"
        :task-id="task.id"
        :revision="task.revision"
        :active="open"
        @denied="historyDenied"
      />
      <template v-if="tab === 'details'">
        <p
          v-if="draft.restored.value && !newer"
          class="text-muted-foreground text-sm"
          role="status"
        >
          {{ t('tasks.draftRestored') }}
        </p>

        <div
          v-if="newer"
          class="bg-warning-surface space-y-3 rounded-2xl p-4"
          role="alert"
          data-testid="task-conflict"
        >
          <p class="flex items-start gap-2 font-medium">
            <CircleAlert class="text-warning mt-0.5 size-4 shrink-0" />{{
              t('tasks.conflictTitle')
            }}
          </p>
          <p class="text-muted-foreground text-sm">{{ t('tasks.conflictText') }}</p>
          <dl class="space-y-2 text-sm">
            <div v-for="row in compared" :key="row.key" class="grid grid-cols-2 gap-2">
              <div>
                <dt class="text-muted-foreground">{{ t('tasks.onServer') }}</dt>
                <dd>{{ shown(row.server, row.key) }}</dd>
              </div>
              <div>
                <dt class="text-muted-foreground">{{ t('tasks.yours') }}</dt>
                <dd class="font-medium">{{ shown(row.yours, row.key) }}</dd>
              </div>
            </div>
          </dl>
          <div class="flex flex-wrap gap-2">
            <Button size="sm" :disabled="busy" @click="save(newer.revision)">{{
              t('tasks.apply')
            }}</Button>
            <Button size="sm" variant="ghost" :disabled="busy" @click="discard">{{
              t('tasks.discard')
            }}</Button>
          </div>
        </div>

        <fieldset
          :key="`${task.id}:${open}:${textEditSession}`"
          :disabled="trashed || !canWrite || busy"
          class="@container space-y-4"
        >
          <TaskTextField
            id="task-title"
            v-model="fields.title"
            v-model:images="fields.title_images"
            v-model:uploading="uploading.title"
            :label="t('tasks.name')"
            :maxlength="300"
            :image-limit="IMAGE_LIMITS.title_images"
            :image-source="imageSource"
            :image-elsewhere="(id) => fields.description_images.some((image) => image.id === id)"
            :disabled="trashed || !canWrite || busy"
          />
          <TaskTextField
            id="task-description"
            v-model="fields.description"
            v-model:images="fields.description_images"
            v-model:uploading="uploading.description"
            :label="t('tasks.description')"
            :maxlength="10000"
            multiline
            :image-limit="IMAGE_LIMITS.description_images"
            :image-source="imageSource"
            :image-elsewhere="(id) => fields.title_images.some((image) => image.id === id)"
            :disabled="trashed || !canWrite || busy"
          />
        </fieldset>
        <TaskSteps
          v-if="!trashed"
          :key="task.id"
          ref="steps"
          :task="task"
          :can-write="canWrite && !dirty && !busy"
          @select="emit('select', $event)"
        />
        <fieldset
          :key="`${task.id}:${open}:${textEditSession}:properties`"
          :disabled="trashed || !canWrite || busy"
          class="@container space-y-4"
          data-testid="task-properties"
        >
          <div class="grid grid-cols-2 gap-3">
            <div class="space-y-2">
              <Label for="task-priority">{{ t('tasks.priority') }}</Label
              ><NativeSelect id="task-priority" v-model="fields.priority"
                ><option v-for="priority in ['1', '2', '3']" :key="priority" :value="priority">
                  {{ t(`tasks.priorities.${priority}`) }}
                </option></NativeSelect
              >
            </div>
          </div>
          <TaskTextField
            v-if="fields.state === 'waiting'"
            id="task-waiting"
            ref="waitingField"
            v-model="fields.waiting_reason"
            :label="t('tasks.waitingReason')"
            :maxlength="2000"
            :disabled="trashed || !canWrite || busy"
          />
          <div v-if="taskList?.space_id" class="space-y-2">
            <Label for="task-assignee">{{ t('circle.assignee') }}</Label
            ><NativeSelect id="task-assignee" v-model="fields.assigned_to"
              ><option value="">{{ t('circle.noAssignee') }}</option>
              <option
                v-for="member in members.data.value?.members ?? []"
                :key="member.user_id"
                :value="member.user_id"
              >
                {{ member.name }}
              </option></NativeSelect
            >
          </div>
          <!-- Side by side only where a date with its year fits: a phone stacks them. -->
          <div class="grid items-end gap-3 @sm:grid-cols-2">
            <div class="space-y-2">
              <Label for="task-planned">{{ t('tasks.plannedOn') }}</Label>
              <DateField
                id="task-planned"
                v-model="fields.planned_on"
                clearable
                :placeholder="t('tasks.noDate')"
              />
            </div>
            <div class="space-y-2">
              <Label for="task-due">{{ t('tasks.dueOn') }}</Label>
              <DateField
                id="task-due"
                v-model="fields.due_on"
                clearable
                :placeholder="t('tasks.noDate')"
              />
            </div>
          </div>
          <p v-if="datesOdd" class="text-warning text-sm">{{ t('tasks.plannedAfterDue') }}</p>
        </fieldset>
        <p v-if="trashed" class="text-muted-foreground text-sm">{{ t('tasks.trashHint') }}</p>

        <div v-if="canWrite && trashed" class="grid grid-cols-1 gap-2">
          <template v-if="trashed">
            <Button
              size="lg"
              :disabled="busy"
              @click="operate('task.restore', t('tasks.restored'))"
            >
              {{ t('tasks.restore') }}
            </Button>
          </template>
        </div>
        <TaskReminder :task="task" />
      </template>
    </template>
    <!-- Saving appears with an edit and stays at the bottom of the card while it scrolls;
         «Готово» above completes the task, with undo. -->
    <template v-if="task && canWrite && !trashed && dirty && !newer" #footer>
      <Button
        class="flex-1"
        :disabled="!fields.title.trim() || busy || uploadingImages > 0"
        @click="save()"
      >
        {{ t('tasks.save') }}
      </Button>
      <Button variant="outline" :disabled="busy" @click="discard">
        {{ t('common.cancel') }}
      </Button>
    </template>
  </Panel>
  <Panel v-model:open="transferOpen" :title="t('tasks.moveToProject')" data-testid="task-transfer">
    <NativeSelect v-model="destination" :aria-label="t('tasks.project')">
      <option value="" disabled>{{ t('tasks.chooseProject') }}</option>
      <option v-for="item in destinations" :key="item.id" :value="item.id">
        {{ item.name }}
      </option>
    </NativeSelect>
    <label class="flex items-start gap-2 text-sm">
      <Checkbox v-model="transferHistory" />{{ t('tasks.moveHistoryWarning') }}
    </label>
    <Button
      variant="outline"
      :disabled="busy || dirty || !destination || !transferHistory"
      @click="move"
    >
      {{ t('tasks.moveToProject') }}
    </Button>
  </Panel>
  <TaskCompletionDialog :completion="completion" @inspect="inspectCompletion" />
</template>
