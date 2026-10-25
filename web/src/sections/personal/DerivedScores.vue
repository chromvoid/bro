<script setup lang="ts">
import { useInfiniteQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/core/client'
import { dayLabel, momentLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import messages from '@/locales/derived-scores-ru.json'
import {
  DerivedScoresPage,
  coherentDerivedSummaries,
  scoreValue,
  type DerivedScoreSummary,
} from './derivedScores'
import DerivedResultDetail from './DerivedResultDetail.vue'
import DerivedResultVersions from './DerivedResultVersions.vue'
import DerivedBackfillControl from './DerivedBackfillControl.vue'
import DerivedMissingInputs from './DerivedMissingInputs.vue'
import RecordQuality from './RecordQuality.vue'

const props = defineProps<{ from: string; to: string; timezone: string; today: string }>()
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const session = useSession()
const user = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!user.value && session.can('personal.read'))
const query = useInfiniteQuery({
  enabled: permitted,
  queryKey: computed(() => [
    'personal',
    'derived-scores',
    user.value,
    props.from,
    props.to,
    props.timezone,
  ]),
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const owner = user.value
    const from = props.from
    const to = props.to
    const zone = props.timezone
    const params = new URLSearchParams({
      from,
      to,
      time_zone: zone,
      limit: '250',
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = DerivedScoresPage.parse(
      await api.get(`/v1/personal/derived-scores?${params}`, signal),
    )
    if (
      !permitted.value ||
      owner !== user.value ||
      from !== props.from ||
      to !== props.to ||
      zone !== props.timezone ||
      page.from !== from ||
      page.to !== to ||
      page.time_zone !== zone
    )
      throw new DOMException('Account or period changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const pages = computed(() => (permitted.value ? (query.data.value?.pages ?? []) : []))
const coherent = computed(() => coherentDerivedSummaries(pages.value))
const days = computed(() => {
  if (!coherent.value) return []
  const grouped = new Map<string, DerivedScoreSummary[]>()
  for (const page of pages.value)
    for (const day of page.days) {
      const scores = grouped.get(day.date) ?? []
      scores.push(...day.scores)
      grouped.set(day.date, scores)
    }
  return [...grouped]
    .sort(([first], [second]) => first.localeCompare(second))
    .map(([date, scores]) => ({ date, scores }))
})
const loadedCount = computed(() => days.value.reduce((total, day) => total + day.scores.length, 0))
const refresh = computed(() => pages.value.at(-1)?.refresh)
const visibleVersions = ref(new Set<string>())
const showArchive = ref(false)
watch(
  () => [user.value, props.from, props.to, props.timezone],
  () => {
    visibleVersions.value = new Set()
    showArchive.value = false
  },
)
function toggleVersions(id: string) {
  const selected = new Set(visibleVersions.value)
  if (selected.has(id)) selected.delete(id)
  else selected.add(id)
  visibleVersions.value = selected
}
</script>

<template>
  <section v-if="permitted" class="space-y-3" data-testid="derived-scores">
    <h2 class="font-semibold">{{ t('derivedScores.title') }}</h2>
    <p class="text-muted-foreground text-sm">{{ t('derivedScores.intro') }}</p>
    <DerivedBackfillControl
      :from="from"
      :to="to"
      :timezone="timezone"
      :pending="refresh?.pending ?? false"
      @queued="query.refetch()"
      @completed="query.refetch()"
    />
    <p v-if="refresh?.pending" class="text-muted-foreground text-sm" role="status">
      {{ t('derivedScores.refreshPending') }}
    </p>
    <p v-if="refresh" class="text-muted-foreground text-xs">
      {{
        t('derivedScores.refreshCounts', {
          pending: refresh.pending_runs,
          failed: refresh.failed_runs,
        })
      }}
    </p>
    <p
      v-if="
        refresh &&
        (refresh.owner_pending_runs > refresh.pending_runs ||
          refresh.owner_failed_runs > refresh.failed_runs)
      "
      class="text-muted-foreground text-xs"
      role="status"
    >
      {{
        t('derivedScores.ownerRefreshCounts', {
          pending: refresh.owner_pending_runs,
          failed: refresh.owner_failed_runs,
        })
      }}
    </p>
    <RefreshFailed :query="query" />
    <LoadingState v-if="query.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="query.isLoadingError.value"
      :error="query.error.value"
      @retry="query.refetch()"
    />
    <p v-else-if="coherent && !loadedCount" class="text-muted-foreground text-sm">
      {{ t('derivedScores.empty') }}
    </p>
    <p
      v-if="pages[0] && coherent"
      class="text-muted-foreground text-xs"
      data-testid="derived-summary-count"
    >
      {{ t('derivedScores.loadedCount', { loaded: loadedCount, total: pages[0].total_summaries }) }}
    </p>
    <template v-for="day in days" :key="day.date">
      <div v-if="day.scores.length" class="space-y-2">
        <h3 class="font-medium">{{ dayLabel(day.date, today) }}</h3>
        <div class="grid gap-3 sm:grid-cols-2">
          <article
            v-for="score in day.scores"
            :key="score.result_id"
            class="bg-card space-y-2 rounded-2xl border p-4"
            data-testid="derived-score"
          >
            <h4 class="font-medium">{{ score.definition_snapshot.label }}</h4>
            <p v-if="scoreValue(score) !== null" class="text-xl font-semibold tabular-nums">
              {{ formatAmount(scoreValue(score), 2) }} {{ score.unit }}
            </p>
            <p class="text-muted-foreground text-sm">
              {{ t(`derivedScores.status.${score.status}`) }}
            </p>
            <p class="text-muted-foreground text-xs">
              {{
                t('derivedScores.savedCalculation', {
                  version: score.version,
                  when: momentLabel(score.evaluated_at, timezone, today),
                })
              }}
            </p>
            <p class="text-muted-foreground break-all text-xs">
              {{
                score.context.source_id
                  ? t('sources.line', { names: score.context.source_id })
                  : t('derivedScores.ownerContext')
              }}
              · {{ score.formula_version }}
            </p>
            <RecordQuality :flags="score.quality" />
            <DerivedMissingInputs :codes="score.missing" />
            <p
              v-if="score.series_count > 0"
              class="text-muted-foreground text-sm"
              data-testid="derived-instant-count"
            >
              {{ t('derivedScores.instantCount', { count: score.series_count }) }}
            </p>
            <DerivedResultDetail
              :result-id="score.result_id"
              :source-id="score.context.source_id"
              :definition-id="score.definition_id"
              :formula-version="score.formula_version"
              :date="score.context.date"
              :timezone="score.context.time_zone"
              :today="today"
            />
            <Button
              size="sm"
              variant="outline"
              :aria-expanded="visibleVersions.has(score.result_id)"
              @click="toggleVersions(score.result_id)"
              >{{
                visibleVersions.has(score.result_id)
                  ? t('derivedScores.hideVersions')
                  : score.series_count > 0
                    ? t('derivedScores.allPointsVersions')
                    : t('derivedScores.versions')
              }}</Button
            >
            <DerivedResultVersions
              v-if="visibleVersions.has(score.result_id)"
              :from="score.context.date"
              :to="score.context.date"
              :timezone="score.context.time_zone"
              :today="today"
              :definition-id="score.definition_id"
              :source-id="score.context.source_id"
              :current-result-id="score.result_id"
            />
          </article>
        </div>
      </div>
    </template>
    <p
      v-if="!coherent || query.isFetchNextPageError.value"
      class="text-warning text-sm"
      role="alert"
    >
      {{ t('derivedScores.changed') }}
    </p>
    <div class="flex flex-wrap gap-2">
      <Button
        v-if="query.hasNextPage.value && coherent"
        size="sm"
        variant="outline"
        :disabled="query.isFetchingNextPage.value"
        @click="query.fetchNextPage()"
        >{{ t('derivedScores.loadMore') }}</Button
      ><Button
        size="sm"
        variant="outline"
        :aria-expanded="showArchive"
        @click="showArchive = !showArchive"
        >{{ showArchive ? t('derivedScores.hideArchive') : t('derivedScores.allVersions') }}</Button
      ><Button
        size="sm"
        variant="ghost"
        :disabled="query.isFetching.value"
        @click="query.refetch()"
        >{{ t('derivedScores.refresh') }}</Button
      >
    </div>
    <DerivedResultVersions
      v-if="showArchive"
      :from="from"
      :to="to"
      :timezone="timezone"
      :today="today"
    />
  </section>
</template>
