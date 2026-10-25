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
import DerivedScoreDetails from './DerivedScoreDetails.vue'
import DerivedMissingInputs from './DerivedMissingInputs.vue'
import { DerivedResultVersionsPage, coherentDerivedVersions, scoreValue } from './derivedScores'
import { personalHistoryLink } from './personalNavigation'

const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const props = defineProps<{
  from: string
  to: string
  timezone: string
  today: string
  definitionId?: string
  sourceId?: string | null
  currentResultId?: string
}>()
const session = useSession()
const owner = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!owner.value && session.can('personal.read'))
const chosenSource = ref('')
const knownSources = ref(new Set<string>())
const selectedSource = computed(() =>
  props.sourceId === undefined ? chosenSource.value || undefined : props.sourceId,
)
const archiveScope = computed(() =>
  selectedSource.value === null ? 'owner' : selectedSource.value ? 'source' : 'all',
)
watch(
  () => [owner.value, props.from, props.to, props.timezone, props.definitionId, props.sourceId],
  () => {
    chosenSource.value = ''
    knownSources.value = new Set()
  },
)
const query = useInfiniteQuery({
  enabled: permitted,
  queryKey: computed(() => [
    'personal',
    'derived-result-versions',
    owner.value,
    props.from,
    props.to,
    props.timezone,
    props.definitionId,
    archiveScope.value,
    selectedSource.value,
  ]),
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const user = owner.value
    const source = selectedSource.value
    const scope = archiveScope.value
    const definition = props.definitionId
    const params = new URLSearchParams({
      from: props.from,
      to: props.to,
      time_zone: props.timezone,
      limit: '100',
      scope,
      ...(source ? { source_id: source } : {}),
      ...(definition ? { definition_id: definition } : {}),
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = DerivedResultVersionsPage.parse(
      await api.get(`/v1/personal/derived-result-versions?${params}`, signal),
    )
    if (
      !permitted.value ||
      user !== owner.value ||
      source !== selectedSource.value ||
      definition !== props.definitionId ||
      page.scope !== scope ||
      (source === null && page.items.some((row) => row.source_scope !== null)) ||
      (source &&
        page.items.some((row) => row.source_scope !== source && row.source_scope !== null)) ||
      (definition && page.items.some((row) => row.definition_id !== definition))
    )
      throw new DOMException('Derived archive scope changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const pages = computed(() => (permitted.value ? (query.data.value?.pages ?? []) : []))
const coherent = computed(() => coherentDerivedVersions(pages.value))
const rows = computed(() => (coherent.value ? pages.value.flatMap((page) => page.items) : []))
watch(pages, (value) => {
  const updated = new Set(knownSources.value)
  for (const row of value.flatMap((page) => page.items))
    if (row.source_scope) updated.add(row.source_scope)
  knownSources.value = updated
})
</script>

<template>
  <section v-if="permitted" class="space-y-3" data-testid="derived-result-versions">
    <h3 class="font-semibold">{{ t('personalHistory.derived.versionsTitle') }}</h3>
    <p class="text-muted-foreground text-sm">
      {{ t('personalHistory.derived.versionsExplanation') }}
    </p>
    <p v-if="pages[0]?.refresh.pending" class="text-muted-foreground text-sm" role="status">
      {{ t('personalHistory.derived.pending') }}
    </p>
    <p v-if="pages[0]" class="text-muted-foreground text-xs">
      {{
        t('personalHistory.derived.archiveCounts', {
          total_versions: pages[0].total_versions,
          pending_runs: pages[0].refresh.pending_runs,
          failed_runs: pages[0].refresh.failed_runs,
        })
      }}
    </p>
    <p
      v-if="
        pages[0] &&
        (pages[0].refresh.owner_pending_runs > pages[0].refresh.pending_runs ||
          pages[0].refresh.owner_failed_runs > pages[0].refresh.failed_runs)
      "
      class="text-muted-foreground text-xs"
      role="status"
    >
      {{
        t('personalHistory.derived.ownerRefresh', {
          owner_pending_runs: pages[0].refresh.owner_pending_runs,
          owner_failed_runs: pages[0].refresh.owner_failed_runs,
        })
      }}
    </p>
    <NativeSelect
      v-if="sourceId === undefined && (knownSources.size > 0 || chosenSource)"
      v-model="chosenSource"
      :aria-label="t('personalHistory.derived.versionSource')"
      class="max-w-sm"
      ><option value="">{{ t('personalHistory.derived.allSources') }}</option>
      <option v-for="source in knownSources" :key="source" :value="source">
        {{ source }}
      </option></NativeSelect
    >
    <RefreshFailed :query="query" />
    <LoadingState v-if="query.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="query.isLoadingError.value"
      :error="query.error.value"
      @retry="query.refetch()"
    />
    <p v-else-if="coherent && !rows.length" class="text-muted-foreground text-sm">
      {{
        query.hasNextPage.value
          ? t('personalHistory.derived.moreContextPages')
          : t('personalHistory.derived.noContextVersions')
      }}
    </p>
    <article
      v-for="row in rows"
      :key="row.result_id"
      class="bg-card space-y-2 rounded-2xl border p-4"
      data-testid="derived-result-version"
    >
      <h4 class="font-medium">{{ row.definition_snapshot.label }} · v{{ row.version }}</h4>
      <p class="text-muted-foreground text-sm">
        {{
          row.is_current
            ? t('personalHistory.derived.currentContextVersion')
            : t('personalHistory.derived.previousContextVersion')
        }}<span v-if="row.result_id === currentResultId">
          {{ t('personalHistory.derived.shownInCard') }}</span
        >
        · {{ row.evaluation }} · {{ row.time_zone }}
      </p>
      <p v-if="scoreValue(row.score) !== null" class="text-xl font-semibold tabular-nums">
        {{ formatAmount(scoreValue(row.score), 2) }} {{ row.score.unit }}
      </p>
      <p v-else class="text-muted-foreground text-sm">
        {{ t('personalHistory.derived.noValue') }}
      </p>
      <DerivedMissingInputs :codes="row.score.missing" />
      <p class="text-muted-foreground text-sm">
        {{
          t('personalHistory.derived.savedAt', {
            value1: momentLabel(row.as_of, timezone, today),
            value2: momentLabel(row.created_at, timezone, today),
          })
        }}
      </p>
      <p class="text-muted-foreground break-all text-sm">
        {{
          row.source_scope
            ? t('personalHistory.derived.source', { source: row.source_scope })
            : t('personalHistory.derived.ownerAllSources')
        }}
      </p>
      <DerivedScoreDetails
        :score="row.score"
        :definition="row.definition_snapshot"
        :timezone="timezone"
        :today="today"
      />
      <RouterLink
        :to="personalHistoryLink(row.date, 'daily', 'all')"
        class="text-primary inline-flex min-h-11 items-center text-sm underline"
        >{{ t('personalHistory.derived.dayRecords') }}</RouterLink
      >
      <details class="text-muted-foreground text-xs">
        <summary class="min-h-11 cursor-pointer py-2">
          {{ t('personalHistory.derived.immutableEvidence') }}
        </summary>
        <pre class="max-h-96 overflow-auto whitespace-pre-wrap break-all">{{
          JSON.stringify(
            {
              result_id: row.result_id,
              version: row.version,
              temporal_basis: row.temporal_basis,
              normalized_inputs: row.normalized_inputs,
              context_revisions: row.context_revisions,
              input_adapter_version: row.input_adapter_version,
              implementation_digest: row.implementation_digest,
              implementation_source: row.implementation_source,
              operation_id: row.operation_id,
              definition_snapshot: row.definition_snapshot,
            },
            null,
            2,
          )
        }}</pre>
      </details>
    </article>
    <p
      v-if="!coherent || query.isFetchNextPageError.value"
      class="text-warning text-sm"
      role="alert"
    >
      {{ t('personalHistory.derived.archiveChanged') }}
    </p>
    <div class="flex flex-wrap gap-2">
      <Button
        v-if="query.hasNextPage.value && coherent"
        size="sm"
        variant="outline"
        :disabled="query.isFetchingNextPage.value"
        @click="query.fetchNextPage()"
        >{{ t('personalHistory.versions.more') }}</Button
      ><Button
        size="sm"
        variant="ghost"
        :disabled="query.isFetching.value"
        @click="query.refetch()"
        >{{ t('personalHistory.derived.reload') }}</Button
      >
    </div>
  </section>
</template>
