<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import type { Availability } from './availability'

const props = defineProps<{ availability?: Availability; failed?: boolean; busy?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const { t } = useI18n()
const knownReasons = new Set([
  'no_account_access',
  'no_compatible_connection',
  'auth_required',
  'auth_unavailable',
  'execution_disabled',
  'chat_disabled',
  'quota_exhausted',
  'daily_limit',
  'transport_error',
])
const message = computed(() => {
  if (props.failed) return t('assistant.availability.checkFailed')
  if (!props.availability)
    return t(props.busy ? 'assistant.availability.checking' : 'assistant.availability.unknown')
  const reason = props.availability.reason
  return reason && knownReasons.has(reason)
    ? t(`assistant.availability.${reason}`)
    : t('assistant.availability.unavailable')
})
const destination = computed(() =>
  props.availability?.action === 'configure_access'
    ? { name: 'admin-users' }
    : props.availability?.action === 'open_accounts'
      ? { name: 'accounts' }
      : null,
)
</script>

<template>
  <div
    v-if="failed || availability?.state !== 'configured'"
    class="bg-muted space-y-2 rounded-xl p-3 text-sm"
    role="status"
    data-testid="assistant-availability"
  >
    <p>{{ message }}</p>
    <p v-if="busy && (availability || failed)">{{ t('assistant.availability.checking') }}</p>
    <p v-if="availability?.action === 'contact_admin'">{{ t('assistant.contactAdmin') }}</p>
    <p v-if="availability?.retry_at">
      {{
        t('assistant.availability.retryAt', {
          at: new Date(availability.retry_at).toLocaleString(),
        })
      }}
    </p>
    <div class="flex flex-wrap gap-2">
      <Button v-if="destination" size="sm" as-child
        ><RouterLink :to="destination">{{ t('assistant.fixAccess') }}</RouterLink></Button
      >
      <Button size="sm" variant="outline" :disabled="busy" @click="emit('refresh')">{{
        t('assistant.availability.refresh')
      }}</Button>
    </div>
  </div>
</template>
