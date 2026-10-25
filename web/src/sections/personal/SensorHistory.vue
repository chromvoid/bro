<script setup lang="ts">
import historyMessages from '@/locales/personal-history-ru.json'
import { useDisplayUnits } from './displayUnits'
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { History } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { momentLabel, momentPhrase } from '@/core/dates'
import { useSession } from '@/stores/session'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Badge } from '@/components/ui/badge'
import { toast } from 'vue-sonner'
import { usePersonal } from './usePersonal'
import { consistentPages, useResolved } from './resolution'
import DailyResolution from './DailyResolution.vue'
import RecordDetails from './RecordDetails.vue'
import RecordVersionHistory from './RecordVersionHistory.vue'
import RawTemperatureHistory from './RawTemperatureHistory.vue'
import {
  SensorPage,
  SensorSample,
  coherentSensorPages,
  sensorAmount,
  sensorRange,
  type SensorMetric,
} from './sensors'

const displayUnits = useDisplayUnits()
const props = defineProps<{
  metric: SensorMetric
  from: string
  to: string
  timezone: string
  today: string
}>()
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const personal = usePersonal()
const session = useSession()
const owner = computed(() => session.me?.id ?? null)
const readable = computed(() => !!owner.value && session.can('personal.read'))
const queryClient = useQueryClient()
const mode = ref<'result' | 'all'>('result')
const resolved = useResolved(
  computed(() => ({ from: props.from, to: props.to, metric: props.metric })),
  computed(() => mode.value === 'result' && readable.value),
)
const settings = computed(() => resolved.data.value?.pages[0])
const coherent = computed(() => consistentPages(resolved.data.value?.pages ?? []))
const writable = computed(() => session.can('personal.write'))
const busy = ref<string | null>(null)
async function exclude(sample: SensorSample) {
  if (!writable.value || busy.value) return
  busy.value = sample.id
  try {
    await personal.operate(
      sample.excluded ? 'sensor.restore' : 'sensor.exclude',
      { sample_id: sample.id, annotation_revision: sample.annotation_revision },
      { [sample.id]: sample.revision },
    )
    toast.success(t(sample.excluded ? 'resolution.restoredDone' : 'resolution.excludedDone'))
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = null
  }
}
const bounds = computed(() => sensorRange(props.from, props.to, props.timezone))
let historyEpoch = 0
const historyKey = computed(() => [
  'personal',
  'sensor-samples',
  owner.value,
  props.metric,
  bounds.value,
])
watch(
  historyKey,
  (_, previous) => {
    historyEpoch++
    queryClient.removeQueries({ queryKey: previous, exact: true })
  },
  { flush: 'sync' },
)
watch(
  readable,
  () => {
    historyEpoch++
    queryClient.removeQueries({ queryKey: historyKey.value, exact: true })
  },
  { flush: 'sync' },
)
const history = useInfiniteQuery({
  enabled: computed(() => mode.value === 'all' && readable.value),
  queryKey: historyKey,
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const user = owner.value
    const metric = props.metric
    const range = { ...bounds.value }
    const epoch = historyEpoch
    if (!readable.value) throw new DOMException('Sensor access changed', 'AbortError')
    const query = new URLSearchParams({
      metric,
      ...range,
      limit: '500',
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = SensorPage.parse(await api.get(`/v1/personal/sensor-samples?${query}`, signal))
    if (
      signal.aborted ||
      epoch !== historyEpoch ||
      !readable.value ||
      user !== owner.value ||
      metric !== props.metric ||
      range.from !== bounds.value.from ||
      range.to !== bounds.value.to ||
      Date.parse(page.from) !== Date.parse(range.from) ||
      Date.parse(page.to) !== Date.parse(range.to) ||
      page.items.some((sample) => sample.user_id !== user || sample.metric !== metric)
    )
      throw new DOMException('Sensor history changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const allPages = computed(() => (readable.value ? (history.data.value?.pages ?? []) : []))
const allCoherent = computed(() => coherentSensorPages(allPages.value))
const allFailed = computed(
  () => !allCoherent.value || history.isFetchNextPageError.value || history.isRefetchError.value,
)
function reloadHistory() {
  historyEpoch++
  void queryClient.resetQueries({ queryKey: historyKey.value, exact: true })
}
const samples = computed(() =>
  !readable.value
    ? []
    : mode.value === 'all'
      ? allFailed.value
        ? []
        : allPages.value.flatMap((page) => page.items)
      : coherent.value
        ? (resolved.data.value?.pages
            .flatMap((page) => page.series)
            .map((sample) => SensorSample.parse(sample)) ?? [])
        : [],
)
const activeQuery = computed(() => (mode.value === 'all' ? history : resolved))
const days = computed(() => settings.value?.daily.filter((day) => day.kind === props.metric) ?? [])
</script>

<template>
  <div v-if="readable" class="space-y-4" data-testid="sensor-history">
    <div class="flex flex-wrap gap-2" role="group" :aria-label="t('resolution.mode')">
      <Button variant="outline" :aria-pressed="mode === 'result'" @click="mode = 'result'">{{
        t('resolution.result')
      }}</Button>
      <Button variant="outline" :aria-pressed="mode === 'all'" @click="mode = 'all'">{{
        t('resolution.allSources')
      }}</Button>
    </div>
    <RawTemperatureHistory
      v-if="metric === 'temperature'"
      :from="from"
      :to="to"
      :timezone="timezone"
      :today="today"
    />
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <template v-if="mode === 'result' && coherent">
      <DailyResolution
        v-for="day in days"
        :key="day.date"
        :date="day.date"
        :kind="day.kind"
        :value="day.value"
        :unit="day.unit"
        :resolution="day.resolution"
        :timezone="timezone"
        :today="today"
      />
    </template>
    <p v-if="!coherent && mode === 'result'" class="text-warning text-sm" role="alert">
      {{ t('resolution.unmatchedPages') }}
      <Button variant="ghost" size="sm" @click="resolved.refetch()">{{
        t('resolution.refresh')
      }}</Button>
    </p>
    <RefreshFailed v-if="mode === 'result'" :query="resolved" />
    <LoadingState v-if="activeQuery.isPending.value" :rows="4" />
    <ErrorState
      v-else-if="activeQuery.isLoadingError.value"
      :error="activeQuery.error.value"
      @retry="mode === 'all' ? reloadHistory() : resolved.refetch()"
    />
    <EmptyState
      v-else-if="!samples.length && (mode === 'all' ? !allFailed : !days.length)"
      :icon="History"
      :title="t('personal.historyEmpty')"
      :description="t('ring.sensorEmpty')"
    />
    <ul
      v-else-if="samples.length"
      class="bg-card divide-y rounded-3xl border"
      data-testid="sensor-samples"
    >
      <li
        v-for="sample in samples"
        :key="sample.id"
        data-testid="sensor-sample"
        class="space-y-1 px-4 py-3"
      >
        <p
          :class="[
            'font-medium tabular-nums',
            sample.excluded && 'text-muted-foreground line-through',
          ]"
        >
          {{ sensorAmount(sample.value, sample.unit, displayUnits) ?? t('personal.noValue') }}
        </p>
        <p
          v-if="
            sample.value === null && (sample.reported_min !== null || sample.reported_max !== null)
          "
          class="text-muted-foreground text-xs"
        >
          {{ t('personalHistory.sensor.rangeOnly') }}
        </p>
        <Badge v-if="sample.excluded" variant="outline">{{ t('resolution.excluded') }}</Badge>
        <p
          v-if="sample.reported_min !== null || sample.reported_max !== null"
          class="text-muted-foreground text-sm"
        >
          {{
            t('ring.reportedRange', {
              min: sensorAmount(sample.reported_min, sample.unit, displayUnits) ?? '—',
              max: sensorAmount(sample.reported_max, sample.unit, displayUnits) ?? '—',
            })
          }}
        </p>
        <p class="text-muted-foreground text-sm">
          {{ momentLabel(sample.start_at, timezone, today) }} · {{ sample.source_name }}
        </p>
        <p v-if="sample.quality.includes('protocol_unverified')" class="text-warning text-sm">
          {{ t('ring.protocolUnverified') }}
        </p>
        <p v-if="sample.end_at !== sample.start_at" class="text-muted-foreground text-sm">
          {{ t('ring.intervalEnd', { when: momentPhrase(sample.end_at, timezone, today) }) }}
        </p>
        <RecordDetails :record="sample" :timezone="timezone" :today="today" />
        <RecordVersionHistory
          family="sensor"
          :record-id="sample.id"
          :source-id="sample.source_id"
          :metric="sample.metric"
          :timezone="timezone"
          :today="today"
        />
        <Button
          v-if="writable"
          size="sm"
          variant="ghost"
          :disabled="busy !== null"
          @click="exclude(sample)"
          >{{ t(sample.excluded ? 'resolution.restore' : 'resolution.exclude') }}</Button
        >
      </li>
    </ul>
    <p v-if="writable" class="text-muted-foreground text-sm">
      {{ t('resolution.sensorExcludeHint') }}
    </p>
    <p v-if="mode === 'all' && allFailed" class="text-warning text-sm" role="alert">
      {{ t('personalHistory.sensor.changed') }}
      <Button size="sm" variant="ghost" @click="reloadHistory">{{
        t('personalHistory.sensor.reload')
      }}</Button>
    </p>
    <p
      v-else-if="activeQuery.isFetchNextPageError.value"
      class="text-destructive text-sm"
      role="alert"
    >
      {{
        t(
          mode === 'result' &&
            activeQuery.error.value instanceof ApiError &&
            activeQuery.error.value.status === 409
            ? 'resolution.unmatchedPages'
            : 'ring.nextPageFailed',
        )
      }}
      <Button v-if="mode === 'result'" size="sm" variant="ghost" @click="resolved.refetch()">{{
        t('resolution.refresh')
      }}</Button>
    </p>
    <Button
      v-if="activeQuery.hasNextPage.value && (mode !== 'all' || !allFailed)"
      variant="outline"
      :disabled="activeQuery.isFetchingNextPage.value"
      @click="activeQuery.fetchNextPage()"
      >{{ t('ring.loadMore') }}</Button
    >
  </div>
</template>
