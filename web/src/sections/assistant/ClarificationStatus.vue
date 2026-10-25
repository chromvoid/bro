<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import messages from '@/locales/clarification-ru.json'
import type { ClarificationItem } from './clarifications'

const props = defineProps<{
  item: ClarificationItem
  checking?: boolean
}>()
const emit = defineEmits<{
  check: [key: string]
  open: [messageId: string]
}>()
const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const reason = computed(() => {
  const code = props.item.receipt.reason
  return code && ['generation_changed', 'budget_exhausted', 'run_interrupted'].includes(code)
    ? t(`liveClarification.reason.${code}`)
    : ''
})
</script>

<template>
  <article
    class="min-w-0 space-y-2 rounded-xl border p-3 text-sm"
    data-testid="clarification-status"
    :data-key="item.key"
  >
    <p class="text-muted-foreground" role="status">
      {{ t(`liveClarification.state.${item.state}`) }}
    </p>
    <p v-if="item.state === 'queued'">{{ t('liveClarification.queued') }}</p>
    <p v-if="reason">{{ reason }}</p>
    <p v-if="item.receipt.question" class="bg-accent rounded-xl p-3">
      {{ item.receipt.question }}
    </p>
    <Button
      v-if="item.state === 'accepted' || item.state === 'delivered'"
      variant="outline"
      :disabled="checking"
      @click="emit('check', item.key)"
      >{{ t('liveClarification.check') }}</Button
    >
    <Button
      v-if="item.state === 'queued'"
      variant="ghost"
      @click="emit('open', item.receipt.source_message_id)"
      >{{ t('liveClarification.open') }}</Button
    >
    <Button variant="ghost" @click="emit('open', item.target.messageId)">{{
      t('liveClarification.origin')
    }}</Button>
  </article>
</template>
