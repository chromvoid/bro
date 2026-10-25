<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { formatAmount } from '@/core/numbers'

/**
 * A sum that can only grow: «≥ 959» while some food has no data for it (the screen says why
 * once, under the day's total). Screen readers hear «не меньше 959».
 */
withDefaults(
  defineProps<{
    value: string | number | null | undefined
    complete?: boolean
    fraction?: number
  }>(),
  { complete: true, fraction: 1 },
)
const { t } = useI18n()
</script>

<template>
  <span class="whitespace-nowrap tabular-nums"
    ><template v-if="!complete"
      ><span class="text-warning" aria-hidden="true">≥&nbsp;</span
      ><span class="sr-only">{{ t('food.atLeast') }}&nbsp;</span></template
    >{{ formatAmount(value, fraction) }}</span
  >
</template>
