<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatUsageNumber, usageRows, type UsageSummary } from './usage'

const props = defineProps<{ usage?: UsageSummary | null }>()
const { t, locale } = useI18n()
const rows = computed(() =>
  usageRows(props.usage).map((row) => {
    const number = formatUsageNumber(row.value, locale.value)
    return {
      ...row,
      formatted:
        number === null
          ? t('assistant.usage.unknown')
          : row.unit === 'milliseconds'
            ? t('assistant.usage.milliseconds', { value: number })
            : number,
    }
  }),
)
</script>

<template>
  <section v-if="rows.length" class="space-y-2" data-testid="usage-details">
    <div>
      <h3 class="text-sm font-medium">{{ t('assistant.usage.title') }}</h3>
      <p class="text-muted-foreground text-xs">{{ t('assistant.usage.note') }}</p>
    </div>
    <dl class="space-y-1 text-xs" :aria-label="t('assistant.usage.title')">
      <div
        v-for="row in rows"
        :key="row.field"
        class="flex items-baseline justify-between gap-4"
        :data-usage-metric="row.field"
      >
        <dt class="text-muted-foreground">{{ t(`assistant.usage.${row.label}`) }}</dt>
        <dd class="text-right tabular-nums">{{ row.formatted }}</dd>
      </div>
    </dl>
  </section>
</template>
