<script setup lang="ts">
import { computed } from 'vue'
import type { PositionIssue } from '../contracts'
import { t } from '../labels'

/** Why a calculation is incomplete; one explanation per affected coin, without raw ledger identifiers. */
const props = defineProps<{ issues: PositionIssue[]; missing?: boolean }>()
const messages = computed(() => [
  ...new Set(
    props.issues.map((issue) =>
      issue.code === 'p2p_cancellation_unlinked'
        ? t('calculation_p2p_cancellation', issue.coin)
        : t('calculation_missing'),
    ),
  ),
])
</script>

<template>
  <div v-if="messages.length || missing" class="text-warning space-y-1 text-sm" role="status">
    <p v-for="message in messages" :key="message">{{ message }}</p>
    <p v-if="missing && !messages.length">{{ t('calculation_missing') }}</p>
  </div>
</template>
