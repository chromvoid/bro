<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import FieldError from '@/components/FieldError.vue'
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
import type { PluginHost } from '@/plugins/api'
import { configured } from '../contracts'
import { dateTime } from '../format'
import { connectionLabel, errorText, t } from '../labels'
import { valueOf, type Trading } from '../model'
import Chips from '../parts/Chips.vue'
import ReadStatus from '../parts/ReadStatus.vue'
import Section from '../parts/Section.vue'

/**
 * The Bybit key. Key and secret live only in these two refs: never in a store, route, draft or
 * log. They are cleared the moment they are sent, when the page leaves the foreground and when
 * the screen closes.
 */
const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.connection
const view = computed(() => valueOf(model.view.value))
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const KEY = /^[A-Za-z0-9]{10,64}$/
const SECRET = /^[A-Za-z0-9]{10,128}$/

const apiKey = ref('')
const apiSecret = ref('')
const environment = ref<string | null>(null)
const label = ref('')
const historyDays = ref('')
const replaceAccount = ref(false)
const confirming = ref(false)

function clearSecrets() {
  apiKey.value = ''
  apiSecret.value = ''
}
watch(props.host.lifecycle, (lifecycle) => lifecycle !== 'foreground' && clearSecrets())
onBeforeUnmount(clearSecrets)

const keyValid = computed(
  () => KEY.test(apiKey.value.trim()) && SECRET.test(apiSecret.value.trim()),
)
const historyValid = computed(() => {
  const days = historyDays.value.trim()
  return !days || (/^\d+$/.test(days) && Number(days) >= 1 && Number(days) <= 730)
})
const valid = computed(() => keyValid.value && historyValid.value && label.value.length <= 64)
const issues = computed(() =>
  (view.value?.readiness.issues ?? []).filter(
    (issue) => issue.code !== 'not_configured' && issue.code !== 'key_not_ip_bound',
  ),
)

function submit() {
  const request = {
    apiKey: apiKey.value.trim(),
    apiSecret: apiSecret.value.trim(),
    environment: environment.value,
    label: label.value,
    historyDays: historyDays.value.trim() ? Number(historyDays.value.trim()) : null,
    replaceAccount: replaceAccount.value,
  }
  const usable = valid.value
  clearSecrets()
  replaceAccount.value = false
  if (usable) void model.connect(request)
}

async function disconnect() {
  confirming.value = false
  await model.disconnect()
}
const refresh = () => void model.refresh()
</script>

<template>
  <Section :title="t('connect_title')">
    <ReadStatus :model="model.view.value" :host="host" @retry="refresh" />
    <div v-if="view" class="bg-card space-y-1 rounded-3xl border p-5" data-testid="connection">
      <p class="font-semibold">{{ connectionLabel(view.connection_state ?? view.status) }}</p>
      <p v-if="view.secret_state.api_key?.hint" class="text-sm">
        {{ t('connected_key', view.secret_state.api_key.hint, view.public.environment ?? '') }}
      </p>
      <p v-if="view.key_info?.ip_bound === false" class="text-warning text-sm">
        {{ t('key_not_ip_bound') }}
      </p>
      <p v-if="view.key_info?.expires_at" class="text-muted-foreground text-sm">
        {{ t('key_expires', dateTime(view.key_info.expires_at, zone)) }}
      </p>
      <p v-for="issue in issues" :key="issue.code" class="text-destructive text-sm">
        {{ connectionLabel(issue.code) }}
      </p>
    </div>

    <div class="text-muted-foreground space-y-2 text-sm">
      <p>{{ t('guide_read_only') }}</p>
      <p>
        {{
          view?.egress_ips.length
            ? t('guide_ip', view.egress_ips.join(', '))
            : t('guide_ip_unknown')
        }}
      </p>
      <p>{{ t('guide_permissions') }}</p>
      <p class="text-xs">{{ t('guide_secret') }}</p>
    </div>

    <p
      v-if="model.notice.value"
      :class="[
        'text-sm',
        model.notice.value.kind === 'failed' ? 'text-destructive' : 'text-success',
      ]"
      role="status"
      data-testid="connect-notice"
    >
      {{
        model.notice.value.kind === 'connected'
          ? t('connect_done')
          : model.notice.value.kind === 'disconnected'
            ? t('disconnect_done')
            : model.notice.value.kind === 'unknown'
              ? t('connect_unknown')
              : errorText(model.notice.value.error, view?.egress_ips ?? [])
      }}
    </p>

    <p v-if="view && !model.writable.value" class="text-muted-foreground text-sm">
      {{ t('no_config_rights') }}
    </p>
    <form
      v-if="view && model.writable.value"
      class="bg-card space-y-4 rounded-3xl border p-5"
      autocomplete="off"
      novalidate
      @submit.prevent="submit"
    >
      <div v-if="view.environments.length > 1" class="space-y-2">
        <p class="text-sm font-medium">{{ t('environment') }}</p>
        <Chips
          :values="view.environments"
          :selected="environment ?? view.public.environment ?? null"
          :label="(value) => value"
          :name="t('environment')"
          @select="(value) => (environment = value)"
        />
      </div>
      <div class="space-y-2">
        <Label for="trading-key">{{ t('api_key') }}</Label>
        <Input
          id="trading-key"
          v-model="apiKey"
          type="password"
          maxlength="64"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
        />
      </div>
      <div class="space-y-2">
        <Label for="trading-secret">{{ t('api_secret') }}</Label>
        <Input
          id="trading-secret"
          v-model="apiSecret"
          type="password"
          maxlength="128"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
        />
      </div>
      <p v-if="(apiKey || apiSecret) && !keyValid" class="text-muted-foreground text-sm">
        {{ t('key_format') }}
      </p>
      <div class="grid gap-3 sm:grid-cols-2">
        <div class="space-y-2">
          <Label for="trading-label">{{ t('label_optional') }}</Label>
          <Input id="trading-label" v-model="label" maxlength="64" />
        </div>
        <div class="space-y-2">
          <Label for="trading-history">{{ t('history_days') }}</Label>
          <Input
            id="trading-history"
            v-model="historyDays"
            inputmode="numeric"
            maxlength="3"
            :aria-invalid="!historyValid"
          />
          <FieldError v-if="!historyValid">{{ t('history_days_invalid') }}</FieldError>
        </div>
      </div>
      <Label v-if="configured(view)" class="flex items-start gap-2 text-sm font-normal">
        <Checkbox
          :model-value="replaceAccount"
          @update:model-value="(value) => (replaceAccount = value === true)"
        />
        {{ t('replace_account') }}
      </Label>
      <div class="flex flex-wrap gap-2">
        <Button type="submit" :disabled="model.acting.value || !valid">
          {{ configured(view) ? t('replace_key') : t('connect_submit') }}
        </Button>
        <Button
          v-if="configured(view)"
          type="button"
          variant="outline"
          :disabled="model.acting.value"
          @click="confirming = true"
        >
          {{ t('disconnect') }}
        </Button>
      </div>
    </form>
  </Section>

  <Dialog v-model:open="confirming">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('disconnect_title') }}</DialogTitle>
        <DialogDescription>{{ t('disconnect_text') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="confirming = false">{{ t('cancel') }}</Button>
        <Button variant="destructive" @click="disconnect">{{ t('confirm_disconnect') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
