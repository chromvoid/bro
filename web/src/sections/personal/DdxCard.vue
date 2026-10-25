<script setup lang="ts">
import ddxMessages from '@/locales/personal-ddx-ru.json'
import { History, LoaderCircle, Plug, RefreshCw } from '@lucide/vue'
import { useIntervalFn } from '@vueuse/core'
import { useQueryClient } from '@tanstack/vue-query'
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { api } from '@/core/client'
import { momentLabel } from '@/core/dates'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import type { Source } from './api'
import { ACTIVE_DDX_RUN, DdxRun, waitSeconds, type DdxLoginMode } from './ddx'
import { useDdxLogin } from './useDdxLogin'
import { usePersonal } from './usePersonal'

const props = defineProps<{
  sources: Source[]
  today: string
  timezone: string
  canWrite: boolean
}>()
const { te } = useI18n({ useScope: 'global' })
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: ddxMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const session = useSession()
const queries = useQueryClient()
const personal = usePersonal()
const formOpen = ref(false)
const mode = ref<DdxLoginMode>('connect')
const auth = useDdxLogin(() => {
  formOpen.value = false
  void queries.invalidateQueries({ queryKey: ['personal'] })
})
const {
  phone,
  code,
  retryAt,
  login,
  available,
  canDisconnect,
  busy,
  checking,
  error: loginError,
  uncertain,
  retryStart,
  active: loginActive,
} = auth
const ddxSources = computed(() =>
  props.sources.filter((source) => source.connection?.provider === 'ddx'),
)
const source = computed(
  () => ddxSources.value.find((item) => item.connection?.auth_state !== 'disconnected') ?? null,
)
const connection = computed(() => source.value?.connection ?? null)
const readyMatchesSource = computed(
  () => connection.value?.auth_state === 'active' && login.value?.source_id === source.value?.id,
)
const archived = computed(() =>
  ddxSources.value.filter((item) => item.connection?.auth_state === 'disconnected'),
)
const showForm = computed(
  () => props.canWrite && (formOpen.value || loginActive.value || uncertain.value),
)
const now = ref(Date.now())
useIntervalFn(() => {
  now.value = Date.now()
}, 1000)
const resendWait = computed(() => waitSeconds(retryAt.value, now.value))
const syncWait = computed(() => waitSeconds(connection.value?.retry_at, now.value))
const run = shallowRef<DdxRun | null>(null)
const actionBusy = ref(false)
const actionError = ref<string | null>(null)
const disconnectOpen = ref(false)
const syncActive = computed(() => !!run.value && ACTIVE_DDX_RUN.has(run.value.state))
const outcome = computed(() => (run.value ? run.value.outcome : connection.value?.last_result))
const errorLabel = (value: string) =>
  te(`personal.ddx.error.${value}`)
    ? t(`personal.ddx.error.${value}`)
    : te(`personal.sourceError.${value}`)
      ? t(`personal.sourceError.${value}`)
      : t('personal.ddx.error.request_failed')
const labelTime = (value: string) => momentLabel(value, props.timezone, props.today)
let disposed = false
let readingRun = false

watch(
  () => connection.value?.active_run,
  (next) => {
    if (next) run.value = next
  },
  { immediate: true },
)
watch(
  () => source.value?.id,
  () => {
    run.value = connection.value?.active_run ?? null
    disconnectOpen.value = false
    actionError.value = null
  },
)
watch(
  () => [session.account, props.canWrite] as const,
  () => {
    formOpen.value = false
    phone.value = ''
    code.value = ''
    run.value = null
    actionBusy.value = false
    disconnectOpen.value = false
    actionError.value = null
  },
  { flush: 'sync' },
)
onScopeDispose(() => {
  disposed = true
})

function begin(next: DdxLoginMode) {
  if (!props.canWrite || available.value !== true || loginActive.value || uncertain.value) return
  auth.clear()
  mode.value = next
  formOpen.value = true
}

function dismissForm() {
  formOpen.value = false
  auth.clear()
}

async function cancelLogin() {
  await auth.action('cancel')
  if (login.value?.state === 'cancelled') formOpen.value = false
}

function start() {
  return auth.start(
    mode.value,
    mode.value === 'connect' ? undefined : source.value?.id,
    mode.value === 'connect' ? undefined : connection.value?.revision,
  )
}

async function readRun(id: string) {
  if (readingRun || disposed) return
  const account = session.account
  const sourceId = source.value?.id
  readingRun = true
  try {
    const next = DdxRun.parse(
      await api.get(`/v1/personal/sync-runs/${encodeURIComponent(id)}?wait_seconds=0`),
    )
    if (
      disposed ||
      account !== session.account ||
      source.value?.id !== sourceId ||
      next.source_id !== sourceId
    )
      return
    run.value = next
    if (!ACTIVE_DDX_RUN.has(next.state)) void queries.invalidateQueries({ queryKey: ['personal'] })
  } catch {
    if (!disposed && account === session.account) actionError.value = 'network_error'
  } finally {
    readingRun = false
  }
}
useIntervalFn(() => {
  if (syncActive.value && run.value) void readRun(run.value.id)
}, 2000)

async function sync() {
  if (
    !props.canWrite ||
    !source.value ||
    actionBusy.value ||
    syncActive.value ||
    syncWait.value ||
    connection.value?.auth_state !== 'active' ||
    available.value !== true
  )
    return
  const account = session.account
  actionBusy.value = true
  actionError.value = null
  try {
    const receipt = (await personal.operate('source.sync.request', {
      source_id: source.value.id,
    })) as { run_id?: string }
    if (!disposed && account === session.account && receipt.run_id) await readRun(receipt.run_id)
  } catch (failure) {
    if (!disposed && account === session.account) {
      actionError.value = failure instanceof ApiError ? failure.code : 'network_error'
      personal.failed(failure)
    }
  } finally {
    if (account === session.account) actionBusy.value = false
  }
}

async function automatic(value: boolean) {
  if (
    !props.canWrite ||
    !source.value ||
    !connection.value ||
    actionBusy.value ||
    available.value !== true
  )
    return
  const account = session.account
  actionBusy.value = true
  actionError.value = null
  try {
    await personal.operate(
      'source.automatic.update',
      { source_id: source.value.id, automatic: value },
      { [source.value.id]: connection.value.revision },
    )
  } catch (failure) {
    if (!disposed && account === session.account) {
      actionError.value = failure instanceof ApiError ? failure.code : 'network_error'
      personal.failed(failure)
    }
  } finally {
    if (account === session.account) actionBusy.value = false
  }
}

async function disconnect() {
  if (
    !props.canWrite ||
    !source.value ||
    !connection.value ||
    actionBusy.value ||
    !canDisconnect.value
  )
    return
  const account = session.account
  const userId = session.me?.id
  if (!account || !userId) return
  actionBusy.value = true
  actionError.value = null
  try {
    await api.post(
      `/v1/personal/ddx/connections/${encodeURIComponent(source.value.id)}/disconnect`,
      { expected_revision: connection.value.revision },
      {
        key: crypto.randomUUID(),
        expectedUser: userId,
        beforeSend: () => {
          if (
            disposed ||
            account !== session.account ||
            session.me?.id !== userId ||
            account.store.userId !== userId ||
            !session.can('personal.write')
          )
            throw new DOMException('Account changed', 'AbortError')
        },
      },
    )
    if (!disposed && account === session.account) {
      disconnectOpen.value = false
      void queries.invalidateQueries({ queryKey: ['personal'] })
    }
  } catch (failure) {
    if (!disposed && account === session.account) {
      actionError.value = failure instanceof ApiError ? failure.code : 'network_error'
      void queries.invalidateQueries({ queryKey: ['personal'] })
    }
  } finally {
    if (account === session.account) actionBusy.value = false
  }
}
</script>

<template>
  <section
    class="bg-card space-y-5 rounded-3xl border p-5"
    aria-labelledby="ddx-title"
    data-testid="ddx-card"
  >
    <header class="flex items-start gap-3">
      <Plug class="text-primary mt-1 size-5 shrink-0" />
      <div class="min-w-0 flex-1">
        <h2 id="ddx-title" class="font-semibold">{{ t('personal.ddx.title') }}</h2>
        <p class="text-muted-foreground mt-1 text-sm">{{ t('personal.ddx.intro') }}</p>
      </div>
      <Badge
        v-if="connection"
        :variant="connection.auth_state === 'active' ? 'success' : 'warning'"
        >{{ t(`personal.ddx.connection.${connection.auth_state}`) }}</Badge
      >
    </header>

    <template v-if="connection && source">
      <p v-if="connection.phone_masked" class="text-sm tabular-nums">
        {{ connection.phone_masked }}
      </p>
      <dl class="space-y-1 text-sm">
        <div class="flex flex-wrap justify-between gap-x-3">
          <dt class="text-muted-foreground">{{ t('personal.ddx.lastMeasurement') }}</dt>
          <dd>
            {{
              connection.last_measurement_at
                ? labelTime(connection.last_measurement_at)
                : t('personal.ddx.noMeasurements')
            }}
          </dd>
        </div>
        <div class="flex flex-wrap justify-between gap-x-3">
          <dt class="text-muted-foreground">{{ t('personal.ddx.lastSync') }}</dt>
          <dd>
            {{
              connection.last_success_at
                ? labelTime(connection.last_success_at)
                : t('personal.ddx.notSynced')
            }}
          </dd>
        </div>
        <div
          v-if="connection.automatic && connection.next_sync_at"
          class="flex flex-wrap justify-between gap-x-3"
        >
          <dt class="text-muted-foreground">{{ t('personal.ddx.nextSync') }}</dt>
          <dd>{{ labelTime(connection.next_sync_at) }}</dd>
        </div>
      </dl>
      <p
        v-if="connection.warning === 'history_restricted' || source.error === 'history_restricted'"
        class="text-warning text-sm"
      >
        {{ t('personal.sourceError.history_restricted') }}
      </p>
      <p
        v-if="
          connection.warning === 'projection_incomplete' ||
          source.error === 'projection_incomplete' ||
          outcome?.projection_incomplete
        "
        class="text-warning text-sm"
      >
        {{ t('personal.sourceError.projection_incomplete') }}
      </p>
      <p v-if="connection.error" class="text-warning text-sm">{{ errorLabel(connection.error) }}</p>
      <div class="flex items-center justify-between gap-3 rounded-2xl border p-3">
        <div>
          <Label for="ddx-automatic">{{ t('personal.ddx.automatic') }}</Label>
          <p class="text-muted-foreground mt-1 text-xs">
            {{ t(connection.automatic ? 'personal.ddx.hourly' : 'personal.ddx.manualOnly') }}
          </p>
        </div>
        <Switch
          id="ddx-automatic"
          :model-value="connection.automatic"
          :disabled="!canWrite || actionBusy || available !== true"
          @update:model-value="automatic"
        />
      </div>
      <div class="flex flex-wrap gap-2">
        <Button
          v-if="canWrite && connection.auth_state === 'active'"
          :disabled="actionBusy || syncActive || syncWait > 0 || available !== true"
          @click="sync"
          ><LoaderCircle v-if="syncActive" class="animate-spin" /><RefreshCw v-else />{{
            t(syncActive ? 'personal.ddx.syncing' : 'personal.ddx.syncNow')
          }}</Button
        >
        <Button
          v-if="canWrite && connection.auth_state === 'auth_required' && connection.can_reconnect"
          :disabled="available !== true || loginActive"
          @click="begin('reconnect')"
          >{{ t('personal.ddx.reconnect') }}</Button
        >
        <Button variant="outline" as-child
          ><RouterLink :to="{ name: 'personal-history', query: { kind: 'body', period: 365 } }"
            ><History />{{ t('personal.bodyHistory') }}</RouterLink
          ></Button
        >
      </div>
      <p v-if="syncWait" class="text-muted-foreground text-sm" role="status">
        {{ t('personal.ddx.syncWait', { seconds: syncWait }) }}
      </p>
      <div
        v-if="run || outcome"
        class="text-muted-foreground space-y-1 text-sm"
        role="status"
        aria-live="polite"
      >
        <p v-if="run">{{ t(`personal.ddx.run.${run.state}`) }}</p>
        <p v-if="outcome && (!run || ['succeeded', 'partial'].includes(run.state))">
          {{
            t('personal.ddx.syncCounts', {
              added: outcome.new_analyses,
              updated: outcome.updated_analyses,
            })
          }}
        </p>
        <p v-if="outcome?.error" class="text-warning">{{ errorLabel(outcome.error) }}</p>
      </div>
      <details v-if="canWrite" class="text-sm">
        <summary class="text-primary cursor-pointer">{{ t('personal.ddx.manage') }}</summary>
        <p v-if="!connection.can_reconnect" class="text-muted-foreground mt-2">
          {{ t('personal.ddx.legacyConnection') }}
        </p>
        <div class="mt-3 flex flex-wrap gap-2">
          <Button
            v-if="connection.can_reconnect"
            variant="outline"
            size="sm"
            :disabled="available !== true || loginActive || actionBusy"
            @click="begin('reconnect')"
            >{{ t('personal.ddx.reconnect') }}</Button
          >
          <Button
            variant="outline"
            size="sm"
            :disabled="available !== true || loginActive || actionBusy"
            @click="begin('replace')"
            >{{ t('personal.ddx.changeAccount') }}</Button
          >
          <Button
            variant="ghost"
            size="sm"
            :disabled="actionBusy || loginActive || !canDisconnect"
            @click="disconnectOpen = true"
            >{{ t('personal.ddx.disconnect') }}</Button
          >
        </div>
      </details>
      <div
        v-if="disconnectOpen"
        class="space-y-3 rounded-2xl border p-4"
        role="group"
        :aria-label="t('personal.ddx.disconnect')"
      >
        <p class="text-sm">{{ t('personal.ddx.disconnectHint') }}</p>
        <div class="flex flex-wrap gap-2">
          <Button
            variant="destructive"
            :disabled="actionBusy || !canDisconnect"
            @click="disconnect"
            >{{ t('personal.ddx.disconnect') }}</Button
          ><Button variant="ghost" :disabled="actionBusy" @click="disconnectOpen = false">{{
            t('common.cancel')
          }}</Button>
        </div>
      </div>
    </template>

    <template v-if="canWrite">
      <p v-if="available === false" class="text-muted-foreground text-sm">
        {{ t('personal.ddx.unavailable') }}
      </p>
      <p v-else-if="available === null && checking" class="text-muted-foreground text-sm">
        {{ t('personal.ddx.checking') }}
      </p>
      <Button v-if="!connection && !showForm && available === true" @click="begin('connect')">{{
        t('personal.ddx.connect')
      }}</Button>
      <div v-if="showForm" class="space-y-4 border-t pt-4" aria-live="polite">
        <template v-if="!loginActive && !uncertain">
          <p class="text-sm">
            {{
              t(
                mode === 'reconnect'
                  ? 'personal.ddx.samePhone'
                  : mode === 'replace'
                    ? 'personal.ddx.replaceHint'
                    : 'personal.ddx.connectHint',
              )
            }}
          </p>
          <form class="space-y-3" autocomplete="off" @submit.prevent="start">
            <div class="space-y-1.5">
              <Label for="ddx-phone">{{ t('personal.ddx.phone') }}</Label
              ><Input
                id="ddx-phone"
                v-model="phone"
                type="tel"
                inputmode="tel"
                autocomplete="tel"
                maxlength="32"
                :placeholder="t('personal.ddx.phonePlaceholder')"
                :disabled="busy"
              />
            </div>
            <div class="flex flex-wrap gap-2">
              <Button
                type="submit"
                :disabled="busy || !phone.trim() || available !== true || resendWait > 0"
                >{{
                  resendWait
                    ? t('personal.ddx.resendWait', { seconds: resendWait })
                    : t('personal.ddx.getCode')
                }}</Button
              ><Button type="button" variant="ghost" :disabled="busy" @click="dismissForm">{{
                t('common.cancel')
              }}</Button>
            </div>
          </form>
        </template>
        <template v-if="loginActive && login">
          <p class="font-medium">{{ t(`personal.ddx.login.${login.state}`) }}</p>
          <p v-if="login.phone_masked" class="text-muted-foreground text-sm">
            {{ login.phone_masked }}
          </p>
          <form
            v-if="login.state === 'waiting_code' && !uncertain"
            class="space-y-3"
            autocomplete="off"
            @submit.prevent="auth.submitCode"
          >
            <p class="text-muted-foreground text-sm">{{ t('personal.ddx.codeHint') }}</p>
            <div class="space-y-1.5">
              <Label for="ddx-code">{{ t('personal.ddx.code') }}</Label
              ><Input
                id="ddx-code"
                v-model="code"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                pattern="[0-9]{6}"
                :disabled="busy"
              />
            </div>
            <Button
              type="submit"
              :disabled="busy || !/^\d{6}$/.test(code.trim()) || available !== true"
              >{{ t('personal.ddx.verify') }}</Button
            >
          </form>
          <p v-if="login.expires_at" class="text-muted-foreground text-xs">
            {{ t('personal.ddx.expires', { when: labelTime(login.expires_at) }) }}
          </p>
          <div class="flex flex-wrap gap-2">
            <Button
              v-if="login.state === 'waiting_code'"
              variant="outline"
              size="sm"
              :disabled="
                busy || uncertain || resendWait > 0 || !login.can_resend || available !== true
              "
              @click="auth.action('resend')"
              >{{
                resendWait
                  ? t('personal.ddx.resendWait', { seconds: resendWait })
                  : t('personal.ddx.resend')
              }}</Button
            >
            <Button variant="ghost" size="sm" :disabled="busy" @click="cancelLogin">{{
              t('common.cancel')
            }}</Button>
          </div>
        </template>
        <div v-if="uncertain" class="space-y-2 text-sm" role="status">
          <p>{{ t('personal.ddx.uncertain') }}</p>
          <Button variant="outline" size="sm" :disabled="checking || busy" @click="auth.refresh">{{
            t('personal.ddx.checkStatus')
          }}</Button>
          <Button
            v-if="retryStart"
            variant="outline"
            size="sm"
            :disabled="busy"
            @click="auth.repeatStart"
            >{{ t('personal.ddx.retryStart') }}</Button
          >
        </div>
      </div>
      <p
        v-if="login && !loginActive && (login.state !== 'ready' || readyMatchesSource)"
        class="text-muted-foreground text-sm"
        role="status"
      >
        {{ t(`personal.ddx.login.${login.state}`) }}
      </p>
      <p v-if="login?.error || loginError" class="text-destructive text-sm" role="alert">
        {{ errorLabel(loginError ?? login!.error!) }}
      </p>
      <Button
        v-if="available === null && loginError"
        variant="outline"
        size="sm"
        :disabled="checking"
        @click="auth.refresh"
        >{{ t('personal.ddx.checkStatus') }}</Button
      >
    </template>
    <p v-if="actionError" class="text-destructive text-sm" role="alert">
      {{ errorLabel(actionError) }}
    </p>
    <details v-if="archived.length" class="text-sm">
      <summary class="text-primary cursor-pointer">
        {{ t('personal.ddx.previousConnections') }}
      </summary>
      <ul class="text-muted-foreground mt-2 space-y-1">
        <li v-for="item in archived" :key="item.id">
          {{ item.name }} · {{ item.connection?.phone_masked }} ·
          {{ t('personal.ddx.historyKept') }}
        </li>
      </ul>
    </details>
  </section>
</template>
