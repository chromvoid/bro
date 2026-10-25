<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useLedger, type Operation } from '@/core/operations'
import { useDraft } from '@/core/useDraft'
import { useSession } from '@/stores/session'
import { TASKS, type Task } from './api'
import TaskCompletionDialog from './TaskCompletionDialog.vue'
import { useTaskCompletion } from './useTaskCompletion'
import {
  AssistantCapabilities,
  AssistantSnapshot,
  delegationIsActive,
  safeSource,
} from './assistant'
const props = defineProps<{ task: Task; canWrite: boolean }>()
const emit = defineEmits<{ inspect: [task: Task] }>()
const { t, te } = useI18n()
const session = useSession()
const ledger = useLedger(TASKS)
const completion = useTaskCompletion(ledger.operate)
watch(() => props.task.id, completion.cancel)
const snapshot = useQuery({
  queryKey: computed(() => ['tasks', 'assistant', props.task.id]),
  refetchInterval: 10_000,
  queryFn: async ({ signal }) =>
    AssistantSnapshot.parse(
      await api.get(`/v1/tasks/items/${encodeURIComponent(props.task.id)}/assistant`, signal),
    ),
})
const capabilities = useQuery({
  queryKey: computed(() => ['tasks', 'assistant-capabilities', props.task.id]),
  queryFn: async ({ signal }) =>
    AssistantCapabilities.parse(
      await api.get(
        `/v1/tasks/assistant/capabilities?task_id=${encodeURIComponent(props.task.id)}`,
        signal,
      ),
    ),
})
const delegation = computed(() => snapshot.data.value?.delegation ?? null)
const active = computed(() => delegationIsActive(delegation.value))
const canManage = computed(
  () =>
    props.canWrite &&
    (snapshot.data.value?.can_manage ??
      (!delegation.value || delegation.value.user_id === session.me?.id)),
)
const canEditAuthority = computed(
  () => props.canWrite && delegation.value?.user_id === session.me?.id,
)
const editing = ref(false)
const editRevision = ref(0)
const advanced = ref(false)
const defaultForm = () => ({
  instruction: '',
  mode: 'oneoff',
  completion: 'manual',
  interval: '60',
  checks: ['has_result', 'all_subtasks_done', 'all_checklist_done', 'no_pending_questions'],
  tools: [] as string[],
  actions: [] as string[],
  executors: [] as string[],
  resources: [] as string[],
  customGrants: false,
})
const draft = useDraft(
  computed(() => `task-delegation:${props.task.id}`),
  defaultForm,
)
const form = computed(() => draft.value.value)
const completionChecks = [
  'has_result',
  'all_subtasks_done',
  'all_checklist_done',
  'no_pending_questions',
] as const
watch(
  () => form.value.completion,
  (mode) => {
    if (mode === 'auto' && (!Array.isArray(form.value.checks) || !form.value.checks.length))
      form.value.checks = [...completionChecks]
  },
)
function toggleCheck(check: string, checked: boolean) {
  form.value.checks = checked
    ? [...new Set([...form.value.checks, check])]
    : form.value.checks.filter((item) => item !== check)
}
const busy = ref(false)
const responses = useDraft(
  computed(() => `task-responses:${props.task.id}`),
  () => ({ answers: {} as Record<string, string>, revisions: {} as Record<string, string> }),
)
const answers = computed(() => responses.value.value.answers)
const revisions = computed(() => responses.value.value.revisions)
const reviseId = ref<string | null>(null)
const statusText = (status: string) =>
  te(`tasks.agent.statuses.${status}`) ? t(`tasks.agent.statuses.${status}`) : status
function beginEdit() {
  const item = delegation.value
  if (!item) return
  draft.value.value = {
    instruction: item.instruction,
    mode: item.mode,
    completion: item.completion_mode,
    interval: String(item.interval_minutes ?? 60),
    checks: [...(item.completion_criteria?.required_checks ?? [])],
    tools: [...item.grants.tools],
    actions: [...item.grants.actions],
    executors: [...item.grants.executors],
    resources: [...item.grants.resources],
    customGrants: false,
  }
  editRevision.value = item.revision
  editing.value = true
}
function customize() {
  if (!form.value.customGrants) {
    const grants = delegation.value?.grants ?? capabilities.data.value?.default_grants
    form.value.tools = [...(grants?.tools ?? [])]
    form.value.actions = [...(grants?.actions ?? [])]
    form.value.executors = [...(grants?.executors ?? [])]
    form.value.resources = [
      ...(grants?.resources ?? (props.task.list_id ? [props.task.list_id] : [])),
    ]
    form.value.customGrants = true
  }
  advanced.value = !advanced.value
}
function selectGrant(
  key: 'tools' | 'actions' | 'executors' | 'resources',
  id: string,
  checked: boolean,
) {
  form.value[key] = checked
    ? [...new Set([...form.value[key], id])]
    : form.value[key].filter((value) => value !== id)
  if (key === 'executors' && checked) {
    for (const permission of ['tools', 'actions'] as const)
      if (capabilities.data.value?.[permission].some((item) => item.id === 'workers.run'))
        form.value[permission] = [...new Set([...form.value[permission], 'workers.run'])]
  }
}
async function operate(operation: Operation) {
  if (busy.value) return false
  busy.value = true
  try {
    const response = await completion.operate(operation)
    if (response === null) return false
    toast.success(t('tasks.saved'))
    await snapshot.refetch()
    return true
  } catch (error) {
    toast.error(errorText(error, t))
    return false
  } finally {
    busy.value = false
  }
}
async function save() {
  if (
    !form.value.instruction.trim() ||
    !props.canWrite ||
    (editing.value && !canEditAuthority.value)
  )
    return
  const fields: Record<string, unknown> = {
    instruction: form.value.instruction.trim(),
    mode: form.value.mode,
    completion_mode: form.value.completion,
    interval_minutes: Number(form.value.interval),
    completion_criteria: {
      required_checks: form.value.completion === 'auto' ? form.value.checks : [],
    },
  }
  if (form.value.customGrants)
    fields.grants = {
      tools: form.value.tools,
      actions: form.value.actions,
      executors: form.value.executors,
      resources: [
        ...new Set([...form.value.resources, ...(props.task.list_id ? [props.task.list_id] : [])]),
      ],
    }
  const item = delegation.value
  const operation: Operation =
    editing.value && item
      ? {
          kind: 'delegation.update',
          payload: { delegation_id: item.id, changes: fields },
          expected_revisions: { [item.id]: editRevision.value },
        }
      : {
          kind: 'delegation.create',
          payload: { task_id: props.task.id, ...fields },
          expected_revisions: { [props.task.id]: props.task.revision },
        }
  if (await operate(operation)) {
    editing.value = false
    advanced.value = false
    await draft.clear()
    draft.value.value = defaultForm()
  }
}
async function control(action: string) {
  const item = delegation.value
  if (item)
    await operate({
      kind: `delegation.${action}`,
      payload: { delegation_id: item.id },
      expected_revisions: { [item.id]: item.revision },
    })
}
async function answer(id: string, revision: number) {
  const answer = answers.value[id]?.trim()
  if (
    answer &&
    (await operate({
      kind: 'question.answer',
      payload: { question_id: id, answer },
      expected_revisions: { [id]: revision },
    }))
  )
    delete answers.value[id]
}
async function decide(id: string, revision: number, decision: 'approve' | 'decline') {
  await operate({
    kind: 'question.answer',
    payload: { question_id: id, decision },
    expected_revisions: { [id]: revision },
  })
}
async function accept(id: string, revision: number, complete: boolean) {
  await operate({
    kind: 'result.accept',
    payload: { result_id: id, complete_task: complete },
    expected_revisions: {
      [id]: revision,
      ...(complete ? { [props.task.id]: props.task.revision } : {}),
    },
  })
}
async function revise(id: string, revision: number) {
  const instruction = revisions.value[id]?.trim()
  if (
    instruction &&
    (await operate({
      kind: 'result.revise',
      payload: { result_id: id, instruction },
      expected_revisions: { [id]: revision },
    }))
  ) {
    delete revisions.value[id]
    reviseId.value = null
  }
}
const executorPermissionsMissing = computed(
  () =>
    form.value.customGrants &&
    form.value.executors.length > 0 &&
    (!form.value.tools.includes('workers.run') || !form.value.actions.includes('workers.run')),
)
const pendingQuestions = computed(
  () =>
    snapshot.data.value?.questions.filter((item) =>
      ['open', 'pending', 'waiting'].includes(item.status),
    ) ?? [],
)
</script>
<template>
  <section class="space-y-4" data-testid="task-assistant">
    <RefreshFailed :query="[snapshot, capabilities]" />
    <LoadingState v-if="snapshot.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="snapshot.isLoadingError.value"
      :error="snapshot.error.value"
      @retry="snapshot.refetch()"
    />
    <template v-else>
      <p v-if="capabilities.data.value?.enabled === false" class="text-muted-foreground text-sm">
        {{ t('tasks.agent.disabled') }}
      </p>
      <ErrorState
        v-if="capabilities.isLoadingError.value"
        :error="capabilities.error.value"
        @retry="capabilities.refetch()"
      />
      <article v-if="delegation" class="bg-muted/40 space-y-3 rounded-2xl p-3">
        <div class="flex flex-wrap gap-2">
          <Badge variant="secondary">{{ statusText(delegation.status) }}</Badge
          ><Badge variant="outline">{{ t(`tasks.agent.modes.${delegation.mode}`) }}</Badge>
        </div>
        <p class="break-words text-sm whitespace-pre-wrap">{{ delegation.instruction }}</p>
        <p class="text-muted-foreground text-xs">
          {{ t(`tasks.agent.completions.${delegation.completion_mode}`) }}
        </p>
        <p v-if="delegation.mode === 'monitor'" class="text-muted-foreground text-xs">
          {{ t('tasks.agent.monitorInfo', { minutes: delegation.interval_minutes ?? 60 }) }}
        </p>
        <p v-if="delegation.budget" class="text-muted-foreground text-xs">
          {{
            t('tasks.agent.budget', {
              minutes: Math.ceil((delegation.budget.active_seconds ?? 300) / 60),
              calls: delegation.budget.tool_calls ?? 20,
              runs: delegation.budget.daily_runs ?? 48,
            })
          }}
        </p>
        <p v-if="delegation.next_run_at" class="text-muted-foreground text-xs">
          {{
            t('tasks.agent.nextRun', {
              time: new Date(delegation.next_run_at).toLocaleString('ru-RU', {
                timeZone: session.me?.timezone ?? 'UTC',
              }),
            })
          }}
        </p>
        <div v-if="canManage && active" class="flex flex-wrap gap-2">
          <Button
            v-if="canEditAuthority"
            size="sm"
            variant="outline"
            :disabled="busy || capabilities.data.value?.enabled !== true"
            @click="beginEdit"
            >{{ t('tasks.agent.edit') }}</Button
          ><Button
            v-if="delegation.status !== 'paused' || canEditAuthority"
            size="sm"
            variant="outline"
            :disabled="busy"
            @click="control(delegation.status === 'paused' ? 'resume' : 'pause')"
            >{{
              t(delegation.status === 'paused' ? 'tasks.agent.resume' : 'tasks.agent.pause')
            }}</Button
          ><Button
            size="sm"
            variant="ghost"
            class="text-destructive"
            :disabled="busy"
            @click="control('revoke')"
            >{{ t('tasks.agent.revoke') }}</Button
          >
        </div>
      </article>
      <form
        v-if="
          canWrite &&
          task.status !== 'done' &&
          (!active || (editing && canEditAuthority)) &&
          capabilities.data.value?.enabled === true
        "
        class="space-y-3"
        @submit.prevent="save"
      >
        <h3 class="font-medium">{{ t(editing ? 'tasks.agent.edit' : 'tasks.agent.delegate') }}</h3>
        <p class="text-muted-foreground text-sm">{{ t('tasks.agent.explanation') }}</p>
        <div class="space-y-2">
          <Label for="delegation-instruction">{{ t('tasks.agent.instruction') }}</Label
          ><Textarea
            id="delegation-instruction"
            v-model="form.instruction"
            maxlength="10000"
            rows="4"
            :placeholder="t('tasks.agent.instructionPlaceholder')"
          />
        </div>
        <NativeSelect v-model="form.mode" :aria-label="t('tasks.agent.mode')"
          ><option v-for="mode in ['oneoff', 'monitor']" :key="mode" :value="mode">
            {{ t(`tasks.agent.modes.${mode}`) }}
          </option></NativeSelect
        >
        <div v-if="form.mode === 'monitor'" class="space-y-2">
          <Label for="delegation-interval">{{ t('tasks.agent.interval') }}</Label
          ><Input
            id="delegation-interval"
            v-model="form.interval"
            type="number"
            min="5"
            max="10080"
          />
          <p class="text-muted-foreground text-sm">{{ t('tasks.agent.monitorHint') }}</p>
        </div>
        <NativeSelect v-model="form.completion" :aria-label="t('tasks.agent.completion')"
          ><option value="manual">{{ t('tasks.agent.completions.manual') }}</option>
          <option value="auto">{{ t('tasks.agent.completions.auto') }}</option></NativeSelect
        >
        <div v-if="form.completion === 'auto'" class="space-y-2">
          <p class="text-sm font-medium">{{ t('tasks.agent.requiredChecks') }}</p>
          <label
            v-for="check in completionChecks"
            :key="check"
            class="flex items-start gap-2 text-sm"
            ><Checkbox
              :model-value="form.checks.includes(check)"
              @update:model-value="toggleCheck(check, $event === true)"
            />{{ t(`tasks.agent.checks.${check}`) }}</label
          >
          <p class="text-muted-foreground text-sm">{{ t('tasks.agent.autoHint') }}</p>
        </div>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          :disabled="!capabilities.data.value"
          @click="customize"
          >{{ t('tasks.agent.permissions') }}</Button
        >
        <div v-if="advanced && capabilities.data.value" class="space-y-3 rounded-xl border p-3">
          <p class="text-muted-foreground text-sm">{{ t('tasks.agent.permissionsHint') }}</p>
          <section
            v-for="kind in ['tools', 'actions', 'resources', 'executors'] as const"
            :key="kind"
            class="space-y-2"
          >
            <h4 class="text-sm font-medium">{{ t(`tasks.agent.grantKinds.${kind}`) }}</h4>
            <p v-if="!capabilities.data.value[kind].length" class="text-muted-foreground text-sm">
              {{ t('tasks.agent.noChoices') }}
            </p>
            <label
              v-for="choice in capabilities.data.value[kind]"
              :key="choice.id"
              class="flex items-start gap-2 text-sm"
              ><Checkbox
                :model-value="
                  form[kind].includes(choice.id) ||
                  (kind === 'resources' && choice.id === task.list_id)
                "
                :disabled="kind === 'resources' && choice.id === task.list_id"
                @update:model-value="selectGrant(kind, choice.id, $event === true)"
              /><span
                >{{ choice.title ?? choice.id
                }}<span v-if="choice.description" class="text-muted-foreground block text-xs">{{
                  choice.description
                }}</span></span
              ></label
            >
          </section>
        </div>
        <p v-if="executorPermissionsMissing" class="text-warning text-sm">
          {{ t('tasks.agent.workerPermissionHint') }}
        </p>
        <div class="flex gap-2">
          <Button
            type="submit"
            :disabled="
              busy ||
              !form.instruction.trim() ||
              executorPermissionsMissing ||
              (form.completion === 'auto' && !form.checks.length) ||
              (form.mode === 'monitor' &&
                (!Number.isInteger(Number(form.interval)) || Number(form.interval) < 5))
            "
            >{{ t(editing ? 'common.save' : 'tasks.agent.start') }}</Button
          ><Button v-if="editing" type="button" variant="ghost" @click="editing = false">{{
            t('common.cancel')
          }}</Button>
        </div>
      </form>
      <section
        v-for="question in pendingQuestions"
        :key="question.id"
        class="bg-warning-surface space-y-2 rounded-xl p-3"
      >
        <h3 class="text-sm font-medium">{{ t('tasks.agent.question') }}</h3>
        <Badge v-if="question.audience_private" variant="outline">{{
          t('tasks.agent.private')
        }}</Badge>
        <p class="text-sm whitespace-pre-wrap">{{ question.text }}</p>
        <div v-if="canManage && question.kind === 'proposal'" class="flex flex-wrap gap-2">
          <Button
            type="button"
            size="sm"
            :disabled="busy"
            @click="decide(question.id, question.revision, 'approve')"
            >{{ t('tasks.agent.approve') }}</Button
          ><Button
            type="button"
            size="sm"
            variant="outline"
            :disabled="busy"
            @click="decide(question.id, question.revision, 'decline')"
            >{{ t('tasks.agent.decline') }}</Button
          >
        </div>
        <form
          v-else-if="canManage"
          class="space-y-2"
          @submit.prevent="answer(question.id, question.revision)"
        >
          <Textarea
            v-model="answers[question.id]"
            maxlength="10000"
            :aria-label="t('tasks.agent.answer')"
          /><Button type="submit" size="sm" :disabled="busy || !answers[question.id]?.trim()">{{
            t('tasks.agent.answer')
          }}</Button>
        </form>
      </section>
      <section v-if="snapshot.data.value?.results.length" class="space-y-3">
        <h3 class="font-medium">{{ t('tasks.agent.results') }}</h3>
        <article
          v-for="result in snapshot.data.value.results"
          :key="result.id"
          class="space-y-3 rounded-xl border p-3"
        >
          <Badge variant="outline">{{
            statusText(result.status === 'pending' ? 'waiting_review' : result.status)
          }}</Badge>
          <Badge v-if="result.audience_private" variant="secondary">{{
            t('tasks.agent.private')
          }}</Badge>
          <p class="break-words text-sm whitespace-pre-wrap">{{ result.body }}</p>
          <ul v-if="result.sources.length" class="space-y-1 text-sm">
            <li v-for="(source, index) in result.sources" :key="index">
              <a
                v-if="safeSource(source.url)"
                :href="safeSource(source.url)!"
                target="_blank"
                rel="noopener noreferrer"
                class="text-primary break-words underline"
                >{{ source.title || source.url }}</a
              >
            </li>
          </ul>
          <div
            v-if="
              canManage &&
              ['ready', 'pending', 'proposed', 'review', 'pending_review'].includes(result.status)
            "
            class="space-y-2"
          >
            <div class="flex flex-wrap gap-2">
              <Button
                size="sm"
                :disabled="busy"
                @click="accept(result.id, result.revision, false)"
                >{{ t('tasks.agent.accept') }}</Button
              ><Button
                v-if="task.status !== 'done'"
                size="sm"
                variant="outline"
                :disabled="busy"
                @click="accept(result.id, result.revision, true)"
                >{{ t('tasks.agent.acceptComplete') }}</Button
              ><Button
                v-if="canEditAuthority"
                size="sm"
                variant="ghost"
                :disabled="busy || capabilities.data.value?.enabled !== true"
                @click="reviseId = result.id"
                >{{ t('tasks.agent.revise') }}</Button
              >
            </div>
            <form
              v-if="reviseId === result.id"
              class="space-y-2"
              @submit.prevent="revise(result.id, result.revision)"
            >
              <Textarea
                v-model="revisions[result.id]"
                maxlength="10000"
                :aria-label="t('tasks.agent.revisionInstruction')"
              /><Button size="sm" type="submit" :disabled="busy || !revisions[result.id]?.trim()">{{
                t('tasks.agent.continue')
              }}</Button>
            </form>
          </div>
        </article>
      </section>
      <details v-if="snapshot.data.value?.runs.length" class="space-y-2">
        <summary class="cursor-pointer text-sm font-medium">{{ t('tasks.agent.history') }}</summary>
        <ol class="space-y-2">
          <li
            v-for="run in snapshot.data.value.runs"
            :key="run.id"
            class="rounded-xl border p-3 text-sm"
          >
            <p>
              {{ statusText(run.status)
              }}<span v-if="run.created_at" class="text-muted-foreground">
                ·
                {{
                  new Date(run.created_at).toLocaleString('ru-RU', {
                    timeZone: session.me?.timezone ?? 'UTC',
                  })
                }}</span
              >
            </p>
            <p v-if="run.summary" class="mt-1 whitespace-pre-wrap">{{ run.summary }}</p>
            <p v-if="run.error" class="text-destructive mt-1">{{ t('tasks.agent.runError') }}</p>
          </li>
        </ol>
      </details>
    </template>
  </section>
  <TaskCompletionDialog :completion="completion" @inspect="emit('inspect', $event)" />
</template>
