<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ListTodo, ShieldCheck } from '@lucide/vue'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import HeaderAction from '@/components/HeaderAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { errorText } from '@/core/errors'
import { api } from '@/core/client'
import { operationKind, useLedger } from '@/core/operations'
import { dayIn } from '@/core/dates'
import { TASKS, Task } from '@/sections/tasks/api'
import TaskPanel from '@/sections/tasks/TaskPanel.vue'
import TaskCompletionDialog from '@/sections/tasks/TaskCompletionDialog.vue'
import { useTaskCompletion } from '@/sections/tasks/useTaskCompletion'
import { useSession } from '@/stores/session'
import { PERMISSIONS, type Permission, type SharedTask } from './api'
import GoneState from './GoneState.vue'
import {
  isGone,
  useCircleActions,
  useGrants,
  useListTasks,
  useMembers,
  useSpaces,
  useTaskLists,
} from './useCircle'

/**
 * G10/G17/G13: a shared list. Assigning a task does not open the list to anyone; who may view,
 * edit or manage it is set separately, and each level is spelled out.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const listId = computed(() => String(route.params.list ?? ''))
const lists = useTaskLists()
const list = computed(
  () => lists.data.value?.lists.find((item) => item.id === listId.value) ?? null,
)
const spaceId = computed(() => list.value?.space_id ?? null)
const spaces = useSpaces()
const space = computed(
  () => spaces.data.value?.spaces.find((item) => item.id === spaceId.value) ?? null,
)
const members = useMembers(spaceId)
const tasks = useListTasks(listId)
const ledger = useLedger(TASKS)
const completion = useTaskCompletion(ledger.operate)
const actions = useCircleActions()
const editor = computed(
  () => session.can('tasks.write') && ['edit', 'manage'].includes(list.value?.effective ?? ''),
)
const manager = computed(() => list.value?.effective === 'manage')
const gone = computed(() => isGone(tasks.error.value) || (!!lists.data.value && !list.value))
watch([listId, editor], completion.cancel)
const nameOf = (user: string | null | undefined) =>
  user
    ? (members.data.value?.members.find((member) => member.user_id === user)?.name ??
      t('circle.participant'))
    : null

async function write(operation: Parameters<typeof ledger.operate>[0], message: string) {
  try {
    const response = await completion.operate(operation)
    if (response === null) return false
    toast.success(message)
    return true
  } catch (error) {
    toast.error(errorText(error, t))
    return false
  }
}

const title = ref('')
async function add() {
  const text = title.value.trim()
  if (!text) return
  if (
    await write(
      { kind: 'task.create', target_resource_id: listId.value, payload: { title: text } },
      t('tasks.added'),
    )
  )
    title.value = ''
}
const complete = (task: SharedTask) =>
  write(
    {
      kind: 'task.complete',
      payload: { task_id: task.id },
      expected_revisions: { [task.id]: task.revision },
    },
    t('tasks.completed'),
  )
function inspect(task: Task) {
  void router.push({
    name: 'tasks',
    query: { task: task.id, ...(task.list_id ? { list: task.list_id } : { scope: 'all' }) },
  })
}

// G17: title and assignee, limited to members of the space.
const editing = shallowRef<SharedTask | null>(null)
const editingOpen = computed({
  get: () => editing.value !== null,
  set: (value: boolean) => {
    if (!value) editing.value = null
  },
})
function edit(task: Task) {
  editing.value = task
}
const detail = useQuery({
  queryKey: computed(() => ['tasks', 'item', editing.value?.id ?? '']),
  enabled: computed(() => !!editing.value),
  queryFn: async ({ signal }) =>
    Task.parse(await api.get(`/v1/tasks/items/${encodeURIComponent(editing.value!.id)}`, signal)),
})
const selectedTask = computed(
  () =>
    detail.data.value ??
    tasks.data.value?.items.find((item) => item.id === editing.value?.id) ??
    editing.value,
)
const today = computed(() => dayIn(session.me?.timezone ?? 'UTC'))

// G13: rights to this list, separate from roles in the space.
const rights = ref(false)
const grants = useGrants(
  listId,
  computed(() => rights.value && manager.value),
)
const access = reactive({ member: 'edit', user: '', level: 'view' as Permission })
watch(
  () => grants.data.value?.resource.member_access,
  (value) => value && (access.member = value),
  { immediate: true },
)
// Owners and admins manage every list of the space already.
const grantable = computed(
  () => members.data.value?.members.filter((member) => member.role === 'member') ?? [],
)
const revision = () => grants.data.value?.resource.revision ?? list.value?.revision ?? 0
const resource = (tail = '') => `/v1/resources/${encodeURIComponent(listId.value)}${tail}`
const saveDefault = () =>
  actions.act(
    () => actions.post(resource(), { member_access: access.member, revision: revision() }),
    t('circle.accessSaved'),
    ['community', 'tasks'],
  )
async function grant() {
  if (!access.user) return
  const done = await actions.act(
    () =>
      actions.post(resource('/grants'), {
        user_id: access.user,
        permission: access.level,
        revision: revision(),
      }),
    t('circle.accessSaved'),
    ['community', 'tasks'],
  )
  if (done) access.user = ''
}
const revoke = (user: string) =>
  actions.act(
    () =>
      actions.post(resource('/grants'), { user_id: user, permission: null, revision: revision() }),
    t('circle.accessRevoked'),
    ['community', 'tasks'],
  )
const describe = (entry: { body: string }) =>
  t('tasks.unresolved', { kind: operationKind(entry as never) })
</script>

<template>
  <PageHeader
    :title="list?.name ?? t('circle.list')"
    :description="
      space ? `${space.name} · ${list ? t(`circle.effective.${list.effective}`) : ''}` : undefined
    "
    :back="spaceId ? { name: 'circle-space', params: { space: spaceId } } : { name: 'circle' }"
  >
    <template #actions>
      <HeaderAction
        :icon="ListTodo"
        :label="t('tasks.openProject')"
        :to="{ name: 'tasks', query: { list: listId } }"
      />
      <HeaderAction
        v-if="manager"
        :icon="ShieldCheck"
        :label="t('circle.listAccess')"
        @click="rights = true"
      />
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <GoneState v-if="gone" />
    <template v-else>
      <UnresolvedWrites
        :entries="ledger.unresolved.value"
        :describe="describe"
        :check="ledger.check"
        :resend="ledger.resend"
        :abandon="ledger.abandon"
      />
      <form v-if="editor" class="flex gap-2" novalidate @submit.prevent="add">
        <Input
          v-model="title"
          maxlength="300"
          :placeholder="t('circle.newTask')"
          :aria-label="t('circle.newTask')"
        />
        <Button type="submit" :disabled="!title.trim()">{{ t('tasks.add') }}</Button>
      </form>
      <RefreshFailed :query="tasks" />
      <LoadingState v-if="tasks.isPending.value" :rows="3" />
      <ErrorState
        v-else-if="tasks.isLoadingError.value"
        :error="tasks.error.value"
        @retry="tasks.refetch()"
      />
      <EmptyState
        v-else-if="!tasks.data.value?.items.length"
        :icon="ListTodo"
        :title="t('circle.listEmpty')"
        :description="t('circle.listEmptyText')"
      />
      <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="list-tasks">
        <li
          v-for="task in tasks.data.value.items"
          :key="task.id"
          class="flex items-center gap-3 px-4 py-3"
        >
          <Checkbox
            v-if="editor"
            :model-value="false"
            :aria-label="t('tasks.complete')"
            @update:model-value="complete(task)"
          />
          <button
            type="button"
            class="min-w-0 flex-1 text-left disabled:cursor-default"
            @click="edit(task)"
          >
            <span class="block truncate font-medium">{{ task.title }}</span>
            <span class="text-muted-foreground block text-sm">{{
              nameOf(task.assigned_to) ?? t('circle.noAssignee')
            }}</span>
          </button>
        </li>
      </ul>
    </template>
  </div>

  <TaskPanel
    v-model:open="editingOpen"
    :task="selectedTask"
    :today="today"
    :can-write="editor"
    @select="edit"
  />

  <Panel
    v-model:open="rights"
    :title="t('circle.listAccess')"
    :description="space && list ? `${space.name} → ${list.name}` : undefined"
  >
    <RefreshFailed :query="grants" />
    <LoadingState v-if="grants.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="grants.isLoadingError.value"
      :error="grants.error.value"
      @retry="grants.refetch()"
    />
    <template v-else-if="grants.data.value">
      <form class="space-y-2" novalidate @submit.prevent="saveDefault">
        <p class="text-sm font-medium">{{ t('circle.defaultAccess') }}</p>
        <RadioGroup v-model="access.member" class="gap-2">
          <Label
            v-for="option in ['view', 'edit']"
            :key="option"
            class="has-[[data-state=checked]]:border-primary flex items-start gap-3 rounded-2xl border p-3 font-normal"
          >
            <RadioGroupItem :value="option" class="mt-0.5" />
            <span>
              <span class="block font-medium">{{ t(`circle.memberAccess.${option}`) }}</span>
              <span class="text-muted-foreground block text-sm">{{
                t(`circle.level.${option}`)
              }}</span>
            </span>
          </Label>
        </RadioGroup>
        <Button
          type="submit"
          size="sm"
          :disabled="
            access.member === grants.data.value.resource.member_access || actions.busy.value
          "
        >
          {{ t('common.save') }}
        </Button>
      </form>

      <section class="space-y-2 border-t pt-4" aria-labelledby="grants-title">
        <h3 id="grants-title" class="text-sm font-medium">{{ t('circle.personalRights') }}</h3>
        <ul
          v-if="grants.data.value.grants.length"
          class="divide-y rounded-2xl border"
          data-testid="grants"
        >
          <li
            v-for="item in grants.data.value.grants"
            :key="item.user_id"
            class="flex items-center gap-3 px-3 py-2.5"
          >
            <span class="min-w-0 flex-1 truncate">{{ item.name }}</span>
            <Badge variant="secondary">{{ t(`circle.permission.${item.permission}`) }}</Badge>
            <Button
              size="sm"
              variant="ghost"
              class="text-destructive"
              :disabled="actions.busy.value"
              @click="revoke(item.user_id)"
            >
              {{ t('access.revoke') }}
            </Button>
          </li>
        </ul>
        <p v-else class="text-muted-foreground text-sm">{{ t('circle.noGrants') }}</p>
        <form class="grid gap-2 sm:grid-cols-[1fr_auto_auto]" novalidate @submit.prevent="grant">
          <NativeSelect v-model="access.user" :aria-label="t('circle.participant')">
            <option value="" disabled>{{ t('circle.chooseMember') }}</option>
            <option v-for="member in grantable" :key="member.user_id" :value="member.user_id">
              {{ member.name }}
            </option>
          </NativeSelect>
          <NativeSelect v-model="access.level" :aria-label="t('circle.levelTitle')">
            <option v-for="level in PERMISSIONS" :key="level" :value="level">
              {{ t(`circle.permission.${level}`) }}
            </option>
          </NativeSelect>
          <Button type="submit" :disabled="!access.user || actions.busy.value">{{
            t('circle.grant')
          }}</Button>
        </form>
        <p class="text-muted-foreground text-sm">{{ t(`circle.level.${access.level}`) }}</p>
      </section>
    </template>
  </Panel>
  <TaskCompletionDialog :completion="completion" @inspect="inspect" />
</template>
