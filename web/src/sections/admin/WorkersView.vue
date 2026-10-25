<script setup lang="ts">
import { Bot, ChevronRight, Plus, Server } from '@lucide/vue'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { FINISHED, executorLabel, harnessLabel, online, type Profile, type Setup } from './api'
import { notInstalled, useWorkers } from './useAdmin'
import { useWorkerOps } from './useWorkerOps'
import WorkerGrants from './WorkerGrants.vue'

/**
 * M08/M09/M14/M21: work on connected machines. A run's state, a question for the user and a
 * finished result are told apart; the machine, its workspace and its rights are shown separately.
 */
const { t, te } = useI18n()
const router = useRouter()
const workers = useWorkers()
const ops = useWorkerOps()
const data = computed(() => workers.data.value)
const label = (group: string, value: string | null | undefined) =>
  value && te(`admin.${group}.${value}`) ? t(`admin.${group}.${value}`) : (value ?? '')
function setupProblem(item: Setup) {
  const key = `admin.setupReason.${item.settings.reason}`
  if (te(key)) return t(key, { provider: harnessLabel(item.harness) })
  return t('admin.setupFailedText')
}

// M09: the goal, the executor and the time limit are visible before the start.
const creating = ref(false)
const run = reactive({ goal: '', executor: '', reserve: '', hours: '4' })
function startRun() {
  Object.assign(run, {
    goal: '',
    executor: data.value?.executors[0]?.id ?? '',
    reserve: '',
    hours: '4',
  })
  creating.value = true
}
async function create() {
  const hours = Number(run.hours.replace(',', '.'))
  if (!run.goal.trim() || !run.executor || !(hours >= 0.1 && hours <= 24))
    return void toast.error(t('admin.runInvalid'))
  const ids =
    run.reserve && run.reserve !== run.executor ? [run.executor, run.reserve] : [run.executor]
  if (
    await ops.send(
      'run.create',
      { goal: run.goal.trim(), executor_ids: ids, rotation: ids.length > 1, hours },
      {},
      t('admin.runStarted'),
    )
  )
    creating.value = false
}

// M14: machines; the private configuration is installed on the machine itself.
const machineName = ref('')
async function addMachine() {
  const name = machineName.value.trim()
  if (name && (await ops.send('machine.create', { label: name }, {}, t('admin.machineAdded'))))
    machineName.value = ''
}
const setup = reactive({ machine: '', binding: '', workspace: '', harness: 'codex' })
const withWorkspaces = computed(
  () => data.value?.machines.filter((machine) => machine.inventory.workspaces.length) ?? [],
)
const workspaces = computed(
  () =>
    withWorkspaces.value.find((machine) => machine.id === setup.machine)?.inventory.workspaces ??
    [],
)
watch(workspaces, (available) => {
  if (!available.includes(setup.workspace)) setup.workspace = ''
})
async function startSetup() {
  if (!setup.machine || !setup.binding.trim() || !setup.workspace) return
  await ops.send(
    'setup.start',
    {
      machine_id: setup.machine,
      harness: setup.harness,
      binding: setup.binding.trim(),
      workspace: setup.workspace,
    },
    {},
    t('admin.setupStarted'),
  )
}

// M21: a profile's model and rights.
const profile = reactive({ id: '', model: '', permissions: 'read_only' as Profile['permissions'] })
const profileOpen = ref(false)
function editProfile(item: Profile) {
  Object.assign(profile, { id: item.id, model: item.model, permissions: item.permissions })
  profileOpen.value = true
}
async function saveProfile() {
  const item = data.value?.profiles.find((candidate) => candidate.id === profile.id)
  if (!item) return
  if (
    await ops.send(
      'profile.save',
      {
        id: item.id,
        label: item.label,
        harness: item.harness,
        workspace: item.workspace,
        model: profile.model.trim(),
        permissions: profile.permissions,
      },
      { [item.id]: item.revision },
    )
  )
    profileOpen.value = false
}
</script>

<template>
  <PageHeader :title="t('admin.workers')" :back="{ name: 'admin' }">
    <template #actions>
      <Button
        v-if="data?.executors.length"
        variant="ghost"
        size="icon"
        :aria-label="t('admin.newRun')"
        @click="startRun"
      >
        <Plus class="size-5" />
      </Button>
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <UnresolvedWrites
      :entries="ops.unresolved.value"
      :describe="ops.describe"
      :check="ops.check"
      :resend="ops.resend"
      :abandon="ops.abandon"
    />
    <RefreshFailed :query="workers" />
    <LoadingState v-if="workers.isPending.value" :rows="3" />
    <EmptyState
      v-else-if="workers.isLoadingError.value && notInstalled(workers.error.value)"
      :icon="Bot"
      :title="t('admin.workersUnavailable')"
      :description="t('admin.workersUnavailableText')"
      data-testid="workers-unavailable"
    />
    <ErrorState
      v-else-if="workers.isLoadingError.value"
      :error="workers.error.value"
      @retry="workers.refetch()"
    />
    <template v-else-if="data">
      <WorkerGrants
        v-if="data.executors.length"
        :snapshot="data"
        :busy="ops.busy.value"
        @grant="(payload, expected) => ops.send('executor.grant', payload, expected)"
        @revoke="(payload, expected) => ops.send('executor.revoke', payload, expected)"
      />
      <section class="space-y-2" aria-labelledby="runs-title">
        <h2 id="runs-title" class="font-semibold">{{ t('admin.runs') }}</h2>
        <p v-if="!data.runs.length" class="text-muted-foreground text-sm">
          {{ t('admin.noRuns') }}
        </p>
        <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="runs">
          <li v-for="item in data.runs" :key="item.id">
            <button
              type="button"
              class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left"
              @click="router.push({ name: 'workers-run', params: { run: item.id } })"
            >
              <span class="min-w-0 flex-1">
                <span class="block truncate font-medium">{{ item.goal }}</span>
                <span class="text-muted-foreground block text-sm">
                  {{
                    item.stop_requested && !FINISHED.has(item.status)
                      ? t('admin.stopRequested')
                      : label('reason', item.reason) || label('runState', item.status)
                  }}
                </span>
              </span>
              <Badge
                :variant="
                  item.status === 'waiting_input'
                    ? 'warning'
                    : item.status === 'ready'
                      ? 'success'
                      : item.status === 'failed'
                        ? 'destructive'
                        : 'secondary'
                "
              >
                {{ label('runState', item.status) }}
              </Badge>
              <ChevronRight class="text-muted-foreground size-4" />
            </button>
          </li>
        </ul>
        <Button v-if="data.executors.length" @click="startRun"
          ><Plus />{{ t('admin.newRun') }}</Button
        >
      </section>

      <section class="space-y-2" aria-labelledby="machines-title">
        <h2 id="machines-title" class="font-semibold">{{ t('admin.machines') }}</h2>
        <ul
          v-if="data.machines.length"
          class="bg-card divide-y rounded-3xl border"
          data-testid="machines"
        >
          <li
            v-for="machine in data.machines"
            :key="machine.id"
            class="flex items-center gap-3 px-4 py-3"
          >
            <Server class="text-muted-foreground size-5 shrink-0" />
            <div class="min-w-0 flex-1">
              <p class="font-medium">{{ machine.label }}</p>
              <p class="text-muted-foreground text-sm">
                {{ online(machine) ? t('admin.online') : t('admin.offline') }} ·
                {{
                  t(
                    'admin.workspaces',
                    { count: machine.inventory.workspaces.length },
                    machine.inventory.workspaces.length,
                  )
                }}
              </p>
            </div>
            <Button
              size="sm"
              variant="ghost"
              :disabled="ops.busy.value"
              @click="
                ops.send(
                  'machine.update',
                  { id: machine.id, enabled: !machine.enabled },
                  { [machine.id]: machine.revision },
                )
              "
            >
              {{ machine.enabled ? t('admin.stopNewRuns') : t('admin.turnOn') }}
            </Button>
          </li>
        </ul>
        <form class="flex gap-2" novalidate @submit.prevent="addMachine">
          <Input
            v-model="machineName"
            maxlength="100"
            :placeholder="t('admin.machineName')"
            :aria-label="t('admin.machineName')"
          />
          <Button
            type="submit"
            variant="outline"
            :disabled="!machineName.trim() || ops.busy.value"
            >{{ t('admin.addMachine') }}</Button
          >
        </form>
        <Alert>
          <AlertTitle>{{ t('admin.firstConnect') }}</AlertTitle>
          <AlertDescription>{{ t('admin.firstConnectText') }}</AlertDescription>
        </Alert>
      </section>

      <section
        v-if="withWorkspaces.length || data.setups.length"
        class="space-y-2"
        aria-labelledby="setups-title"
      >
        <h2 id="setups-title" class="font-semibold">{{ t('admin.setups') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('admin.paseoSetupText') }}</p>
        <form
          v-if="withWorkspaces.length"
          class="grid gap-2 sm:grid-cols-2"
          data-testid="worker-setup"
          novalidate
          @submit.prevent="startSetup"
        >
          <NativeSelect v-model="setup.machine" :aria-label="t('admin.machine')">
            <option value="" disabled>{{ t('admin.machine') }}</option>
            <option v-for="machine in withWorkspaces" :key="machine.id" :value="machine.id">
              {{ machine.label }}
            </option>
          </NativeSelect>
          <NativeSelect v-model="setup.workspace" :aria-label="t('admin.workspace')">
            <option value="" disabled>{{ t('admin.workspace') }}</option>
            <option v-for="workspace in workspaces" :key="workspace" :value="workspace">
              {{ workspace }}
            </option>
          </NativeSelect>
          <NativeSelect v-model="setup.harness" :aria-label="t('admin.workerHarness')">
            <option value="codex">Codex</option>
            <option value="claude">Claude</option>
          </NativeSelect>
          <Input
            v-model="setup.binding"
            maxlength="60"
            :placeholder="t('admin.binding')"
            :aria-label="t('admin.binding')"
          />
          <Button
            type="submit"
            class="sm:col-span-2"
            variant="outline"
            :disabled="
              !setup.machine || !setup.binding.trim() || !setup.workspace || ops.busy.value
            "
            >{{ t('admin.verifyWorker') }}</Button
          >
        </form>
        <ul v-if="data.setups.length" class="bg-card divide-y rounded-3xl border">
          <li v-for="item in data.setups" :key="item.id" class="space-y-2 px-4 py-3">
            <p class="text-sm">
              <span class="font-medium">{{ item.binding }}</span> ·
              {{ harnessLabel(item.harness) }} · {{ label('setupStep', item.step) || item.step }} ·
              {{ label('setupState', item.state) || item.state }}
            </p>
            <p
              v-if="item.settings.reason || item.state === 'failed'"
              class="text-muted-foreground text-sm"
              data-testid="setup-problem"
            >
              {{ setupProblem(item) }}
            </p>
            <div class="flex flex-wrap gap-2">
              <Button
                v-if="item.state === 'ready'"
                size="sm"
                variant="outline"
                :disabled="ops.busy.value"
                @click="
                  ops.send('setup.refresh', { setup_id: item.id }, { [item.id]: item.revision })
                "
                >{{ t('admin.verifyAgain') }}</Button
              >
              <Button
                v-if="!FINISHED.has(item.state)"
                size="sm"
                variant="ghost"
                :disabled="ops.busy.value"
                @click="
                  ops.send('setup.cancel', { setup_id: item.id }, { [item.id]: item.revision })
                "
                >{{ t('admin.cancelSetup') }}</Button
              >
            </div>
            <p v-if="item.state === 'waiting_user'" class="text-muted-foreground text-sm">
              {{ t('admin.nativeWorkerSignIn', { provider: harnessLabel(item.harness) }) }}
            </p>
          </li>
        </ul>
      </section>

      <section v-if="data.executors.length" class="space-y-2" aria-labelledby="executors-title">
        <h2 id="executors-title" class="font-semibold">{{ t('admin.executors') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border" data-testid="executors">
          <li v-for="executor in data.executors" :key="executor.id" class="space-y-2 px-4 py-3">
            <p class="font-medium">{{ executorLabel(data, executor) }}</p>
            <div class="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="outline"
                :disabled="ops.busy.value"
                @click="
                  ops.send(
                    'executor.update',
                    {
                      id: executor.id,
                      enabled: !executor.enabled,
                      allow_agent_dispatch: executor.allow_agent_dispatch,
                    },
                    { [executor.id]: executor.revision },
                  )
                "
              >
                {{ executor.enabled ? t('admin.turnOff') : t('admin.turnOn') }}
              </Button>
              <Button
                size="sm"
                variant="ghost"
                :disabled="ops.busy.value"
                @click="
                  ops.send(
                    'executor.update',
                    {
                      id: executor.id,
                      enabled: executor.enabled,
                      allow_agent_dispatch: !executor.allow_agent_dispatch,
                    },
                    { [executor.id]: executor.revision },
                  )
                "
              >
                {{ executor.allow_agent_dispatch ? t('admin.forbidAgent') : t('admin.allowAgent') }}
              </Button>
            </div>
          </li>
        </ul>
      </section>

      <section v-if="data.profiles.length" class="space-y-2" aria-labelledby="profiles-title">
        <h2 id="profiles-title" class="font-semibold">{{ t('admin.profiles') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border">
          <li v-for="item in data.profiles" :key="item.id">
            <button
              type="button"
              class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3 text-left"
              @click="editProfile(item)"
            >
              <span class="min-w-0 flex-1">
                <span class="block font-medium">{{ item.label }}</span>
                <span class="text-muted-foreground block text-sm"
                  >{{ harnessLabel(item.harness) }} · {{ item.workspace }} ·
                  {{ t(`admin.permissions.${item.permissions}`) }} · {{ item.model }}</span
                >
              </span>
              <ChevronRight class="text-muted-foreground size-4" />
            </button>
          </li>
        </ul>
      </section>
    </template>
  </div>

  <Panel v-model:open="creating" :title="t('admin.newRun')">
    <form v-if="data" class="space-y-4" novalidate @submit.prevent="create">
      <div class="space-y-2">
        <Label for="run-goal">{{ t('admin.runGoal') }}</Label>
        <Textarea id="run-goal" v-model="run.goal" maxlength="10000" rows="3" />
      </div>
      <div class="space-y-2">
        <Label for="run-executor">{{ t('admin.executor') }}</Label>
        <NativeSelect id="run-executor" v-model="run.executor">
          <option v-for="executor in data.executors" :key="executor.id" :value="executor.id">
            {{ executorLabel(data, executor) }}
          </option>
        </NativeSelect>
      </div>
      <div class="space-y-2">
        <Label for="run-reserve">{{ t('admin.reserveExecutor') }}</Label>
        <NativeSelect id="run-reserve" v-model="run.reserve">
          <option value="">{{ t('admin.noRotation') }}</option>
          <option v-for="executor in data.executors" :key="executor.id" :value="executor.id">
            {{ executorLabel(data, executor) }}
          </option>
        </NativeSelect>
      </div>
      <div class="space-y-2">
        <Label for="run-hours">{{ t('admin.hours') }}</Label>
        <Input id="run-hours" v-model="run.hours" inputmode="decimal" />
      </div>
      <Button type="submit" size="lg" class="w-full" :disabled="ops.busy.value">{{
        t('admin.start')
      }}</Button>
    </form>
  </Panel>

  <Panel
    v-model:open="profileOpen"
    :title="data?.profiles.find((item) => item.id === profile.id)?.label ?? ''"
  >
    <form class="space-y-4" novalidate @submit.prevent="saveProfile">
      <div class="space-y-2">
        <Label for="profile-model">{{ t('admin.model') }}</Label>
        <Input id="profile-model" v-model="profile.model" maxlength="100" />
      </div>
      <div class="space-y-2">
        <Label for="profile-permissions">{{ t('admin.permissionsTitle') }}</Label>
        <NativeSelect id="profile-permissions" v-model="profile.permissions">
          <option value="read_only">{{ t('admin.permissions.read_only') }}</option>
          <option value="workspace_write">{{ t('admin.permissions.workspace_write') }}</option>
        </NativeSelect>
      </div>
      <p class="text-muted-foreground text-sm">{{ t('admin.permissionsHint') }}</p>
      <Button
        type="submit"
        size="lg"
        class="w-full"
        :disabled="!profile.model.trim() || ops.busy.value"
        >{{ t('common.save') }}</Button
      >
    </form>
  </Panel>
</template>
