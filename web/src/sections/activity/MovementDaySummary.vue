<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { dayLabel, fullDayLabel, momentPhrase } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { unitOf, type History } from '@/sections/personal/api'
import ResolutionExplanation from '@/sections/personal/ResolutionExplanation.vue'

const props = defineProps<{
  history: History | undefined
  kind: 'steps' | 'active_minutes'
  date: string
  today: string
  timezone: string
}>()
const { t } = useI18n()
// The last measured day may precede the selected end date. Keep that distinction
// visible rather than presenting the latest earlier amount as today's movement.
const day = computed(() =>
  props.history?.daily.find((row) => row.date === props.date && row.kind === props.kind),
)
const value = computed(() => {
  const row = day.value
  return row?.status === 'available' &&
    row.resolution?.status !== 'conflict' &&
    row.value !== null &&
    row.value.trim() !== '' &&
    Number.isFinite(Number(row.value))
    ? row.value
    : null
})
const status = computed(() => {
  const row = day.value
  if (row?.resolution?.status === 'conflict' || row?.status === 'conflict') return 'conflict'
  if (!row || row.status === 'no_data' || (row.status === 'available' && value.value === null))
    return 'no_data'
  return row.status === 'conflict' || row.status === 'unavailable' ? row.status : 'unknown'
})
</script>

<template>
  <div class="mt-3 space-y-2" :data-testid="`movement-day-${kind}`">
    <p class="flex flex-wrap items-baseline gap-x-2 text-sm" data-testid="movement-day-date">
      <span class="font-medium">{{ dayLabel(date, today) }}</span>
      <span class="text-muted-foreground">{{ fullDayLabel(date) }}</span>
    </p>
    <p
      v-if="value !== null"
      class="text-2xl font-semibold tabular-nums"
      data-testid="movement-day-value"
    >
      {{ formatAmount(value, 0) }} {{ unitOf(kind) }}
    </p>
    <p
      v-else
      class="text-muted-foreground text-sm"
      :data-status="status"
      data-testid="movement-day-status"
    >
      {{ t(`resolution.status.${status}`) }}
    </p>
    <p v-if="day?.resolution?.freshness?.measured_at" class="text-muted-foreground text-xs">
      {{
        t('resolution.measuredAt', {
          when: momentPhrase(day.resolution.freshness.measured_at, timezone, today),
        })
      }}
    </p>
    <p
      v-if="day?.resolution?.protocol?.verified === false && day.resolution.selected.length"
      class="text-warning text-xs"
    >
      {{ t('resolution.protocolUnverified') }}
    </p>
    <ResolutionExplanation
      v-if="day?.resolution"
      :resolution="day.resolution"
      :timezone="timezone"
      :today="today"
    />
  </div>
</template>
