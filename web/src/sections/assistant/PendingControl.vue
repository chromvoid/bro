<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { isKnownUnadmitted, type Submission } from '@/core/journal'
import { controlReceipt } from './controls'

const props = defineProps<{ entry: Submission; busy?: boolean }>()
const emit = defineEmits<{ check: []; retry: []; dismiss: [] }>()
const { t, te } = useI18n()
const receipt = computed(() => controlReceipt(props.entry))
const rejected = computed(
  () => receipt.value?.state === 'rejected' || isKnownUnadmitted(props.entry),
)
const reason = computed(() => {
  const key = `assistant.controls.reasons.${receipt.value?.reason ?? ''}`
  return te(key) ? t(key) : t('assistant.controls.rejected')
})
</script>

<template>
  <div
    class="bg-muted/50 space-y-2 rounded-xl px-3 py-2 text-sm"
    role="status"
    data-testid="pending-control"
  >
    <p v-if="rejected">{{ reason }}</p>
    <p v-else-if="receipt?.state === 'settled' && entry.context.purpose === 'edit'">
      {{ t('assistant.controls.editReady') }}
    </p>
    <p v-else>{{ t('assistant.controls.awaitingReceipt') }}</p>
    <p v-if="entry.context.purpose === 'edit' && !rejected" class="text-muted-foreground">
      {{ t('assistant.controls.savedEnvelope') }}
    </p>
    <div class="flex flex-wrap gap-2">
      <Button
        v-if="!rejected"
        variant="ghost"
        size="sm"
        class="min-h-11"
        :disabled="busy"
        @click="emit('check')"
      >
        {{
          t(
            receipt?.state === 'settled'
              ? 'assistant.controls.restoreEdit'
              : 'assistant.controls.check',
          )
        }}
      </Button>
      <Button
        v-if="!receipt && !rejected"
        variant="ghost"
        size="sm"
        class="min-h-11"
        :disabled="busy"
        @click="emit('retry')"
        >{{ t('assistant.controls.retry') }}</Button
      >
      <Button
        v-if="rejected"
        variant="ghost"
        size="sm"
        class="min-h-11"
        :disabled="busy"
        @click="emit('dismiss')"
        >{{ t('assistant.controls.dismiss') }}</Button
      >
    </div>
  </div>
</template>
