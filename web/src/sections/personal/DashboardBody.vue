<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { computed, nextTick, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import DataNotes from '@/components/DataNotes.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { momentLabel, shiftDay } from '@/core/dates'
import dashboardBodyRu from '@/locales/dashboard-body-ru.json'
import {
  BODY_KINDS,
  amountText,
  provenance,
  type BodyKind,
  type Metric,
  type Observation,
  type Profile,
  type Source,
  type SourceMetric,
} from './api'
import BodyAnalysisCard from './BodyAnalysisCard.vue'
import BodyAtlas from './BodyAtlas.vue'
import BodyComposition from './BodyComposition.vue'
import BodyFigure from './BodyFigure.vue'
import BodySourceDetails from './BodySourceDetails.vue'
import RecordPanel from './RecordPanel.vue'
import SourceLine from './SourceLine.vue'
import { bodyVisual } from './bodyVisual'
import { dashboardBody, dashboardBodyChanges, previousDashboardBody } from './dashboardBody'
import { useHistory } from './usePersonal'
import { capturePersonalReturn, personalHistoryLink } from './personalNavigation'
import { useBodyPresentation } from './useBodyPresentation'

const props = defineProps<{
  metrics: Partial<Record<BodyKind, Metric>>
  today: string
  timezone: string
  canResolve: boolean
  day?: string
  sources?: Source[]
  fatSources?: SourceMetric[]
  profile?: Profile | null
  /** Only a source represented by the parent's caliper summary may leave this overview. */
  caliperSourceId?: string | null
}>()
const emit = defineEmits<{ profile: [] }>()
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: dashboardBodyRu },
  missingWarn: false,
  fallbackWarn: false,
})
const headingId = useId()
const day = computed(() => props.day ?? props.today)
const range = computed(() => ({
  from: shiftDay(day.value, -364),
  to: day.value,
  kind: 'body' as const,
}))
const history = useHistory(range)
const presentation = useBodyPresentation()
const snapshot = computed(() =>
  dashboardBody(
    props.metrics,
    props.fatSources ?? [],
    history.data.value,
    day.value,
    props.timezone,
  ),
)
const current = computed(() => snapshot.value.current)
const previous = computed(() => previousDashboardBody(snapshot.value.analyses, current.value))
const showPrevious = ref(false)
const shown = computed(() =>
  showPrevious.value && previous.value ? previous.value : current.value,
)
const mapping = computed(() => (shown.value ? bodyVisual(shown.value, props.profile) : null))
const keyKinds = ['body_fat_percent', 'skeletal_muscle_mass'] as const
const conflicted = computed(() =>
  BODY_KINDS.some((kind) => props.metrics[kind]?.status === 'conflict'),
)
/** The body's decision that names its sources: every analysis field shares the same devices. */
const sourced = computed(() =>
  BODY_KINDS.map((kind) => props.metrics[kind]).find(
    (metric) => !!metric?.resolution?.contributions?.length,
  ),
)
const bodySources = computed(
  () =>
    props.sources?.filter((source) =>
      source.kinds.some((kind) => BODY_KINDS.includes(kind as BodyKind)),
    ) ?? [],
)
const restricted = computed(() =>
  [...bodySources.value, ...(history.data.value?.sources ?? [])].some(
    (source) => source.error === 'history_restricted',
  ),
)
const hasData = computed(
  () =>
    BODY_KINDS.some((kind) => props.metrics[kind]?.status !== 'no_data' && !!props.metrics[kind]) ||
    !!props.fatSources?.length,
)
const fallbackMetrics = computed(() =>
  keyKinds
    .map((kind) => props.metrics[kind])
    .filter(
      (metric): metric is Metric =>
        !!metric &&
        metric.status !== 'conflict' &&
        metric.status !== 'no_data' &&
        amountText(metric.kind, metric.value) !== null &&
        (metric.kind !== 'body_fat_percent' || !props.fatSources?.length),
    ),
)
const secondaryFat = computed(
  () =>
    props.fatSources?.filter(
      (source) =>
        (!props.caliperSourceId || source.source_id !== props.caliperSourceId) &&
        !source.records?.some((record) => record.id === current.value?.values.body_fat_percent?.id),
    ) ?? [],
)
const sourceDown = computed(
  () =>
    !!shown.value?.sourceId &&
    (bodySources.value.some(
      (source) => source.id === shown.value?.sourceId && source.status === 'unavailable',
    ) ||
      props.fatSources?.some(
        (source) => source.source_id === shown.value?.sourceId && source.source_unavailable,
      )),
)
const detailsOpen = ref(false)
const detailAnalysisId = ref<string | null>(null)
const detailAnalysis = computed(
  () =>
    snapshot.value.analyses.find((analysis) => analysis.id === detailAnalysisId.value) ??
    shown.value,
)
const analysisElement = ref<HTMLElement | null>(null)
function openDetails() {
  detailAnalysisId.value = null
  detailsOpen.value = true
}
const renderer = ref<'webgpu' | 'webgl' | 'poster'>('poster')
const figureLoading = ref(true)
const record = shallowRef<Observation | null>(null)
function editRecord(observation: Observation) {
  if (!props.canResolve) return
  detailAnalysisId.value = detailAnalysis.value?.id ?? null
  detailsOpen.value = false
  record.value = observation
}
watch(record, async (current, previousRecord) => {
  if (current || !previousRecord || !detailAnalysisId.value) return
  detailsOpen.value = true
  await nextTick()
  const disclosure = analysisElement.value?.querySelector<HTMLDetailsElement>(
    '[data-testid="analysis-details"]',
  )
  if (disclosure) {
    disclosure.open = true
    disclosure.dispatchEvent(new Event('toggle'))
  }
  await nextTick()
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
  if (!detailsOpen.value || record.value) return
  const target = Array.from(
    analysisElement.value?.querySelectorAll<HTMLElement>('[data-record-id]') ?? [],
  ).find((element) => element.dataset.recordId === previousRecord.id)
  target?.scrollIntoView?.({ block: 'nearest' })
  target?.focus({ preventScroll: true })
})
const recordOpen = computed({
  get: () => !!record.value && props.canResolve,
  set: (open: boolean) => {
    if (!open) record.value = null
  },
})
const sourceIsDdx = computed(
  () =>
    !!detailAnalysis.value?.archive ||
    bodySources.value.some(
      (source) =>
        source.id === detailAnalysis.value?.sourceId && source.connection?.provider === 'ddx',
    ) ||
    detailAnalysis.value?.records.some(
      (entry) => (entry.original?.origin ?? entry.origin)?.provider === 'DDX Fitness',
    ),
)
const historyRoute = computed(() => personalHistoryLink(day.value, 'body', 'body', 365))
const when = (instant: string) => momentLabel(instant, props.timezone, props.today)
const comparison = computed(() =>
  dashboardBodyChanges(showPrevious.value ? undefined : shown.value, snapshot.value.analyses),
)
const change = (kind: (typeof keyKinds)[number]) =>
  comparison.value.changes.find((entry) => entry.kind === kind)
const sourceRecords = (source: SourceMetric) =>
  source.records?.filter((entry) => !entry.excluded) ?? []
const historyOnlyRestricted = computed(
  () =>
    restricted.value && !bodySources.value.some((source) => source.error === 'history_restricted'),
)
function conflicting(kind: BodyKind): boolean {
  return (
    (shown.value?.records.filter((entry) => entry.kind === kind && !entry.excluded).length ?? 0) > 1
  )
}
watch(
  () => [day.value, current.value?.id],
  () => {
    showPrevious.value = false
    detailsOpen.value = false
    if (!record.value) detailAnalysisId.value = null
  },
)
</script>

<template>
  <section
    v-if="hasData || bodySources.length || profile || $slots.caliper"
    class="dashboard-body bg-card rounded-3xl border p-4 sm:p-5"
    :aria-labelledby="headingId"
    data-testid="dashboard-body"
  >
    <header class="flex flex-wrap items-start justify-between gap-3">
      <div class="min-w-0">
        <h2 :id="headingId" class="font-semibold">{{ t('personal.bodyComposition') }}</h2>
        <p class="text-muted-foreground mt-1 hidden text-sm md:block">
          {{ t('personal.dashboard.bodySubtitle') }}
        </p>
      </div>
      <Button
        variant="ghost"
        size="sm"
        class="min-h-11"
        data-testid="body-details-open"
        @click="openDetails"
      >
        {{ t('personal.dashboard.allBodyMetrics') }}<ChevronRight aria-hidden="true" />
      </Button>
    </header>

    <p v-if="conflicted" class="text-warning mt-3 text-sm" data-testid="body-conflict">
      {{ t('resolution.status.conflict') }}
    </p>

    <template v-if="shown && mapping">
      <div
        class="dashboard-body__scan-caption text-muted-foreground mt-4 text-sm"
        data-testid="dashboard-body-caption"
      >
        <span class="font-medium">{{ shown.sourceName ?? t('personal.unnamedSource') }}</span>
        <span aria-hidden="true"> · </span>
        <time :datetime="shown.measuredAt">{{ when(shown.measuredAt) }}</time>
      </div>
      <SourceLine
        v-if="sourced"
        :metric="sourced.kind"
        :contributions="sourced.resolution?.contributions"
        :day="day"
      />
      <div class="dashboard-body__overview mt-2">
        <div class="dashboard-body__figure">
          <BodyFigure
            v-if="presentation.ready.value"
            :visual="mapping.visual"
            :character-id="presentation.characterId.value"
            :paused="detailsOpen"
            :comparing="showPrevious"
            @renderer="renderer = $event"
            @loading="figureLoading = $event"
          />
        </div>
        <div class="dashboard-body__metrics">
          <button
            v-for="kind in keyKinds"
            :key="kind"
            type="button"
            class="dashboard-body__metric hover:bg-accent/40 rounded-2xl border p-3 text-left"
            :data-testid="`dashboard-body-${kind}`"
            @click="openDetails"
          >
            <span class="text-muted-foreground flex items-center justify-between gap-2 text-sm">
              {{
                t(
                  kind === 'skeletal_muscle_mass'
                    ? 'personal.dashboard.skeletalMuscle'
                    : `personal.metric.${kind}`,
                )
              }}<ChevronRight class="hidden size-4 shrink-0 md:block" aria-hidden="true" />
            </span>
            <strong class="mt-1 block text-2xl font-semibold tabular-nums">
              {{ amountText(kind, shown.values[kind]?.data.value ?? null) ?? '—' }}
            </strong>
            <span v-if="!shown.values[kind]" class="text-muted-foreground mt-1 block text-xs">
              {{ t(conflicting(kind) ? 'resolution.status.conflict' : 'personal.noValue') }}
            </span>
            <span
              v-if="change(kind)"
              class="text-muted-foreground mt-1 hidden text-xs tabular-nums md:block"
            >
              {{ change(kind)!.value
              }}<span class="block">{{
                t('personal.comparedWith', { when: when(change(kind)!.at) })
              }}</span>
            </span>
            <span
              v-if="shown.values[kind]?.corrected"
              class="text-muted-foreground mt-1 block text-xs"
            >
              {{ t('personal.origin.corrected') }}
            </span>
          </button>
        </div>
      </div>
      <div
        v-if="comparison.changes.length"
        class="text-muted-foreground mt-2 space-y-1 text-xs tabular-nums md:hidden"
        data-testid="dashboard-body-comparison"
      >
        <template v-if="comparison.sharedPrevious">
          <p data-testid="dashboard-body-shared-change">
            <span v-for="(entry, index) in comparison.changes" :key="entry.kind">
              <span v-if="index" aria-hidden="true"> · </span
              >{{ t(`personal.dashboard.change.${entry.kind}`, { value: entry.value }) }}
            </span>
          </p>
          <p>
            {{ t('personal.comparedWith', { when: when(comparison.sharedPrevious.measuredAt) }) }}
          </p>
        </template>
        <p
          v-for="entry in comparison.sharedPrevious ? [] : comparison.changes"
          :key="entry.kind"
          :data-testid="`dashboard-body-change-${entry.kind}`"
        >
          {{ t(`personal.dashboard.change.${entry.kind}`, { value: entry.value }) }} ·
          {{ t('personal.comparedWith', { when: when(entry.at) }) }}
        </p>
      </div>
      <div
        v-if="previous"
        class="dashboard-body__controls mt-3 flex flex-wrap justify-end gap-1"
        role="group"
        :aria-label="t('personal.atlas.compare')"
      >
        <Button
          :variant="showPrevious ? 'default' : 'outline'"
          size="sm"
          :aria-pressed="showPrevious"
          data-testid="dashboard-body-previous"
          class="min-h-11"
          @click="showPrevious = true"
        >
          {{ t('personal.dashboard.previousAnalysis') }}
        </Button>
        <Button
          :variant="showPrevious ? 'outline' : 'default'"
          size="sm"
          :aria-pressed="!showPrevious"
          data-testid="dashboard-body-latest"
          class="min-h-11"
          @click="showPrevious = false"
        >
          {{ t('personal.dashboard.latestAnalysis') }}
        </Button>
      </div>
      <!-- The poster stands in while the figure loads; a visible line here collapsed when it
           finished and moved the page under a tap. -->
      <p v-if="figureLoading" class="sr-only" role="status">
        {{ t('personal.atlas.motion.loading') }}
      </p>
      <p v-else-if="renderer === 'poster'" class="text-muted-foreground mt-2 text-sm">
        {{ t('personal.atlas.staticView') }}
      </p>
      <p v-if="sourceDown" class="text-warning mt-2 text-sm">{{ t('personal.sourceDown') }}</p>
      <div
        v-if="profile && (mapping.genderMissing || mapping.heightMissing)"
        class="text-muted-foreground mt-3 space-y-1 text-sm"
        data-testid="dashboard-body-profile-hint"
      >
        <p v-if="mapping.genderMissing">{{ t('personal.atlas.genderMissing') }}</p>
        <p v-if="mapping.heightMissing">{{ t('personal.atlas.heightMissing') }}</p>
        <Button v-if="canResolve" variant="ghost" size="sm" @click="emit('profile')">
          {{
            t(
              mapping.genderMissing
                ? 'personal.atlas.specifyGender'
                : 'personal.atlas.specifyHeight',
            )
          }}
        </Button>
      </div>
    </template>
    <template v-else>
      <p class="text-muted-foreground mt-4 text-sm">
        {{
          t(
            fallbackMetrics.length
              ? 'personal.dashboard.separateMeasurements'
              : 'personal.dashboard.noDeviceAnalysis',
          )
        }}
      </p>
      <ul v-if="fallbackMetrics.length" class="mt-3 grid gap-3 sm:grid-cols-2">
        <li
          v-for="metric in fallbackMetrics"
          :key="metric.kind"
          class="dashboard-body__fallback min-w-0 rounded-2xl border p-3 break-words"
          :data-testid="`dashboard-body-${metric.kind}`"
        >
          <p class="text-muted-foreground text-sm">{{ t(`personal.metric.${metric.kind}`) }}</p>
          <p class="mt-1 text-2xl font-semibold tabular-nums">
            {{ amountText(metric.kind, metric.value) }}
          </p>
          <p
            v-for="entry in metric.records?.filter((entry) => !entry.excluded)"
            :key="entry.id"
            class="text-muted-foreground mt-1 hidden text-xs md:block"
          >
            <time :datetime="entry.start_at">{{ when(entry.start_at) }}</time> ·
            {{ provenance(entry, (origin) => t(`personal.origin.${origin}`)) }}
          </p>
          <p v-if="metric.source_unavailable" class="text-warning mt-2 text-sm">
            {{ t('personal.sourceDown') }}
          </p>
        </li>
      </ul>
    </template>

    <ul
      v-if="secondaryFat.length"
      class="dashboard-body__other-methods mt-4 grid gap-3 sm:grid-cols-2"
      data-testid="dashboard-body-other-methods"
    >
      <li
        v-for="source in secondaryFat"
        :key="source.source_id ?? 'manual'"
        class="dashboard-body__other-method min-w-0 rounded-2xl border p-3 break-words"
        :data-source-id="source.source_id ?? 'manual'"
      >
        <button
          type="button"
          class="dashboard-body__other-summary min-h-11 w-full rounded-lg text-left"
          @click="openDetails"
        >
          <span class="text-muted-foreground block text-sm">
            {{ t('personal.metric.body_fat_percent') }} ·
            {{ source.source_name ?? t('personal.caliper.source') }}
          </span>
          <span class="mt-1 block text-xl font-semibold tabular-nums">
            {{
              source.status === 'conflict'
                ? t('resolution.status.conflict')
                : (amountText('body_fat_percent', source.value) ?? t('personal.noData'))
            }}
          </span>
        </button>
        <p v-if="sourceRecords(source).length" class="text-muted-foreground mt-1 text-xs md:hidden">
          {{
            sourceRecords(source).length === 1
              ? when(sourceRecords(source)[0]!.start_at)
              : t('personal.dashboard.sourceRecords', sourceRecords(source).length)
          }}
        </p>
        <p
          v-for="entry in sourceRecords(source)"
          :key="entry.id"
          class="text-muted-foreground mt-1 hidden text-xs md:block"
        >
          <time :datetime="entry.start_at">{{ when(entry.start_at) }}</time> ·
          {{ provenance(entry, (origin) => t(`personal.origin.${origin}`)) }}
        </p>
        <p
          v-if="source.source_unavailable || source.status === 'unavailable'"
          class="text-warning mt-1 text-sm"
        >
          {{ t('personal.sourceDown') }}
        </p>
      </li>
    </ul>
    <DataNotes
      class="mt-2 hidden md:block"
      :notes="[
        restricted && t('personal.sourceError.history_restricted'),
        !!(shown && mapping) && t('personal.atlas.schematic'),
      ]"
    />
    <slot name="caliper" />
    <RefreshFailed class="mt-3" :query="history" />
    <div v-if="history.isLoadingError.value" class="mt-3">
      <ErrorState :error="history.error.value" @retry="history.refetch()" />
    </div>

    <Panel
      v-model:open="detailsOpen"
      :title="t('personal.bodyComposition')"
      :autofocus="false"
      data-testid="body-details"
    >
      <DataNotes
        class="md:hidden"
        :notes="[historyOnlyRestricted && t('personal.sourceError.history_restricted')]"
      />
      <template v-if="detailAnalysis">
        <BodyAtlas
          :analysis="detailAnalysis"
          :analyses="snapshot.analyses"
          :profile="profile"
          :can-write="canResolve"
          :today="today"
          :timezone="timezone"
          @profile="emit('profile')"
        />
        <BodySourceDetails
          v-if="sourceIsDdx"
          :key="detailAnalysis.id"
          :archive="detailAnalysis.archive ?? null"
          :archive-list-truncated="history.data.value?.truncated"
          :today="today"
          :timezone="timezone"
        />
        <div ref="analysisElement">
          <BodyAnalysisCard
            :analysis="detailAnalysis"
            :today="today"
            :timezone="timezone"
            :can-write="canResolve"
            @edit="editRecord"
          />
        </div>
      </template>
      <BodyComposition
        :metrics="metrics"
        :today="today"
        :timezone="timezone"
        :day="day"
        :sources="sources"
        :fat-sources="fatSources"
      />
      <Button variant="outline" as-child>
        <RouterLink :to="historyRoute" @click="capturePersonalReturn(historyRoute)"
          >{{ t('personal.atlas.open') }}<ChevronRight aria-hidden="true"
        /></RouterLink>
      </Button>
    </Panel>
    <RecordPanel
      v-if="canResolve && record"
      v-model:open="recordOpen"
      :record="record"
      :today="today"
    />
  </section>
</template>

<style scoped>
.dashboard-body {
  container-type: inline-size;
  container-name: dashboard-body;
}
.dashboard-body__overview {
  display: grid;
  grid-template-columns: minmax(104px, 0.8fr) minmax(0, 1.5fr);
  gap: 12px;
  align-items: center;
}
.dashboard-body__figure {
  position: relative;
  overflow: hidden;
  width: 100%;
  max-width: 224px;
  height: 224px;
  min-width: 0;
  justify-self: center;
}
.dashboard-body__figure :deep(.body-figure) {
  /* Keep the renderer's camera aspect square; the portrait wrapper clips only
     the empty side margins of the renderer and unchanged poster artwork. */
  position: absolute;
  left: 50%;
  width: 224px;
  height: 224px;
  max-width: none;
  min-height: 0;
  transform: translateX(-50%);
}
.dashboard-body__metrics {
  display: grid;
  gap: 10px;
  min-width: 0;
}
.dashboard-body__metric {
  min-width: 0;
  overflow-wrap: anywhere;
}
.dashboard-body__scan-caption {
  overflow-wrap: anywhere;
}
@container dashboard-body (min-width: 600px) {
  .dashboard-body__overview {
    grid-template-columns: minmax(160px, 0.7fr) minmax(0, 1.5fr);
  }
  .dashboard-body__figure {
    max-width: 264px;
    height: 264px;
  }
  .dashboard-body__figure :deep(.body-figure) {
    width: 264px;
    height: 264px;
  }
  .dashboard-body__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container dashboard-body (max-width: 279px) {
  .dashboard-body__overview {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
}
@media (max-width: 767px) {
  .dashboard-body__scan-caption {
    margin-top: 0.5rem;
  }
  .dashboard-body__overview {
    grid-template-columns: minmax(0, 1fr) 112px;
    gap: 12px;
  }
  .dashboard-body__figure {
    order: 2;
    width: 112px;
    max-width: 112px;
    height: 176px;
  }
  .dashboard-body__figure :deep(.body-figure) {
    width: 176px;
    height: 176px;
  }
  .dashboard-body__metrics {
    order: 1;
    gap: 14px;
    grid-template-columns: minmax(0, 1fr);
  }
  .dashboard-body__metric {
    min-height: 44px;
    padding: 0;
    border: 0;
    border-radius: 0.5rem;
  }
  .dashboard-body__controls {
    margin-top: 0.5rem;
  }
  .dashboard-body__controls :deep([data-slot='button']) {
    gap: 0.25rem;
    padding-inline: 0.5rem;
    font-size: 0.8125rem;
  }
  .dashboard-body__fallback,
  .dashboard-body__other-method {
    padding: 0;
    border: 0;
    border-radius: 0;
  }
  .dashboard-body__other-methods {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
    margin-top: 12px;
  }
  .dashboard-body__other-summary {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 4px 12px;
  }
  .dashboard-body__other-summary > span {
    margin-top: 0;
  }
}
</style>
