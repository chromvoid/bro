<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { ChevronRight, Copy, UserPlus } from '@lucide/vue'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { Token, groupScopes, tokenLink, type User } from './api'
import { useAccess, useAccounts, useSharedSpeech, useUserLimits, useUsers } from './useAdmin'

/**
 * M02–M04/M07/M15/M16: who may use the installation and how much. Rights, subscriptions, limits
 * and account recovery are separate; disabling blocks access and keeps the data.
 */
const { t, te } = useI18n()
// Message keys cannot contain dots: «tasks.read» is looked up as «tasks_read».
const key = (code: string) => code.replaceAll('.', '_')
const queries = useQueryClient()
const users = useUsers()
const accounts = useAccounts()
const access = useAccess()
const speech = useSharedSpeech()
const busy = ref(false)
const scopeLabel = (scope: string) =>
  te(`admin.scope.${key(scope)}`) ? t(`admin.scope.${key(scope)}`) : scope
const groups = computed(() => groupScopes(users.data.value?.available_scopes ?? []))

async function run<T>(task: () => Promise<T>, message: string | null): Promise<T | null> {
  if (busy.value) return null
  busy.value = true
  try {
    const result = await task()
    if (message) toast.success(message)
    return result
  } catch (error) {
    toast.error(
      error instanceof ApiError && error.status === 409 ? t('admin.changed') : errorText(error, t),
    )
    return null
  } finally {
    busy.value = false
    void queries.invalidateQueries({ queryKey: ['accounts'] })
  }
}

// M03: a one-time link, handed over in person; nothing is sent to anyone automatically.
const inviting = ref(false)
const inviteScopes = ref<string[]>([])
const inviteAccounts = ref<string[]>([])
const subscriptions = computed(() =>
  (accounts.data.value?.accounts ?? []).filter((account) => account.enabled),
)
const providerName = (provider: string) => (provider === 'claude' ? 'Claude' : 'Codex')
const link = ref<string | null>(null)
function startInvite() {
  inviteScopes.value = (users.data.value?.available_scopes ?? []).filter(
    (scope) => scope.endsWith('.read') || scope === 'session.control',
  )
  // Every Codex subscription by default: the new person talks to the assistant right away.
  inviteAccounts.value = subscriptions.value
    .filter((account) => account.provider === 'codex')
    .map((account) => account.id)
  link.value = null
  inviting.value = true
}
async function invite() {
  const body = { scopes: inviteScopes.value, accounts: inviteAccounts.value }
  const created = await run(
    async () => Token.parse((await api.post('/v1/admin/tokens', body)).data),
    null,
  )
  if (created) link.value = tokenLink(location.origin, 'invite', created.token)
}
async function copy() {
  if (!link.value) return
  try {
    await navigator.clipboard.writeText(link.value)
    toast.success(t('admin.copied'))
  } catch {
    toast(t('admin.copyManually'))
  }
}
const toggle = (list: string[], scope: string, on: boolean | 'indeterminate') =>
  on === true ? [...new Set([...list, scope])] : list.filter((item) => item !== scope)

// M04/M07: one person's rights, subscriptions and daily limits.
const selected = shallowRef<User | null>(null)
const selectedOpen = computed({
  get: () => selected.value !== null,
  set: (value: boolean) => {
    if (!value) selected.value = null
  },
})
const form = reactive({
  scopes: [] as string[],
  accounts: [] as string[],
  speech: [] as string[],
  limits: {} as Record<string, string>,
})
const limits = useUserLimits(computed(() => selected.value?.id ?? null))
watch(
  () => limits.data.value,
  (value) => {
    if (!value) return
    form.limits = Object.fromEntries(
      value.limits.map((limit) => [
        limit.kind,
        limit.maximum === null
          ? ''
          : String(
              limit.kind === 'dictation_ms' ? Math.round(limit.maximum / 60_000) : limit.maximum,
            ),
      ]),
    )
  },
)
const grantOf = (user: string) => access.data.value?.grants.find((grant) => grant.user_id === user)
// A person without a subscription cannot talk to the assistant, whatever their rights.
const withoutSubscriptions = (user: User) =>
  user.status === 'active' && grantOf(user.id)?.accounts.length === 0
// Disabled subscriptions are offered only while someone still holds them, to take them away.
const personSubscriptions = computed(() =>
  (accounts.data.value?.accounts ?? []).filter(
    (account) => account.enabled || form.accounts.includes(account.id),
  ),
)
const sharedSpeech = computed(() =>
  (speech.data.value?.connections ?? []).filter((connection) => connection.ownership === 'shared'),
)
// Filled once per opened person: a refresh after saving never overwrites what is being edited.
const grantsFor = ref<string | null>(null)
watch(
  () => [selected.value?.id, access.data.value] as const,
  ([user]) => {
    const grant = user ? grantOf(user) : undefined
    if (!user || !grant || grantsFor.value === user) return
    form.accounts = [...grant.accounts]
    form.speech = [...grant.speech_connections]
    grantsFor.value = user
  },
)
function open(user: User) {
  grantsFor.value = null
  selected.value = user
  form.scopes = [...user.scopes]
  resetLink.value = null
}
const saveRights = (status = selected.value!.status) =>
  run(
    () =>
      api.post(`/v1/admin/users/${encodeURIComponent(selected.value!.id)}`, {
        status,
        scopes: form.scopes,
        revision: selected.value!.revision,
      }),
    status === selected.value!.status
      ? t('admin.rightsSaved')
      : status === 'disabled'
        ? t('admin.disabled')
        : t('admin.enabled'),
  )
async function saveRightsAndClose(status?: 'active' | 'disabled') {
  if (await saveRights(status)) selected.value = null
}
const saveSubscriptions = () =>
  run(
    () =>
      api.post(`/v1/admin/users/${encodeURIComponent(selected.value!.id)}/access`, {
        accounts: form.accounts,
        // Unknown speech connections are left as they are, never cleared.
        ...(speech.data.value ? { speech_connections: form.speech } : {}),
      }),
    t('admin.subscriptionsSaved'),
  )
async function saveLimits() {
  const body: Record<string, number | null> = {}
  for (const [kind, value] of Object.entries(form.limits)) {
    const trimmed = value.trim()
    if (trimmed && !/^\d+$/.test(trimmed)) return void toast.error(t('admin.limitNumber'))
    const number = trimmed ? Number(trimmed) : null
    body[kind] = number === null ? null : kind === 'dictation_ms' ? number * 60_000 : number
  }
  await run(
    () => api.post(`/v1/admin/users/${encodeURIComponent(selected.value!.id)}/limits`, body),
    t('admin.limitsSaved'),
  )
}

// M15: the reset link is bound to this person.
const resetLink = ref<string | null>(null)
async function reset() {
  const created = await run(
    async () =>
      Token.parse((await api.post('/v1/admin/tokens', { user_id: selected.value!.id })).data),
    null,
  )
  if (created) resetLink.value = tokenLink(location.origin, 'reset', created.token)
}

// M16: disabling needs a confirmation; data stays.
const disabling = ref(false)
async function disable() {
  disabling.value = false
  await saveRightsAndClose('disabled')
}
</script>

<template>
  <PageHeader :title="t('admin.users')" :back="{ name: 'admin' }">
    <template #actions>
      <Button
        variant="ghost"
        size="icon"
        :aria-label="t('admin.newInvite')"
        :disabled="!users.data.value || accounts.isPending.value"
        @click="startInvite"
      >
        <UserPlus class="size-5" />
      </Button>
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <RefreshFailed :query="users" />
    <LoadingState v-if="users.isPending.value" :rows="4" />
    <ErrorState
      v-else-if="users.isLoadingError.value"
      :error="users.error.value"
      @retry="users.refetch()"
    />
    <template v-else-if="users.data.value">
      <ul class="bg-card divide-y rounded-3xl border" data-testid="users">
        <li v-for="user in users.data.value.users" :key="user.id">
          <button
            type="button"
            class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left"
            @click="open(user)"
          >
            <span class="min-w-0 flex-1">
              <span class="block truncate font-medium">{{ user.name }}</span>
              <span class="text-muted-foreground block text-sm">@{{ user.login }}</span>
            </span>
            <!-- Stacked, so large text never pushes the row past the screen. -->
            <span
              v-if="user.system_admin || user.status === 'disabled' || withoutSubscriptions(user)"
              class="flex flex-col items-end gap-1"
            >
              <Badge v-if="user.system_admin">{{ t('admin.administrator') }}</Badge>
              <Badge v-if="withoutSubscriptions(user)" variant="outline">{{
                t('admin.withoutSubscriptions')
              }}</Badge>
              <Badge v-if="user.status === 'disabled'" variant="destructive">{{
                t('admin.status.disabled')
              }}</Badge>
            </span>
            <ChevronRight class="text-muted-foreground size-4" />
          </button>
        </li>
      </ul>
      <Button :disabled="!users.data.value || accounts.isPending.value" @click="startInvite"
        ><UserPlus />{{ t('admin.newInvite') }}</Button
      >
      <Alert>
        <AlertTitle>{{ t('admin.privateTitle') }}</AlertTitle>
        <AlertDescription>{{ t('admin.privateText') }}</AlertDescription>
      </Alert>
    </template>
  </div>

  <Panel
    v-model:open="inviting"
    :title="t('admin.inviteTitle')"
    :description="t('admin.inviteText')"
  >
    <template v-if="!link">
      <fieldset v-for="group in groups" :key="group.key" class="space-y-2">
        <legend class="text-sm font-medium">{{ t(`admin.group.${group.key}`) }}</legend>
        <Label
          v-for="scope in group.scopes"
          :key="scope"
          class="flex items-center gap-2 font-normal"
        >
          <Checkbox
            :model-value="inviteScopes.includes(scope)"
            @update:model-value="(on) => (inviteScopes = toggle(inviteScopes, scope, on))"
          />{{ scopeLabel(scope) }}
        </Label>
      </fieldset>
      <fieldset v-if="subscriptions.length" class="space-y-2" data-testid="invite-subscriptions">
        <legend class="text-sm font-medium">{{ t('admin.subscriptions') }}</legend>
        <Label
          v-for="account in subscriptions"
          :key="account.id"
          class="flex items-center gap-2 font-normal"
        >
          <Checkbox
            :model-value="inviteAccounts.includes(account.id)"
            @update:model-value="(on) => (inviteAccounts = toggle(inviteAccounts, account.id, on))"
          />{{ account.label }}
          <span
            v-if="account.label !== providerName(account.provider)"
            class="text-muted-foreground"
            >{{ providerName(account.provider) }}</span
          >
        </Label>
      </fieldset>
      <Button size="lg" class="w-full" :disabled="busy" @click="invite">{{
        t('admin.createLink')
      }}</Button>
    </template>
    <div v-else class="space-y-3" data-testid="invite-link">
      <Alert>
        <AlertTitle>{{ t('admin.oneTime') }}</AlertTitle>
        <AlertDescription>{{ t('admin.oneTimeText') }}</AlertDescription>
      </Alert>
      <Input :model-value="link" readonly :aria-label="t('admin.link')" class="font-mono text-xs" />
      <Button variant="outline" class="w-full" @click="copy"><Copy />{{ t('admin.copy') }}</Button>
    </div>
  </Panel>

  <Panel
    v-model:open="selectedOpen"
    :title="selected?.name ?? ''"
    :description="selected?.login ? `@${selected.login}` : undefined"
  >
    <template v-if="selected">
      <section class="space-y-3" aria-labelledby="rights-title">
        <h3 id="rights-title" class="font-medium">{{ t('admin.rights') }}</h3>
        <p v-if="selected.system_admin" class="text-muted-foreground text-sm">
          {{ t('admin.adminHasAll') }}
        </p>
        <fieldset v-for="group in groups" :key="group.key" class="space-y-1.5">
          <legend class="text-muted-foreground text-sm">{{ t(`admin.group.${group.key}`) }}</legend>
          <Label
            v-for="scope in group.scopes"
            :key="scope"
            class="flex items-center gap-2 font-normal"
          >
            <Checkbox
              :model-value="form.scopes.includes(scope)"
              @update:model-value="(on) => (form.scopes = toggle(form.scopes, scope, on))"
            />{{ scopeLabel(scope) }}
          </Label>
        </fieldset>
        <Button class="w-full" :disabled="busy" @click="saveRightsAndClose()">
          {{ t('admin.saveRights') }}
        </Button>
      </section>

      <section
        class="space-y-3 border-t pt-4"
        aria-labelledby="subscriptions-title"
        data-testid="user-subscriptions"
      >
        <h3 id="subscriptions-title" class="font-medium">{{ t('admin.subscriptions') }}</h3>
        <p v-if="access.isLoadingError.value" class="text-muted-foreground text-sm">
          {{ t('admin.accessUnknown') }}
        </p>
        <LoadingState v-else-if="grantsFor !== selected.id" :rows="1" />
        <template v-else>
          <p v-if="!personSubscriptions.length" class="text-muted-foreground text-sm">
            {{ t('admin.noAccounts') }}
          </p>
          <Label
            v-for="account in personSubscriptions"
            :key="account.id"
            class="flex items-center gap-2 font-normal"
          >
            <Checkbox
              :model-value="form.accounts.includes(account.id)"
              @update:model-value="(on) => (form.accounts = toggle(form.accounts, account.id, on))"
            />{{ account.label }}
            <span
              v-if="account.label !== providerName(account.provider)"
              class="text-muted-foreground"
              >{{ providerName(account.provider) }}</span
            >
          </Label>
          <fieldset v-if="sharedSpeech.length" class="space-y-1.5">
            <legend class="text-muted-foreground text-sm">
              {{ t('admin.speechConnections') }}
            </legend>
            <Label
              v-for="connection in sharedSpeech"
              :key="connection.id"
              class="flex items-center gap-2 font-normal"
            >
              <Checkbox
                :model-value="form.speech.includes(connection.id)"
                @update:model-value="(on) => (form.speech = toggle(form.speech, connection.id, on))"
              />{{ connection.label }}
            </Label>
          </fieldset>
          <Button variant="outline" class="w-full" :disabled="busy" @click="saveSubscriptions">{{
            t('admin.saveSubscriptions')
          }}</Button>
        </template>
      </section>

      <section
        class="space-y-3 border-t pt-4"
        aria-labelledby="limits-title"
        data-testid="user-limits"
      >
        <h3 id="limits-title" class="font-medium">{{ t('admin.limitsTitle') }}</h3>
        <LoadingState v-if="limits.isPending.value" :rows="1" />
        <template v-else>
          <div v-for="kind in Object.keys(form.limits)" :key="kind" class="space-y-1.5">
            <Label :for="`limit-${kind}`">{{ t(`admin.limit.${kind}`) }}</Label>
            <Input
              :id="`limit-${kind}`"
              v-model="form.limits[kind]"
              inputmode="numeric"
              :placeholder="t('admin.unlimited')"
            />
          </div>
          <p class="text-muted-foreground text-sm">{{ t('admin.limitsHint') }}</p>
          <Button variant="outline" class="w-full" :disabled="busy" @click="saveLimits">{{
            t('admin.saveLimits')
          }}</Button>
        </template>
      </section>

      <section class="space-y-3 border-t pt-4">
        <Button variant="outline" class="w-full" :disabled="busy" @click="reset">{{
          t('admin.resetLink')
        }}</Button>
        <div v-if="resetLink" class="space-y-2" data-testid="reset-link">
          <p class="text-sm">{{ t('admin.resetText', { name: selected.name }) }}</p>
          <Input
            :model-value="resetLink"
            readonly
            :aria-label="t('admin.link')"
            class="font-mono text-xs"
          />
        </div>
        <template v-if="!selected.system_admin">
          <Button
            v-if="selected.status === 'active'"
            variant="ghost"
            class="text-destructive w-full"
            @click="disabling = true"
          >
            {{ t('admin.disable') }}
          </Button>
          <Button
            v-else
            variant="outline"
            class="w-full"
            :disabled="busy"
            @click="saveRightsAndClose('active')"
          >
            {{ t('admin.enable') }}
          </Button>
        </template>
      </section>
    </template>
  </Panel>

  <Dialog v-model:open="disabling">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('admin.disableTitle', { name: selected?.name ?? '' }) }}</DialogTitle>
        <DialogDescription>{{
          t('admin.disableText', { name: selected?.name ?? '' })
        }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="disabling = false">{{ t('admin.keepActive') }}</Button>
        <Button variant="destructive" @click="disable">{{ t('admin.disable') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
