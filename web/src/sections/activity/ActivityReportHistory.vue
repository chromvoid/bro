<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useSession } from '@/stores/session'
import { usePersonal } from '@/sections/personal/usePersonal'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { api } from '@/core/client'
import { dayLabel, momentLabel } from '@/core/dates'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RecordDetails from '@/sections/personal/RecordDetails.vue'
import DeviceWorkoutCard from './DeviceWorkoutCard.vue'
import {
  ActivityReportPage,
  activityReportKey,
  archiveWorkout,
  coherentReportPages,
  type ActivityReportFilter,
  type ActivityReport,
} from './activityReports'

const { t } = useI18n()
const props = defineProps<{
  from: string
  to: string
  timezone: string
  today: string
  kind?: ActivityReportFilter
}>()
const session = useSession()
const personal = usePersonal()
const busy = ref<string | null>(null)
const source = ref('')
const knownSources = ref(new Map<string, string>())
const user = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!user.value && session.can('personal.read'))
const queryClient = useQueryClient()
const reader = useId()
const generation = ref(0)
const historyKey = computed(() => [
  'personal',
  'activity-report-archive',
  user.value,
  props.from,
  props.to,
  props.kind ?? null,
  source.value,
  reader,
  generation.value,
])
watch(
  () =>
    [user.value, props.from, props.to, props.kind ?? null, source.value, permitted.value] as const,
  (_current, previous) => {
    const epoch = generation.value++
    queryClient.removeQueries({
      queryKey: ['personal', 'activity-report-archive', ...previous.slice(0, 5), reader, epoch],
      exact: true,
    })
  },
  { flush: 'sync' },
)
watch(
  () => [user.value, props.from, props.to, props.kind ?? null, permitted.value] as const,
  () => {
    source.value = ''
    knownSources.value = new Map()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  const key = historyKey.value
  generation.value++
  queryClient.removeQueries({ queryKey: key, exact: true })
})
const history = useInfiniteQuery({
  enabled: permitted,
  queryKey: historyKey,
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const context = {
      epoch: generation.value,
      owner: user.value,
      from: props.from,
      to: props.to,
      kind: props.kind ?? null,
      source: source.value,
    }
    if (!permitted.value) throw new DOMException('Report access changed', 'AbortError')
    const query = new URLSearchParams({
      from: context.from,
      to: context.to,
      limit: '100',
      ...(context.kind ? { kind: context.kind } : {}),
      ...(context.source ? { source_id: context.source } : {}),
      ...(pageParam !== null ? { cursor: pageParam } : {}),
    })
    const page = ActivityReportPage.parse(
      await api.get(`/v1/personal/activity-reports?${query}`, signal),
    )
    if (
      signal.aborted ||
      context.epoch !== generation.value ||
      !permitted.value ||
      context.owner !== user.value ||
      context.from !== props.from ||
      context.to !== props.to ||
      context.kind !== (props.kind ?? null) ||
      context.source !== source.value ||
      page.from !== context.from ||
      page.to !== context.to ||
      page.items.some(
        (item) =>
          (context.source && item.source_id !== context.source) ||
          (context.kind && item.kind !== context.kind),
      )
    )
      throw new DOMException('Report history context changed', 'AbortError')
    return { ...page, readContext: context }
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const pages = computed(() => history.data.value?.pages ?? [])
const coherent = computed(
  () =>
    pages.value.every(
      (page) =>
        page.readContext.epoch === generation.value &&
        page.readContext.owner === user.value &&
        page.readContext.from === props.from &&
        page.readContext.to === props.to &&
        page.readContext.kind === (props.kind ?? null) &&
        page.readContext.source === source.value,
    ) && coherentReportPages(pages.value),
)
const failed = computed(
  () =>
    !coherent.value ||
    history.isLoadingError.value ||
    history.isFetchNextPageError.value ||
    history.isRefetchError.value,
)
const reports = computed(() =>
  permitted.value && !failed.value ? pages.value.flatMap((page) => page.items) : [],
)
watch(reports, (loaded) => {
  if (!permitted.value) return
  const updated = new Map(knownSources.value)
  for (const report of loaded) updated.set(report.source_id, report.source_name ?? report.source_id)
  knownSources.value = updated
})
const sources = computed(() => [...knownSources.value.entries()])
function restartHistory() {
  const key = historyKey.value
  generation.value++
  queryClient.removeQueries({ queryKey: key, exact: true })
  void queryClient.resetQueries({ queryKey: historyKey.value, exact: true })
}
async function exclude(report: ActivityReport) {
  if (
    !permitted.value ||
    failed.value ||
    !reports.value.includes(report) ||
    !session.can('personal.write') ||
    busy.value ||
    report.current_revision === undefined ||
    report.annotation_revision === undefined
  )
    return
  busy.value = report.id
  try {
    await personal.operate(
      report.excluded ? 'activity_report.restore' : 'activity_report.exclude',
      { report_id: report.id, annotation_revision: report.annotation_revision },
      { [report.id]: report.current_revision },
    )
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = null
  }
}
const scalar = (value: unknown) =>
  typeof value === 'number' || typeof value === 'string'
    ? String(value)
    : t('activityReports.notReported')
const dispositionLabels = new Map([
  ['current', t('activityReports.disposition.current')],
  ['excluded', t('activityReports.disposition.excluded')],
  ['conflict', t('activityReports.disposition.conflict')],
  ['superseded', t('activityReports.disposition.superseded')],
])
</script>

<template>
  <section v-if="permitted" class="space-y-3" data-testid="activity-report-history">
    <h2 class="font-semibold">{{ t('activityReports.title') }}</h2>
    <p class="text-muted-foreground text-sm">
      {{ t('activityReports.description') }}
    </p>
    <NativeSelect
      v-if="sources.length > 1 || source"
      v-model="source"
      :aria-label="t('activityReports.source')"
      class="max-w-sm"
      ><option value="">{{ t('activityReports.allSources') }}</option>
      <option v-for="[id, name] in sources" :key="id" :value="id">{{ name }}</option></NativeSelect
    >
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <LoadingState v-if="history.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="history.isLoadingError.value"
      :error="history.error.value"
      @retry="restartHistory"
    />
    <p v-else-if="failed" class="text-warning text-sm" role="alert">
      {{ t('activityReports.incomplete') }}
      <Button
        variant="ghost"
        size="sm"
        :disabled="history.isFetching.value"
        @click="restartHistory"
        >{{ t('activityReports.refresh') }}</Button
      >
    </p>
    <p v-else-if="!reports.length && pages.length" class="text-muted-foreground text-sm">
      {{ t('activityReports.empty') }}
    </p>
    <article
      v-for="report in reports"
      :key="activityReportKey(report)"
      class="space-y-2"
      data-testid="activity-report-version"
    >
      <p class="text-muted-foreground flex flex-wrap gap-2 text-sm">
        <span>{{ dayLabel(report.local_date, today) }} · v{{ report.source_version }}</span
        ><span v-if="report.is_current === false">{{ t('activityReports.previousVersion') }}</span
        ><span v-if="report.excluded" class="text-warning">{{
          t('activityReports.disposition.excluded')
        }}</span
        ><span v-if="report.resolution_disposition" data-testid="activity-report-disposition">{{
          dispositionLabels.get(report.resolution_disposition) ??
          t('activityReports.unknownDisposition')
        }}</span>
      </p>
      <details
        v-if="
          report.resolution_disposition && !dispositionLabels.has(report.resolution_disposition)
        "
        class="text-muted-foreground space-y-1 text-sm"
        data-testid="activity-report-disposition-details"
      >
        <summary class="min-h-11 cursor-pointer py-2">
          {{ t('activityReports.technicalDisposition') }}
        </summary>
        <p>
          <code>{{ report.resolution_disposition }}</code>
        </p>
      </details>
      <DeviceWorkoutCard
        v-if="archiveWorkout(report)"
        :report="archiveWorkout(report)!"
        :original-report="report"
        :timezone="timezone"
        :today="today"
      />
      <div v-else class="bg-card space-y-2 rounded-2xl border p-4">
        <h3 class="font-medium">
          {{
            report.kind === 'running_steps'
              ? t('activityReports.runningSteps')
              : report.kind === 'active_energy'
                ? t('activityReports.activeEnergyOriginal')
                : report.kind === 'distance'
                  ? t('activityReports.distanceOriginal')
                  : report.kind
          }}
        </h3>
        <p class="text-lg font-semibold tabular-nums">
          {{ scalar(report.data.value)
          }}<span v-if="typeof report.data.unit === 'string'" class="ml-1 text-base font-normal">{{
            report.data.unit === 'kcal'
              ? t('activityReports.kcal')
              : report.data.unit === 'm'
                ? t('activityReports.meters')
                : report.data.unit
          }}</span>
        </p>
        <p class="text-muted-foreground text-sm">
          {{ report.source_name }} · {{ momentLabel(report.observed_at, timezone, today) }} ·
          {{
            report.shape === 'day_counter_snapshot'
              ? t('activityReports.counterSnapshot')
              : report.shape
          }}
        </p>
        <RecordDetails
          :record="{
            ...report,
            start_at: report.start_at ?? report.observed_at,
            end_at: report.end_at ?? report.observed_at,
          }"
          :timezone="timezone"
          :today="today"
        />
      </div>
      <Button
        v-if="
          session.can('personal.write') &&
          report.current_revision !== undefined &&
          report.annotation_revision !== undefined
        "
        size="sm"
        variant="outline"
        :disabled="busy !== null || !!personal.unresolved.value.length"
        @click="exclude(report)"
        >{{ t(report.excluded ? 'activityReports.restore' : 'activityReports.exclude') }}</Button
      >
    </article>
    <Button
      v-if="history.hasNextPage.value && !failed"
      variant="outline"
      :disabled="history.isFetching.value"
      @click="history.fetchNextPage()"
      >{{ t('activityReports.loadMore') }}</Button
    >
  </section>
</template>
