<script setup lang="ts">
import { Footprints } from '@lucide/vue'
import { computed, toRef } from 'vue'
import { useI18n } from 'vue-i18n'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { momentPhrase } from '@/core/dates'
import { describeError } from '@/core/errors'
import { formatAmount } from '@/core/numbers'
import { unitOf } from '@/sections/personal/api'
import SourceLine from '@/sections/personal/SourceLine.vue'
import { useOverview } from '@/sections/personal/usePersonal'
import { coverageOf } from './movement'

const props = defineProps<{ today: string; timezone: string }>()
const { t } = useI18n()
const overview = useOverview(toRef(props, 'today'))
// Never label a cached or mismatched earlier-day response as today's steps.
const metric = computed(() =>
  overview.data.value?.date === props.today ? overview.data.value.metrics.steps : undefined,
)
const value = computed(() => {
  const row = metric.value
  return row?.status === 'available' &&
    row.value !== null &&
    row.value.trim() !== '' &&
    Number.isFinite(Number(row.value))
    ? row.value
    : null
})
const status = computed(() => {
  const row = metric.value
  if (!row || row.status === 'no_data' || (row.status === 'available' && value.value === null))
    return 'no_data'
  return row.status === 'conflict' || row.status === 'unavailable' ? row.status : 'unknown'
})
// The same marks as the day's tile in «Активность»: only a partial day is noted.
const partial = computed(() => value.value !== null && coverageOf(metric.value) === 'partial')
const goal = computed(() => {
  const target = metric.value?.target
  return target !== null &&
    target !== undefined &&
    Number.isFinite(Number(target)) &&
    Number(target) > 0
    ? target
    : null
})
</script>

<template>
  <section class="bg-card space-y-2 rounded-3xl border p-5" data-testid="today-steps-card">
    <!-- How the total was resolved is in the day's details in «Активность», not on Today. -->
    <div class="flex flex-wrap items-center justify-between">
      <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
        <Footprints class="size-4" />{{ t('activity.stepsToday') }}
      </h2>
      <RouterLink
        :to="{ name: 'activity' }"
        class="text-primary text-sm font-medium"
        data-testid="today-steps-activity"
        >{{ t('nav.activity') }}</RouterLink
      >
    </div>
    <RefreshFailed :query="overview" />
    <div v-if="overview.isPending.value" role="status" data-state="loading">
      <Skeleton class="h-9 w-32" />
      <span class="sr-only">{{ t('state.loading') }}</span>
    </div>
    <div
      v-else-if="overview.isLoadingError.value"
      class="space-y-2 text-sm"
      role="alert"
      data-state="error"
    >
      <p class="text-muted-foreground">{{ t(describeError(overview.error.value)) }}</p>
      <Button
        size="sm"
        variant="outline"
        :disabled="overview.isFetching.value"
        @click="overview.refetch()"
      >
        {{ t('common.retry') }}
      </Button>
    </div>
    <template v-else>
      <p
        v-if="value !== null"
        class="text-4xl font-semibold tabular-nums"
        data-testid="today-steps-value"
      >
        {{ formatAmount(value, 0) }}
        <span class="text-base font-normal">{{ unitOf('steps') }}</span>
      </p>
      <p
        v-else
        class="text-muted-foreground text-sm"
        :data-status="status"
        data-testid="today-steps-status"
      >
        {{ t(`resolution.status.${status}`) }}
      </p>
      <p v-if="goal !== null" class="text-muted-foreground text-xs" data-testid="today-steps-goal">
        {{ t('personal.target', { value: `${formatAmount(goal, 0)} ${unitOf('steps')}` }) }}
      </p>
      <p v-if="partial" class="text-muted-foreground text-xs" data-testid="today-steps-partial">
        {{ t('activityDay.partial') }}
      </p>
      <p v-if="metric?.resolution?.freshness?.measured_at" class="text-muted-foreground text-xs">
        {{
          t('resolution.measuredAt', {
            when: momentPhrase(metric.resolution.freshness.measured_at, timezone, today),
          })
        }}
      </p>
      <SourceLine metric="steps" :contributions="metric?.resolution?.contributions" :day="today" />
    </template>
  </section>
</template>
