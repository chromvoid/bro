<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { useDisplayUnits } from './displayUnits'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import messages from '@/locales/sensor-day-summary-ru.json'
import DailyResolution from './DailyResolution.vue'
import { consistentPages, useResolved } from './resolution'
import { SENSOR_METRICS, sensorAmount } from './sensors'
import { personalHistoryLink } from './personalNavigation'

const displayUnits = useDisplayUnits()
const props = defineProps<{ day: string; timezone: string; today: string }>()
const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const query = useResolved(computed(() => ({ from: props.day, to: props.day })))
const coherent = computed(() => consistentPages(query.data.value?.pages ?? []))
const daily = computed(() => (coherent.value ? (query.data.value?.pages[0]?.daily ?? []) : []))
// Only what the ring measured that day: a metric without a value has no row.
const rows = computed(() =>
  SENSOR_METRICS.flatMap((metric) => {
    const day = daily.value.find((value) => value.kind === metric)
    return day?.value != null ? [{ metric, day }] : []
  }),
)
const loaded = computed(() => !query.isPending.value && !query.isLoadingError.value)
// A day the ring reported nothing has no card at all.
const shown = computed(() => !loaded.value || !coherent.value || rows.value.length > 0)
</script>

<template>
  <section
    v-if="shown"
    class="bg-card space-y-3 rounded-3xl border p-4"
    data-testid="sensor-day-summary"
  >
    <h2 class="font-semibold">{{ t('sensorDaySummary.title') }}</h2>
    <RefreshFailed :query="query" />
    <LoadingState v-if="query.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="query.isLoadingError.value"
      :error="query.error.value"
      @retry="query.refetch()"
    />
    <p v-else-if="!coherent" class="text-warning text-sm" role="alert">
      Решение об источниках изменилось. Обновите показания.
    </p>
    <!-- Rows of one list, not cards inside the card: the value on the right, the chevron last. -->
    <div v-else class="divide-y">
      <details v-for="row in rows" :key="row.metric" class="group space-y-2 py-1">
        <summary
          class="flex min-h-11 cursor-pointer list-none items-center gap-3 [&::-webkit-details-marker]:hidden"
        >
          <span class="min-w-0 flex-1 font-medium">{{ t(`ring.metric.${row.metric}`) }}</span>
          <span class="text-muted-foreground text-sm tabular-nums">{{
            sensorAmount(row.day.value, row.day.unit, displayUnits)
          }}</span>
          <ChevronDown
            class="text-muted-foreground size-4 shrink-0 transition-transform group-open:rotate-180"
            aria-hidden="true"
          />
        </summary>
        <DailyResolution
          :date="day"
          :kind="row.metric"
          :value="row.day.value"
          :unit="row.day.unit"
          :resolution="row.day.resolution"
          :timezone="timezone"
          :today="today"
        />
        <RouterLink
          :to="personalHistoryLink(day, 'daily', row.metric, 7)"
          class="text-primary inline-flex min-h-11 items-center text-sm underline"
          >История и все источники</RouterLink
        >
      </details>
    </div>
  </section>
</template>
