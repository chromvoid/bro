<script setup lang="ts">
import { CircleCheck, ExternalLink, LoaderCircle } from '@lucide/vue'
import { computed, ref, toRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ACTIVE_LOGIN, loginAction } from './api'
import { useLogin } from './useAdmin'
import AccountAccessSetup from './AccountAccessSetup.vue'

/**
 * M06/M17: signing a subscription in at the provider. The sign-in link is shown only for the
 * provider's own pages; a code pasted back is sent once and never kept. Polling a login never
 * erases a code being typed.
 */
const props = defineProps<{ id: string }>()
const emit = defineEmits<{ cancel: [] }>()
const { t, te } = useI18n()
const login = useLogin(toRef(props, 'id'))
const data = computed(() => login.data.value)
const action = computed(() => (data.value ? loginAction(data.value) : null))
const provider = computed(() => (data.value?.provider === 'claude' ? 'Claude' : 'Codex'))
const code = ref('')
const sending = ref(false)
const sent = ref('')

async function submit() {
  const value = code.value.trim()
  code.value = ''
  if (!value || sending.value) return
  sending.value = true
  sent.value = ''
  try {
    await api.post(`/v1/accounts/logins/${encodeURIComponent(props.id)}/code`, { code: value })
    sent.value = t('admin.login.codeSent')
    void login.refetch()
  } catch (error) {
    sent.value = errorText(error, t)
  } finally {
    sending.value = false
  }
}
const explanation = computed(() => {
  const error = data.value?.error
  return error && te(`admin.login.error.${error}`) ? t(`admin.login.error.${error}`) : ''
})
</script>

<template>
  <div class="bg-card space-y-3 rounded-2xl border p-4" aria-live="polite" data-testid="login-card">
    <p v-if="!data" class="text-muted-foreground flex items-center gap-2 text-sm">
      <LoaderCircle class="size-4 animate-spin" />{{ t('admin.login.checking') }}
    </p>
    <template v-else>
      <p class="flex items-center gap-2 font-medium">
        <CircleCheck v-if="data.state === 'ready'" class="text-success size-5" />
        <LoaderCircle
          v-else-if="ACTIVE_LOGIN.has(data.state) && data.state !== 'waiting_user'"
          class="size-4 animate-spin"
        />
        {{
          data.state === 'ready' && data.reconnect_connection_id
            ? t('admin.login.reconnected')
            : te(`admin.login.state.${data.state}`)
              ? t(`admin.login.state.${data.state}`, { provider })
              : t('admin.login.unknown')
        }}
      </p>
      <p class="text-muted-foreground text-sm">{{ data.label }}</p>
      <p v-if="data.reconnect_connection_id && ACTIVE_LOGIN.has(data.state)" class="text-sm">
        {{ t('admin.login.sameAccount') }}
      </p>
      <template v-if="action">
        <Button as-child>
          <a :href="action.url" target="_blank" rel="noopener noreferrer"
            ><ExternalLink />{{ t('admin.login.open') }}</a
          >
        </Button>
        <p v-if="action.code" class="text-sm">
          {{ t('admin.login.enterCode') }}
          <code class="bg-muted rounded px-1.5 py-0.5 font-mono">{{ action.code }}</code>
        </p>
        <form
          v-if="action.pasteCode"
          class="space-y-2"
          autocomplete="off"
          novalidate
          @submit.prevent="submit"
        >
          <p class="text-sm">{{ t('admin.login.pasteHint') }}</p>
          <Label :for="`login-code-${id}`">{{ t('admin.login.code') }}</Label>
          <div class="flex gap-2">
            <Input
              :id="`login-code-${id}`"
              v-model="code"
              type="password"
              maxlength="4096"
              autocomplete="off"
            />
            <Button type="submit" :disabled="!code.trim() || sending">{{
              t('admin.login.finish')
            }}</Button>
          </div>
        </form>
        <p class="text-muted-foreground text-xs">{{ t('admin.login.after') }}</p>
      </template>
      <p v-if="sent" class="text-sm" role="status">{{ sent }}</p>
      <p v-if="explanation" class="text-destructive text-sm">{{ explanation }}</p>
      <div v-if="data.state === 'ready'" class="flex flex-wrap gap-2" data-testid="login-ready">
        <p v-if="data.reconnect_connection_id" class="text-muted-foreground w-full text-sm">
          {{ t('admin.login.reconnectedText') }}
        </p>
        <AccountAccessSetup
          v-else-if="data.account_id"
          :account-id="data.account_id"
          :label="data.label"
        />
      </div>
      <Button v-if="ACTIVE_LOGIN.has(data.state)" size="sm" variant="ghost" @click="emit('cancel')">
        {{ t('admin.login.cancel') }}
      </Button>
    </template>
  </div>
</template>
