<script setup lang="ts">
import { KeyRound, Plus } from '@lucide/vue'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
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
import { Progress } from '@/components/ui/progress'
import { dayIn, momentPhrase } from '@/core/dates'
import { errorText } from '@/core/errors'
import { operationKind, useLedger } from '@/core/operations'
import { useSession } from '@/stores/session'
import {
  ACCOUNTS,
  ACTIVE_LOGIN,
  needsReauthentication,
  quotaView,
  resetCredits,
  type Account,
  type Connection,
  type QuotaView,
} from './api'
import LoginCard from './LoginCard.vue'
import AccountAccessSetup from './AccountAccessSetup.vue'
import { notInstalled, useAccounts } from './useAdmin'

/**
 * M05/M06/M17: provider subscriptions of the installation. Unknown quota is not zero, a passed
 * reset is not a refill, and a subscription is used by others only after it joins a pool.
 */
const { t, te } = useI18n()
const session = useSession()
const snapshot = useAccounts()
const ledger = useLedger(ACCOUNTS)
const data = computed(() => snapshot.data.value)
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const busy = ref(false)

async function send(
  kind: string,
  payload: Record<string, unknown>,
  expected: Record<string, number> = {},
  message = t('admin.saved'),
) {
  if (busy.value) return false
  busy.value = true
  try {
    await ledger.operate({ kind, payload, expected_revisions: expected })
    toast.success(message)
    return true
  } catch (error) {
    toast.error(errorText(error, t))
    return false
  } finally {
    busy.value = false
  }
}

const quotas = (account: Account) =>
  (data.value?.quotas ?? [])
    .filter(
      (quota) => quota.account_id === account.id && (quota.bucket !== 'request' || quota.exhausted),
    )
    .sort((a, b) => a.scope.localeCompare(b.scope) || Number(a.bucket) - Number(b.bucket))
    .map((quota) => ({ quota, view: quotaView(quota) }))
const periodText = (view: QuotaView) => {
  const scope = view.scope === '*' ? t('admin.quota.total') : view.scope
  const period =
    view.period.kind === 'request'
      ? t('admin.quota.requests')
      : view.period.kind === 'week'
        ? t('admin.quota.week')
        : view.period.kind === 'raw'
          ? view.period.value
          : t(`admin.quota.${view.period.kind}`, { count: view.period.count })
  return `${scope} · ${period}`
}
const statusText = (view: QuotaView) =>
  view.exhausted
    ? t('admin.quota.exhausted')
    : view.remaining === null
      ? t('admin.quota.unknown')
      : t('admin.quota.remaining', { value: view.remaining })

// M06: a new sign-in; the card shows the provider step.
const connecting = ref(false)
const form = reactive({ provider: '', label: '' })
const providers = computed(() =>
  (data.value?.login_providers ?? []).filter(
    (provider) =>
      !(data.value?.logins ?? []).some(
        (login) => (login.provider || 'codex') === provider && ACTIVE_LOGIN.has(login.state),
      ),
  ),
)
function startConnect() {
  Object.assign(form, { provider: providers.value[0] ?? '', label: '' })
  connecting.value = true
}
async function connect() {
  if (!form.provider) return
  const name = form.provider === 'claude' ? 'Claude' : 'Codex'
  if (
    await send(
      'account.connect',
      { provider: form.provider, label: form.label.trim() || name },
      {},
      t('admin.connectStarted'),
    )
  )
    connecting.value = false
}
// Sign-ins that were in progress during this visit stay until the user leaves; old ones do not.
const followed = ref(new Set<string>())
watch(
  () => data.value?.logins,
  (logins) => {
    const active = (logins ?? []).filter((login) => ACTIVE_LOGIN.has(login.state))
    if (active.some((login) => !followed.value.has(login.id)))
      followed.value = new Set([...followed.value, ...active.map((login) => login.id)])
  },
  { immediate: true },
)
const logins = computed(() =>
  (data.value?.logins ?? []).filter((login) => followed.value.has(login.id)).slice(0, 3),
)
const loginSection = ref<HTMLElement>()
const connectionsFor = (account: Account) =>
  (data.value?.connections ?? []).filter((connection) => connection.account_id === account.id)
const canStartReconnect = (account: Account) => providers.value.includes(account.provider)
async function reconnect(connection: Connection) {
  if (
    await send(
      'connection.reconnect',
      { connection_id: connection.id },
      { [connection.id]: connection.revision },
      t('admin.connectStarted'),
    )
  ) {
    await snapshot.refetch()
    await nextTick()
    loginSection.value?.focus()
    loginSection.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

// Account choice for the chat and for background work.
const responsive = computed(() =>
  (data.value?.accounts ?? []).filter((account) =>
    data.value?.connections.some(
      (connection) =>
        connection.account_id === account.id && connection.capabilities.includes('responses'),
    ),
  ),
)
const policy = (name: string) => data.value?.policies.find((item) => item.name === name) ?? null
const choice = reactive({ interactive: '', background: '', reserve: '' })
function syncChoice() {
  choice.interactive = policy('interactive')?.accounts[0] ?? ''
  const background = policy('background')
  choice.background =
    background?.accounts[0] ?? responsive.value[1]?.id ?? responsive.value[0]?.id ?? ''
  choice.reserve = background?.fallback ? (background.accounts[1] ?? '') : ''
}
const policyOpen = ref(false)
function openPolicy() {
  syncChoice()
  policyOpen.value = true
}
async function savePolicy() {
  const expected = (name: string): Record<string, number> => {
    const old = policy(name)
    return old ? { [old.id]: old.revision } : {}
  }
  const ok =
    (!choice.interactive ||
      (await send(
        'policy.set',
        { name: 'interactive', accounts: [choice.interactive], fallback: true },
        expected('interactive'),
      ))) &&
    (!choice.background ||
      (await send(
        'policy.set',
        {
          name: 'background',
          accounts:
            choice.reserve && choice.reserve !== choice.background
              ? [choice.background, choice.reserve]
              : [choice.background],
          fallback: !!choice.reserve && choice.reserve !== choice.background,
        },
        expected('background'),
      )))
  if (ok) policyOpen.value = false
}
const describe = (entry: { body: string }) =>
  t('admin.unresolved', { kind: operationKind(entry as never) })
const authLabel = (state: string) => (te(`admin.auth.${state}`) ? t(`admin.auth.${state}`) : state)
const accountName = (id: string) =>
  data.value?.accounts.find((account) => account.id === id)?.label ?? id
</script>

<template>
  <PageHeader :title="t('admin.accounts')" :back="{ name: 'admin' }">
    <template #actions>
      <Button
        v-if="data?.can_connect && providers.length"
        variant="ghost"
        size="icon"
        :aria-label="t('admin.connect')"
        @click="startConnect"
      >
        <Plus class="size-5" />
      </Button>
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <UnresolvedWrites
      :entries="ledger.unresolved.value"
      :describe="describe"
      :check="ledger.check"
      :resend="ledger.resend"
      :abandon="ledger.abandon"
    />
    <RefreshFailed :query="snapshot" />
    <LoadingState v-if="snapshot.isPending.value" :rows="3" />
    <EmptyState
      v-else-if="snapshot.isLoadingError.value && notInstalled(snapshot.error.value)"
      :icon="KeyRound"
      :title="t('admin.accountsUnavailable')"
      :description="t('admin.accountsUnavailableText')"
    />
    <ErrorState
      v-else-if="snapshot.isLoadingError.value"
      :error="snapshot.error.value"
      @retry="snapshot.refetch()"
    />
    <template v-else-if="data">
      <section class="space-y-2" aria-labelledby="connect-title">
        <h2 id="connect-title" class="font-semibold">{{ t('admin.connectTitle') }}</h2>
        <p
          v-if="!data.can_connect"
          class="text-muted-foreground text-sm"
          data-testid="connect-unavailable"
        >
          {{ t('admin.connectUnavailable') }}
        </p>
        <template v-else>
          <Button v-if="providers.length" variant="outline" @click="startConnect"
            ><Plus />{{ t('admin.connect') }}</Button
          >
          <p class="text-muted-foreground text-sm">{{ t('admin.connectHint') }}</p>
        </template>
      </section>

      <section
        v-if="logins.length"
        ref="loginSection"
        class="space-y-2"
        tabindex="-1"
        aria-labelledby="login-title"
      >
        <h2 id="login-title" class="font-semibold">{{ t('admin.loginTitle') }}</h2>
        <LoginCard
          v-for="login in logins"
          :id="login.id"
          :key="login.id"
          @cancel="
            send('account.connect.cancel', { login_id: login.id }, {}, t('admin.login.cancelled'))
          "
        />
      </section>

      <section class="space-y-2" aria-labelledby="subscriptions-title">
        <h2 id="subscriptions-title" class="font-semibold">{{ t('admin.subscriptions') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('admin.subscriptionsHint') }}</p>
        <p
          v-if="!data.accounts.length"
          class="text-muted-foreground text-sm"
          data-testid="no-subscriptions"
        >
          {{ t('admin.noSubscriptions') }}
        </p>
        <ul v-else class="space-y-3" data-testid="subscriptions">
          <li
            v-for="account in data.accounts"
            :key="account.id"
            class="bg-card space-y-3 rounded-3xl border p-5"
          >
            <div class="flex items-center gap-3">
              <div class="min-w-0 flex-1">
                <p class="font-medium">{{ account.label }}</p>
                <p class="text-muted-foreground text-sm">
                  {{ account.provider === 'claude' ? 'Claude' : 'Codex' }}
                  · {{ t('admin.resetCredits', { value: resetCredits(account).value })
                  }}<template v-if="resetCredits(account).stale">
                    · {{ t('admin.stale') }}</template
                  >
                </p>
              </div>
              <Badge :variant="account.enabled ? 'success' : 'secondary'">{{
                account.enabled ? t('admin.inUse') : t('admin.paused')
              }}</Badge>
            </div>
            <div
              v-for="connection in connectionsFor(account)"
              :key="connection.id"
              class="space-y-2"
            >
              <p
                v-if="needsReauthentication(connection)"
                class="text-destructive text-sm"
                role="status"
              >
                {{ t('admin.reconnectRequired') }}
                <template v-if="!connection.can_reconnect">{{
                  t('admin.reconnectOnMachine', { machine: connection.machine })
                }}</template>
              </p>
              <Button
                v-if="connection.can_reconnect"
                size="sm"
                variant="outline"
                :disabled="busy || !canStartReconnect(account)"
                @click="reconnect(connection)"
              >
                <KeyRound />{{ t('admin.reconnect') }}
              </Button>
            </div>
            <AccountAccessSetup :account-id="account.id" :label="account.label" />
            <p v-if="!quotas(account).length" class="text-muted-foreground text-sm">
              {{ t('admin.quota.waiting') }}
            </p>
            <div
              v-for="{ quota, view } in quotas(account)"
              :key="`${quota.scope}:${quota.bucket}`"
              class="space-y-1"
            >
              <div class="flex flex-wrap justify-between gap-3 text-sm">
                <span>{{ periodText(view) }}</span>
                <span :class="view.exhausted ? 'text-destructive font-medium' : 'font-medium'">{{
                  statusText(view)
                }}</span>
              </div>
              <Progress
                v-if="view.remaining !== null"
                :model-value="view.remaining"
                :aria-label="`${periodText(view)}: ${statusText(view)}`"
              />
              <p class="text-muted-foreground text-xs">
                <template v-if="view.stale">{{ t('admin.stale') }} · </template>
                {{
                  quota.observed_at
                    ? t('admin.observed', { when: momentPhrase(quota.observed_at, zone, today) })
                    : ''
                }}
                <template v-if="quota.resets_at">
                  ·
                  {{
                    view.expired
                      ? t('admin.quota.awaiting')
                      : t('admin.quota.resets', {
                          when: momentPhrase(quota.resets_at, zone, today),
                        })
                  }}
                </template>
              </p>
            </div>
            <Button
              size="sm"
              variant="ghost"
              :disabled="busy"
              @click="
                send(
                  'account.update',
                  { id: account.id, enabled: !account.enabled },
                  { [account.id]: account.revision },
                )
              "
            >
              {{ account.enabled ? t('admin.pauseNew') : t('admin.resume') }}
            </Button>
          </li>
        </ul>
      </section>

      <section v-if="data.connections.length" class="space-y-2" aria-labelledby="connections-title">
        <h2 id="connections-title" class="font-semibold">{{ t('admin.connections') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border">
          <li
            v-for="connection in data.connections"
            :key="connection.id"
            class="flex items-center gap-3 px-4 py-3"
          >
            <div class="min-w-0 flex-1 text-sm">
              <p class="font-medium">
                {{ accountName(connection.account_id) }} · {{ connection.machine }}
              </p>
              <p class="text-muted-foreground">
                {{
                  authLabel(
                    needsReauthentication(connection) ? 'auth_required' : connection.auth_state,
                  )
                }}<template v-if="connection.refresh_owner">
                  · {{ t('admin.refreshOwner', { owner: connection.refresh_owner }) }}</template
                >
              </p>
            </div>
            <Button
              size="sm"
              variant="ghost"
              :disabled="busy"
              @click="
                send(
                  'connection.update',
                  { id: connection.id, enabled: !connection.enabled },
                  { [connection.id]: connection.revision },
                )
              "
            >
              {{ connection.enabled ? t('admin.turnOff') : t('admin.turnOn') }}
            </Button>
          </li>
        </ul>
      </section>

      <section v-if="responsive.length" class="space-y-2" aria-labelledby="policy-title">
        <h2 id="policy-title" class="font-semibold">{{ t('admin.policy') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('admin.policyText') }}</p>
        <Button variant="outline" size="sm" @click="openPolicy">{{ t('admin.policyEdit') }}</Button>
      </section>
      <Alert>
        <AlertTitle>{{ t('admin.accessRule') }}</AlertTitle>
        <AlertDescription>{{ t('admin.accessRuleText') }}</AlertDescription>
      </Alert>
    </template>
  </div>

  <Panel
    v-model:open="connecting"
    :title="t('admin.connect')"
    :description="t('admin.connectHint')"
  >
    <form class="space-y-4" novalidate @submit.prevent="connect">
      <div class="space-y-2">
        <Label for="connect-provider">{{ t('admin.provider') }}</Label>
        <NativeSelect id="connect-provider" v-model="form.provider">
          <option v-for="provider in providers" :key="provider" :value="provider">
            {{ provider === 'claude' ? 'Claude' : 'Codex' }}
          </option>
        </NativeSelect>
      </div>
      <div class="space-y-2">
        <Label for="connect-label">{{ t('admin.subscriptionName') }}</Label>
        <Input
          id="connect-label"
          v-model="form.label"
          maxlength="100"
          :placeholder="t('admin.subscriptionExample')"
        />
      </div>
      <Button type="submit" size="lg" class="w-full" :disabled="!form.provider || busy">{{
        t('admin.startLogin')
      }}</Button>
    </form>
  </Panel>

  <Panel v-model:open="policyOpen" :title="t('admin.policy')" :description="t('admin.policyText')">
    <form class="space-y-4" novalidate @submit.prevent="savePolicy">
      <div class="space-y-2">
        <Label for="policy-interactive">{{ t('admin.policyChat') }}</Label>
        <NativeSelect id="policy-interactive" v-model="choice.interactive">
          <option value="">{{ t('admin.automatic') }}</option>
          <option v-for="account in responsive" :key="account.id" :value="account.id">
            {{ account.label }}
          </option>
        </NativeSelect>
      </div>
      <div class="space-y-2">
        <Label for="policy-background">{{ t('admin.policyBackground') }}</Label>
        <NativeSelect id="policy-background" v-model="choice.background">
          <option v-for="account in responsive" :key="account.id" :value="account.id">
            {{ account.label }}
          </option>
        </NativeSelect>
      </div>
      <div class="space-y-2">
        <Label for="policy-reserve">{{ t('admin.reserve') }}</Label>
        <NativeSelect id="policy-reserve" v-model="choice.reserve">
          <option value="">{{ t('admin.noReserve') }}</option>
          <option v-for="account in responsive" :key="account.id" :value="account.id">
            {{ account.label }}
          </option>
        </NativeSelect>
      </div>
      <p class="text-muted-foreground text-sm">{{ t('admin.policyHint') }}</p>
      <Button type="submit" size="lg" class="w-full" :disabled="busy">{{
        t('common.save')
      }}</Button>
    </form>
  </Panel>
</template>
