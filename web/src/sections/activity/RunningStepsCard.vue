<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { fullDayLabel, momentPhrase } from '@/core/dates'
import { describeError } from '@/core/errors'
import { formatAmount } from '@/core/numbers'
import ResolutionExplanation from '@/sections/personal/ResolutionExplanation.vue'
import type { ResolvedPage } from '@/sections/personal/resolution'

const props = defineProps<{
  page?: ResolvedPage
  loading: boolean
  error?: unknown
  date: string
  today: string
  timezone: string
}>()
defineEmits<{ retry: [] }>()
const { t, te } = useI18n()
const day = computed(() =>
  props.page?.daily.find((row) => row.date === props.date && row.kind === 'running_steps'),
)
const value = computed(() => {
  const row = day.value
  const amount = row?.value
  return row?.status === 'available' &&
    amount != null &&
    amount.trim() !== '' &&
    Number.isSafeInteger(Number(amount)) &&
    Number(amount) >= 0
    ? amount
    : null
})
const status = computed(() => {
  const row = day.value
  if (row?.status === 'conflict' || row?.status === 'unavailable') return row.status
  return 'no_data'
})
const statusText = computed(() =>
  te(`activity.running.status.${status.value}`)
    ? t(`activity.running.status.${status.value}`)
    : t(`resolution.status.${status.value}`),
)
const observed = computed(() => {
  const data = day.value?.resolution.data
  const timestamp =
    typeof data === 'object' && data !== null && 'observed_at' in data
      ? data.observed_at
      : day.value?.resolution.freshness?.measured_at
  return typeof timestamp === 'string' ? timestamp : null
})
const partial = computed(
  () =>
    day.value?.complete === false ||
    day.value?.resolution.status === 'partial' ||
    day.value?.resolution.coverage?.basis === 'daily_counter_snapshot',
)
</script>

<template>
  <section
    class="bg-card w-full max-w-sm space-y-2 rounded-2xl border p-4"
    data-testid="running-steps-card"
  >
    <h2 class="font-semibold">{{ t('activity.running.steps') }}</h2>
    <p class="text-muted-foreground text-xs">{{ fullDayLabel(date) }}</p>
    <div v-if="loading" role="status">
      <Skeleton class="h-9 w-32" /><span class="sr-only">{{ t('state.loading') }}</span>
    </div>
    <div v-else-if="error" class="space-y-2 text-sm" role="alert">
      <p>{{ t(describeError(error)) }}</p>
      <Button size="sm" variant="outline" @click="$emit('retry')">{{ t('common.retry') }}</Button>
    </div>
    <template v-else>
      <p
        v-if="value !== null"
        class="text-3xl font-semibold tabular-nums"
        data-testid="running-steps-value"
      >
        {{ formatAmount(value, 0) }}
        <span class="text-base font-normal">{{ t('activity.running.stepUnit') }}</span>
      </p>
      <p
        v-else
        class="text-muted-foreground text-sm"
        :data-status="status"
        data-testid="running-steps-status"
      >
        {{ statusText }}
      </p>
      <p
        v-if="value !== null && partial"
        class="text-muted-foreground text-sm"
        data-testid="running-steps-partial"
      >
        {{ t('activity.running.snapshot') }}
      </p>
      <p v-if="observed" class="text-muted-foreground text-xs">
        {{ t('activity.running.observed', { when: momentPhrase(observed, timezone, today) }) }}
      </p>
      <ResolutionExplanation
        v-if="day?.resolution"
        :resolution="day.resolution"
        :timezone="timezone"
        :today="today"
      />
      <p class="text-muted-foreground text-sm">{{ t('activity.running.noAddition') }}</p>
    </template>
  </section>
</template>
