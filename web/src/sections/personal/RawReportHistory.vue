<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import historyMessages from '@/locales/personal-history-ru.json'
import { useInfiniteQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { api } from '@/core/client'
import { momentLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import RecordDetails from './RecordDetails.vue'
import { RawReportPage, coherentRawPages, rawReportKey } from './rawReports'

const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const props = defineProps<{
  metric: 'temperature' | 'workouts'
  from: string
  to: string
  timezone: string
  today: string
}>()
const temperature = computed(() => props.metric === 'temperature')
const session = useSession()
const user = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!user.value && session.can('personal.read'))
const source = ref('')
const knownSources = ref(new Map<string, string>())
watch(
  () => [user.value, props.metric, props.from, props.to],
  () => {
    source.value = ''
    knownSources.value = new Map()
  },
)
const query = useInfiniteQuery({
  enabled: permitted,
  queryKey: computed(() => [
    'personal',
    'raw-ring-reports',
    user.value,
    props.metric,
    props.from,
    props.to,
    source.value,
  ]),
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const owner = user.value
    const selectedSource = source.value
    const metric = props.metric
    const params = new URLSearchParams({
      metric,
      from: props.from,
      to: props.to,
      limit: '100',
      ...(selectedSource ? { source_id: selectedSource } : {}),
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = RawReportPage.parse(await api.get(`/v1/personal/raw-reports?${params}`, signal))
    if (
      !permitted.value ||
      owner !== user.value ||
      selectedSource !== source.value ||
      metric !== props.metric ||
      page.items.some(
        (item) =>
          item.user_id !== owner ||
          item.metric !== metric ||
          (selectedSource && item.source_id !== selectedSource),
      )
    )
      throw new DOMException('Raw report source changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const pages = computed(() => (permitted.value ? (query.data.value?.pages ?? []) : []))
const coherent = computed(() => coherentRawPages(pages.value))
const reports = computed(() => (coherent.value ? pages.value.flatMap((page) => page.items) : []))
watch(pages, (value) => {
  const updated = new Map(knownSources.value)
  for (const report of value.flatMap((page) => page.items))
    updated.set(report.source_id, report.source_name)
  knownSources.value = updated
})
</script>

<template>
  <section
    v-if="permitted"
    class="space-y-3 rounded-2xl border p-4"
    :data-testid="temperature ? 'raw-temperature-history' : 'raw-workout-history'"
  >
    <h2 class="font-semibold">
      {{
        temperature
          ? t('personalHistory.raw.temperatureTitle')
          : t('personalHistory.raw.workoutTitle')
      }}
    </h2>
    <p v-if="temperature" class="text-warning text-sm">
      {{ t('personalHistory.raw.temperatureExplanation') }}
    </p>
    <p v-else class="text-warning text-sm">
      {{ t('personalHistory.raw.workoutExplanation') }}
    </p>
    <RefreshFailed :query="query" />
    <LoadingState v-if="query.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="query.isLoadingError.value"
      :error="query.error.value"
      @retry="query.refetch()"
    />
    <p v-else-if="pages[0]" class="text-muted-foreground text-sm">
      {{ t('personalHistory.raw.count', { count: pages[0].count }) }}
    </p>
    <NativeSelect
      v-if="knownSources.size > 1 || source"
      v-model="source"
      :aria-label="
        temperature
          ? t('personalHistory.raw.temperatureSource')
          : t('personalHistory.raw.workoutSource')
      "
      class="max-w-sm"
      ><option value="">{{ t('personalHistory.raw.allSources') }}</option>
      <option v-for="[id, name] in knownSources" :key="id" :value="id">
        {{ name }}
      </option></NativeSelect
    >
    <details
      v-for="report in reports"
      :key="rawReportKey(report)"
      class="space-y-2 rounded-xl border p-3"
      :data-testid="temperature ? 'raw-temperature-version' : 'raw-workout-version'"
    >
      <summary class="min-h-11 cursor-pointer py-2">
        {{ report.source_name }} · {{ momentLabel(report.observed_at, timezone, today) }} · v{{
          report.source_version
        }}
        ·
        {{
          report.is_current
            ? t('personalHistory.versions.current')
            : t('personalHistory.versions.previous')
        }}
      </summary>
      <p class="text-muted-foreground text-sm">
        {{
          t('personalHistory.raw.received', {
            value1: temperature
              ? t('personalHistory.raw.ringDate')
              : t('personalHistory.raw.phoneDate'),
            local_date: report.local_date,
            timezone: report.timezone,
            value4: momentLabel(report.received_at, timezone, today),
          })
        }}
      </p>
      <p class="text-sm">
        {{
          t('personalHistory.raw.format', {
            layout: report.layout,
            qualification: report.qualification,
            value3: temperature
              ? t('personalHistory.raw.ambiguousLayout')
              : t('personalHistory.raw.unconfirmedUnits'),
          })
        }}
      </p>
      <p v-if="report.metric === 'workouts'" class="text-muted-foreground text-xs">
        {{ t('personalHistory.raw.dateBasis', { time_basis: report.time_basis }) }}
      </p>
      <RecordDetails :record="report" :timezone="timezone" :today="today" />
      <section
        v-if="report.metric === 'temperature' && report.interpretation"
        class="bg-muted space-y-2 rounded-xl p-3 text-sm"
        data-testid="soul-temperature-interpretation"
      >
        <h3 class="font-medium">
          {{
            report.interpretation.definition_id === 'soul1230-temperature-legacy25'
              ? t('personalHistory.raw.soul1230')
              : t('personalHistory.raw.soul1214')
          }}
        </h3>
        <p class="text-warning">
          {{ t('personalHistory.raw.interpretationWarning') }}
        </p>
        <p>
          {{ report.interpretation.definition_id }} · {{ report.interpretation.formula_version }} ·
          {{ report.interpretation.layout ?? t('personalHistory.raw.projectionUnavailable') }}
        </p>
        <p
          v-if="report.interpretation.status === 'unavailable'"
          class="text-warning"
          data-testid="soul1230-temperature-unavailable"
        >
          <template
            v-if="report.interpretation.missing[0] === 'temperature_sync_start_context_invalid'"
          >
            {{ t('personalHistory.raw.invalidTimeContext') }}
          </template>
          <template v-else>
            {{ t('personalHistory.raw.missingTimeContext') }}
          </template>
        </p>
        <template v-else>
          <p v-if="report.interpretation.definition_id === 'soul1230-temperature-legacy25'">
            {{
              t('personalHistory.raw.timeContext', {
                capture_local_date: report.interpretation.capture_local_date,
                day_start: report.interpretation.day_start,
                timezone: report.interpretation.time_context.timezone,
                sync_started_at: report.interpretation.time_context.sync_started_at,
                parsed_at: report.interpretation.time_context.parsed_at,
                parse_local_date: report.interpretation.parse_local_date,
                ring_local_date: report.interpretation.ring_local_date,
              })
            }}
          </p>
          <p v-else>
            {{
              t('personalHistory.raw.legacyTimeContext', {
                capture_local_date: report.interpretation.capture_local_date,
                day_start: report.interpretation.day_start,
                ring_local_date: report.interpretation.ring_local_date,
              })
            }}
          </p>
          <p>
            {{
              t('personalHistory.raw.interval', {
                interval_minutes: report.interpretation.interval_minutes,
                as_of: report.interpretation.as_of,
              })
            }}
          </p>
          <p v-if="report.interpretation.status === 'empty'">
            {{ t('personalHistory.raw.emptyInterpretation') }}
          </p>
          <ul v-else class="space-y-1 tabular-nums">
            <li
              v-for="point in report.interpretation.points"
              :key="`${point.token_offset}-${point.slot}`"
            >
              {{
                t('personalHistory.raw.offset', {
                  offset_minutes: point.offset_minutes,
                  value2:
                    report.interpretation.definition_id === 'soul1230-temperature-legacy25'
                      ? t('personalHistory.raw.requestMidnight')
                      : t('personalHistory.raw.receiptMidnight'),
                  value3: formatAmount(point.value, 1),
                  unit: point.unit,
                })
              }}
            </li>
          </ul>
        </template>
        <details
          v-if="report.interpretation.definition_id === 'soul1230-temperature-legacy25'"
          class="text-muted-foreground text-xs"
          data-testid="soul1214-temperature-capture-projection"
        >
          <summary class="min-h-11 cursor-pointer py-2">
            {{ t('personalHistory.raw.legacyProjection') }}
          </summary>
          <p>
            {{
              t('personalHistory.raw.legacyProjectionContext', {
                formula_version: report.interpretation.legacy_capture_projection.formula_version,
                day_start: report.interpretation.legacy_capture_projection.day_start,
                as_of: report.interpretation.legacy_capture_projection.as_of,
              })
            }}
          </p>
          <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
            JSON.stringify(report.interpretation.legacy_capture_projection, null, 2)
          }}</pre>
        </details>
        <details class="text-muted-foreground text-xs">
          <summary class="min-h-11 cursor-pointer py-2">
            {{ t('personalHistory.raw.conversionBasis') }}
          </summary>
          <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
            JSON.stringify(
              {
                input_refs: report.interpretation.input_refs,
                quality: report.interpretation.quality,
                candidate_counts: report.interpretation.candidate_counts,
                plausible_counts: report.interpretation.plausible_counts,
                ...(report.interpretation.definition_id === 'soul1230-temperature-legacy25'
                  ? {
                      missing: report.interpretation.missing,
                      time_basis: report.interpretation.time_basis,
                      time_context: report.interpretation.time_context,
                    }
                  : { time_basis: report.interpretation.time_basis }),
              },
              null,
              2,
            )
          }}</pre>
        </details>
      </section>
      <details class="text-muted-foreground text-xs">
        <summary class="min-h-11 cursor-pointer py-2">
          {{ t('personalHistory.raw.packet') }}
        </summary>
        <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
          JSON.stringify(
            {
              namespace: report.namespace,
              opcode: report.opcode,
              payload_hex: report.payload_hex,
              details: report.details,
              source_hash: report.source_hash,
            },
            null,
            2,
          )
        }}</pre>
      </details>
    </details>
    <p
      v-if="!coherent || query.isFetchNextPageError.value"
      class="text-warning text-sm"
      role="alert"
    >
      {{ t('personalHistory.raw.changed') }}
      <Button size="sm" variant="ghost" @click="query.refetch()">{{
        t('personalHistory.raw.reload')
      }}</Button>
    </p>
    <Button
      v-if="query.hasNextPage.value && coherent"
      size="sm"
      variant="outline"
      :disabled="query.isFetchingNextPage.value"
      @click="query.fetchNextPage()"
      >{{ t('personalHistory.raw.more') }}</Button
    >
  </section>
</template>
