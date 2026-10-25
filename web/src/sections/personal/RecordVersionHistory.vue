<script setup lang="ts">
import historyMessages from '@/locales/personal-history-ru.json'
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/core/client'
import { momentLabel } from '@/core/dates'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/button'
import LoadingState from '@/components/state/LoadingState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import RecordDetails from './RecordDetails.vue'
import { useDisplayUnits } from './displayUnits'
import {
  RecordVersionPage,
  coherentRecordVersions,
  versionDetails,
  versionRange,
  versionValue,
  type ArchivedMetric,
} from './recordVersions'

const props = defineProps<{
  family: 'record' | 'sensor'
  recordId: string
  sourceId: string | null
  metric: ArchivedMetric
  timezone: string
  today: string
}>()
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const displayUnits = useDisplayUnits()
const session = useSession()
const queryClient = useQueryClient()
const owner = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!owner.value && session.can('personal.read'))
const open = ref(false)
let epoch = 0
const key = computed(() => [
  'personal',
  'record-versions',
  owner.value,
  props.family,
  props.recordId,
  props.sourceId,
  props.metric,
])
watch(
  [
    owner,
    () => props.family,
    () => props.recordId,
    () => props.sourceId,
    () => props.metric,
    permitted,
  ],
  (_, previous) => {
    epoch++
    open.value = false
    queryClient.removeQueries({
      queryKey: ['personal', 'record-versions', ...previous.slice(0, 5)],
      exact: true,
    })
  },
  { flush: 'sync' },
)
const query = useInfiniteQuery({
  enabled: computed(() => permitted.value && open.value),
  queryKey: key,
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const current = {
      owner: owner.value,
      epoch,
      family: props.family,
      id: props.recordId,
      source: props.sourceId,
      metric: props.metric,
    }
    if (!permitted.value) throw new DOMException('Record access changed', 'AbortError')
    const params = new URLSearchParams({
      [current.family === 'record' ? 'kind' : 'metric']: current.metric,
      limit: '50',
      ...(current.source ? { source_id: current.source } : {}),
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = RecordVersionPage.parse(
      await api.get(
        `/v1/personal/${current.family === 'record' ? 'records' : 'sensor-samples'}/${encodeURIComponent(current.id)}/versions?${params}`,
        signal,
      ),
    )
    if (
      signal.aborted ||
      current.epoch !== epoch ||
      !permitted.value ||
      current.owner !== owner.value ||
      current.family !== props.family ||
      current.id !== props.recordId ||
      current.source !== props.sourceId ||
      current.metric !== props.metric ||
      page.id !== current.id ||
      page.source_id !== current.source ||
      (current.family === 'record' ? page.kind : page.metric) !== current.metric ||
      page.versions.some(
        ({ revision, snapshot }) =>
          (typeof snapshot.user_id === 'string' && snapshot.user_id !== current.owner) ||
          (typeof snapshot.id === 'string' && snapshot.id !== current.id) ||
          ((typeof snapshot.source_id === 'string' || snapshot.source_id === null) &&
            snapshot.source_id !== current.source) ||
          (typeof snapshot[current.family === 'record' ? 'kind' : 'metric'] === 'string' &&
            snapshot[current.family === 'record' ? 'kind' : 'metric'] !== current.metric) ||
          (typeof snapshot.revision === 'number' && snapshot.revision !== revision),
      )
    )
      throw new DOMException('Record context changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const pages = computed(() => (permitted.value && open.value ? (query.data.value?.pages ?? []) : []))
const coherent = computed(() => coherentRecordVersions(pages.value))
const failed = computed(
  () => !coherent.value || query.isFetchNextPageError.value || query.isRefetchError.value,
)
const versions = computed(() => (failed.value ? [] : pages.value.flatMap((page) => page.versions)))
function toggle(event: Event) {
  open.value = permitted.value && (event.currentTarget as HTMLDetailsElement).open
}
function reload() {
  epoch++
  void queryClient.resetQueries({ queryKey: key.value, exact: true })
}
</script>

<template>
  <details
    v-if="permitted"
    class="space-y-3 px-4 py-2 text-sm"
    data-testid="record-version-history"
    :open="open"
    @toggle="toggle"
  >
    <summary class="text-primary min-h-11 cursor-pointer py-2">
      {{ t('personalHistory.versions.title') }}
    </summary>
    <template v-if="open">
      <p class="text-muted-foreground">
        {{ t('personalHistory.versions.explanation') }}
      </p>
      <p v-if="!failed && pages[0]?.annotation.excluded" class="text-warning">
        {{ t('personalHistory.versions.excluded') }}
      </p>
      <p v-if="!failed && pages[0]?.annotation.corrected" class="text-muted-foreground">
        {{ t('personalHistory.versions.corrected') }}
      </p>
      <LoadingState v-if="query.isPending.value" :rows="2" />
      <ErrorState
        v-else-if="query.isLoadingError.value"
        :error="query.error.value"
        @retry="reload"
      />
      <p v-else-if="failed" class="text-warning" role="alert">
        {{ t('personalHistory.versions.changed') }}
        <Button size="sm" variant="ghost" @click="reload">{{
          t('personalHistory.versions.reload')
        }}</Button>
      </p>
      <p v-else-if="pages[0] && !versions.length" class="text-muted-foreground">
        {{ t('personalHistory.versions.empty') }}
      </p>
      <p v-else-if="pages[0]" class="text-muted-foreground">
        {{ t('personalHistory.versions.count', { count: pages[0].count }) }}
      </p>
      <details v-if="!failed && pages[0]" class="text-muted-foreground">
        <summary class="min-h-11 cursor-pointer py-2">
          {{ t('personalHistory.versions.annotations') }}
        </summary>
        <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
          JSON.stringify(pages[0].annotation, null, 2)
        }}</pre>
      </details>
      <details
        v-for="version in versions"
        :key="version.revision"
        class="space-y-2 rounded-xl border p-3"
        data-testid="saved-record-version"
      >
        <summary class="min-h-11 cursor-pointer py-2">
          {{ t(`${family === 'record' ? 'personal' : 'ring'}.metric.${metric}`) }} ·
          {{ versionValue(version, metric, displayUnits) ?? t('personal.noValue') }} ·
          {{
            pages[0]?.source_name ??
            (sourceId === null
              ? t('personalHistory.versions.manual')
              : t('personalHistory.versions.unnamedSource'))
          }}
          · v{{ version.revision }} ·
          {{
            version.revision === pages[0]?.current_revision
              ? t('personalHistory.versions.current')
              : t('personalHistory.versions.previous')
          }}
        </summary>
        <p v-if="versionRange(version, displayUnits)" class="text-muted-foreground">
          {{
            t('personalHistory.versions.range', {
              value1: versionRange(version, displayUnits)?.minimum ?? '—',
              value2: versionRange(version, displayUnits)?.maximum ?? '—',
            })
          }}
        </p>
        <p class="text-muted-foreground">
          {{
            version.created_at
              ? t('personalHistory.versions.savedAt', {
                  when: momentLabel(version.created_at, timezone, today),
                })
              : t('personalHistory.versions.savedAtUnknown')
          }}
        </p>
        <RecordDetails
          :record="versionDetails(version, pages[0]?.source_name ?? null)"
          :timezone="timezone"
          :today="today"
        />
        <details class="text-muted-foreground">
          <summary class="min-h-11 cursor-pointer py-2">
            {{ t('personalHistory.versions.fullSnapshot') }}
          </summary>
          <p class="break-all">
            {{
              t('personalHistory.versions.receipt', {
                revision: version.revision,
                value2: version.operation_id ?? t('personalHistory.versions.receiptUnknown'),
              })
            }}
          </p>
          <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
            JSON.stringify(version.snapshot, null, 2)
          }}</pre>
        </details>
      </details>
      <Button
        v-if="query.hasNextPage.value && !failed"
        size="sm"
        variant="outline"
        :disabled="query.isFetchingNextPage.value"
        @click="query.fetchNextPage()"
        >{{ t('personalHistory.versions.more') }}</Button
      >
    </template>
  </details>
</template>
