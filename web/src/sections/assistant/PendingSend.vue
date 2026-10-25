<script setup lang="ts">
import { Check, CircleAlert, LoaderCircle } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import UnknownOutcome from '@/components/state/UnknownOutcome.vue'
import { Button } from '@/components/ui/button'
import { useDeveloperMode } from '@/core/developer'
import { timeIn } from '@/core/dates'
import type { PendingSend } from './pending'

defineProps<{ send: PendingSend; zone: string; busy?: boolean }>()
const emit = defineEmits<{
  check: []
  resend: []
  abandon: []
  restore: []
  dismiss: []
  open: [messageId: string]
}>()
const { t } = useI18n()
// HTTP status, stage and trace help an administrator; the family sees the plain outcome above.
const developer = useDeveloperMode()
</script>

<template>
  <article
    class="space-y-2"
    data-testid="pending-send"
    :data-state="send.state"
    :data-status="send.status"
  >
    <div class="flex flex-col items-end gap-1">
      <div
        class="bg-accent/60 text-accent-foreground max-w-[85%] rounded-2xl rounded-tr-md px-4 py-2.5"
      >
        <p class="break-words whitespace-pre-wrap">{{ send.text || '📷' }}</p>
      </div>
      <time
        v-if="send.createdAt"
        class="text-muted-foreground text-xs tabular-nums"
        :datetime="send.createdAt"
        >{{ timeIn(zone, send.createdAt) }}</time
      >
      <p
        v-if="send.status === 'sending'"
        class="text-muted-foreground flex items-center gap-1.5 text-xs"
        role="status"
      >
        <LoaderCircle class="size-3.5 animate-spin" />
        {{ t('assistant.sendingNow') }}
      </p>
      <p
        v-else-if="send.status === 'delivered'"
        class="text-muted-foreground flex items-center gap-1.5 text-xs"
        role="status"
      >
        <Check class="size-3.5" />{{ t('assistant.delivered') }}
      </p>
    </div>

    <div v-if="send.status === 'delivered'" class="flex flex-wrap justify-end gap-2">
      <Button
        v-if="send.messageId"
        size="sm"
        variant="outline"
        @click="emit('open', send.messageId)"
        >{{ t('assistant.openMessage') }}</Button
      >
      <Button size="sm" variant="ghost" :disabled="busy" @click="emit('check')">{{
        t('assistant.pendingCheck')
      }}</Button>
      <Button size="sm" variant="ghost" :disabled="busy" @click="emit('dismiss')">{{
        t('assistant.pendingDismiss')
      }}</Button>
    </div>

    <UnknownOutcome
      v-if="send.status === 'uncertain'"
      :checking="busy"
      :can-resend="['prepared', 'sending', 'uncertain'].includes(send.state)"
      @check="emit('check')"
      @resend="emit('resend')"
    >
    </UnknownOutcome>
    <Button
      v-if="send.status === 'uncertain' && send.state === 'uncertain'"
      size="sm"
      variant="ghost"
      :disabled="busy"
      @click="emit('abandon')"
    >
      {{ t('assistant.pendingAbandon') }}
    </Button>
    <Button
      v-if="send.status === 'uncertain' && ['accepted', 'resolved'].includes(send.state)"
      size="sm"
      variant="ghost"
      :disabled="busy"
      @click="emit('dismiss')"
    >
      {{ t('assistant.pendingDismiss') }}
    </Button>

    <div
      v-if="send.status === 'rejected'"
      class="border-destructive/30 space-y-2 rounded-2xl border p-4"
      role="alert"
    >
      <p class="text-destructive flex items-start gap-2 font-medium">
        <CircleAlert class="mt-0.5 size-4 shrink-0" />{{ t('assistant.rejectedTitle') }}
      </p>
      <p v-if="send.failure" class="text-muted-foreground text-sm">
        {{ send.failure.status === 429 ? t('errors.limit') : t('errors.invalid') }}
      </p>
      <div class="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" @click="emit('restore')">{{
          t('assistant.editAgain')
        }}</Button>
        <Button size="sm" variant="ghost" @click="emit('dismiss')">{{ t('common.close') }}</Button>
      </div>
    </div>
    <details
      v-if="developer && (send.status === 'uncertain' || send.status === 'rejected')"
      class="text-muted-foreground space-y-1 text-xs"
      data-testid="delivery-diagnostic"
    >
      <summary class="cursor-pointer">{{ t('assistant.details') }}</summary>
      <template v-if="send.diagnostic">
        <p>
          {{
            t(`assistant.deliveryDiagnostic.${send.diagnostic.kind}`, {
              status: send.diagnostic.status,
            })
          }}
        </p>
        <p>{{ t(`assistant.deliveryDiagnostic.${send.diagnostic.phase}`) }}</p>
        <p>
          {{
            t('assistant.deliveryDiagnostic.duration', {
              seconds: (send.diagnostic.elapsedMs / 1000).toFixed(1),
            })
          }}
        </p>
        <p v-if="send.diagnostic.traceId" class="break-all">
          {{ t('assistant.deliveryDiagnostic.trace') }} <code>{{ send.diagnostic.traceId }}</code>
        </p>
      </template>
      <p class="break-all">
        {{ t('assistant.detailsDiagnostic') }} <code>{{ send.key }}</code>
      </p>
    </details>
  </article>
</template>
