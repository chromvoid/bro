<script setup lang="ts">
import { CircleHelp } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

defineProps<{ checking?: boolean; description?: string; canResend?: boolean }>()
const emit = defineEmits<{ check: []; resend: [] }>()
const { t } = useI18n()
</script>

<template>
  <div
    class="bg-warning-surface text-foreground flex flex-col gap-3 rounded-2xl p-4"
    role="status"
    data-state="unknown-outcome"
  >
    <div class="flex items-start gap-3">
      <CircleHelp class="text-warning mt-0.5 size-5 shrink-0" />
      <div class="space-y-1">
        <p class="font-medium">{{ t('outcome.unknownTitle') }}</p>
        <p class="text-muted-foreground text-sm">{{ description ?? t('outcome.unknownText') }}</p>
      </div>
    </div>
    <div class="flex flex-wrap gap-2">
      <Button size="sm" :disabled="checking" @click="emit('check')">{{
        t('outcome.check')
      }}</Button>
      <Button
        v-if="canResend"
        size="sm"
        variant="outline"
        :disabled="checking"
        @click="emit('resend')"
      >
        {{ t('outcome.resend') }}
      </Button>
    </div>
  </div>
</template>
