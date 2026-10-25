<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import { ChevronLeft, ChevronRight, History as HistoryIcon } from '@lucide/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import LineChart from '@/components/chart/LineChart.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { momentLabel } from '@/core/dates'
import {
  INSTANT_KINDS,
  amountText,
  type History,
  type Kind,
  type Observation,
  type Profile,
} from './api'
import { bodyAnalyses, bodyPoints } from './body'
import BodyAnalysisCard from './BodyAnalysisCard.vue'
import BodyAtlas from './BodyAtlas.vue'
import BodySourceDetails from './BodySourceDetails.vue'

const displayUnits = useDisplayUnits()
const props = defineProps<{
  history: History
  today: string
  timezone: string
  canWrite: boolean
  profile?: Profile | null
}>()
const emit = defineEmits<{ edit: [record: Observation]; profile: [] }>()
const { t } = useI18n()
const analyses = computed(() => bodyAnalyses(props.history.records, props.history.source_analyses))
const restricted = computed(
  () => props.history.sources?.filter((source) => source.error === 'history_restricted') ?? [],
)
const sourceOptions = computed(() => {
  const options = new Map<string, string>()
  for (const analysis of analyses.value) {
    if (analysis.sourceId)
      options.set(analysis.sourceId, analysis.sourceName ?? t('personal.unnamedSource'))
  }
  return [...options].map(([id, name]) => ({ id, name }))
})
const chosenSource = ref('')
const source = computed(() =>
  sourceOptions.value.some((option) => option.id === chosenSource.value)
    ? chosenSource.value
    : (sourceOptions.value[0]?.id ?? ''),
)
const selectedId = ref('')
const sourceAnalyses = computed(() =>
  analyses.value.filter((item) => (item.sourceId ?? '') === source.value),
)
const selected = computed(
  () =>
    sourceAnalyses.value.find((item) => item.id === selectedId.value) ?? sourceAnalyses.value[0],
)
const selectedIndex = computed(() =>
  sourceAnalyses.value.findIndex((item) => item.id === selected.value?.id),
)
const selectedIsDdx = computed(
  () =>
    !!selected.value?.archive ||
    props.history.sources?.some(
      (item) => item.id === selected.value?.sourceId && item.connection?.provider === 'ddx',
    ) ||
    selected.value?.records.some(
      (record) => (record.original?.origin ?? record.origin)?.provider === 'DDX Fitness',
    ),
)
function moveAnalysis(direction: number) {
  const next = sourceAnalyses.value[selectedIndex.value + direction]
  if (next) selectedId.value = next.id
}
const kind = ref<Kind>('body_fat_percent')
const availableKinds = computed(() =>
  INSTANT_KINDS.filter((option) =>
    analyses.value.some(
      (analysis) => analysis.sourceId === source.value && analysis.values[option],
    ),
  ),
)
const chartKind = computed(() =>
  availableKinds.value.includes(kind.value as (typeof INSTANT_KINDS)[number])
    ? kind.value
    : (availableKinds.value[0] ?? 'body_fat_percent'),
)
const points = computed(() => bodyPoints(analyses.value, source.value, chartKind.value))

// Keep the mounted list bounded even when a year contains hundreds of scans.
const PAGE_SIZE = 12
const page = ref(0)
const pageCount = computed(() => Math.ceil(analyses.value.length / PAGE_SIZE))
const pageAnalyses = computed(() =>
  analyses.value.slice(page.value * PAGE_SIZE, (page.value + 1) * PAGE_SIZE),
)
const listHeading = useTemplateRef<HTMLElement>('listHeading')
watch([() => props.history.from, () => props.history.to], () => {
  page.value = 0
})
watch(pageCount, (count) => {
  page.value = Math.min(page.value, Math.max(0, count - 1))
})
function movePage(direction: number) {
  page.value = Math.max(0, Math.min(pageCount.value - 1, page.value + direction))
  listHeading.value?.scrollIntoView?.({ block: 'start' })
}
</script>

<template>
  <div class="space-y-5" data-testid="body-history">
    <Alert v-if="restricted.length" variant="warning">
      <AlertTitle>{{ t('personal.restrictedHistory') }}</AlertTitle>
      <AlertDescription>{{ t('personal.sourceError.history_restricted') }}</AlertDescription>
    </Alert>
    <Alert v-if="history.truncated" variant="warning">
      <AlertDescription>{{ t('personal.atlas.truncated') }}</AlertDescription>
    </Alert>
    <div v-if="analyses.length" class="space-y-4">
      <div v-if="sourceOptions.length > 1" class="max-w-sm space-y-1.5">
        <Label for="body-analysis-source">{{ t('personal.preferredSource') }}</Label>
        <NativeSelect
          id="body-analysis-source"
          :model-value="source"
          @update:model-value="chosenSource = $event ?? ''"
        >
          <option v-for="option in sourceOptions" :key="option.id" :value="option.id">
            {{ option.name }}
          </option>
        </NativeSelect>
      </div>
      <div v-if="selected" class="space-y-2">
        <Label for="body-analysis-select">{{ t('personal.atlas.selectAnalysis') }}</Label>
        <div class="flex items-center gap-2">
          <Button
            variant="outline"
            size="icon"
            :disabled="selectedIndex >= sourceAnalyses.length - 1"
            :aria-label="t('personal.atlas.older')"
            @click="moveAnalysis(1)"
            ><ChevronLeft
          /></Button>
          <NativeSelect
            id="body-analysis-select"
            :model-value="selected.id"
            class="min-w-0 flex-1"
            @update:model-value="selectedId = $event ?? ''"
          >
            <option v-for="(item, index) in sourceAnalyses" :key="item.id" :value="item.id">
              {{ momentLabel(item.measuredAt, timezone, today) }} ·
              {{ t('personal.atlas.scanNumber', { number: sourceAnalyses.length - index }) }}
            </option>
          </NativeSelect>
          <Button
            variant="outline"
            size="icon"
            :disabled="selectedIndex <= 0"
            :aria-label="t('personal.atlas.newer')"
            @click="moveAnalysis(-1)"
            ><ChevronRight
          /></Button>
        </div>
      </div>
      <BodyAtlas
        v-if="selected"
        :analysis="selected"
        :analyses="sourceAnalyses"
        :profile="profile"
        :can-write="canWrite"
        :today="today"
        :timezone="timezone"
        @profile="emit('profile')"
      />
      <BodySourceDetails
        v-if="selected && selectedIsDdx"
        :key="selected.id"
        :archive="selected.archive ?? null"
        :today="today"
        :timezone="timezone"
        :archive-list-truncated="history.truncated"
      />
    </div>
    <section
      v-if="sourceOptions.length"
      class="bg-card space-y-4 rounded-3xl border p-5"
      aria-labelledby="body-trend-title"
    >
      <div>
        <h2 id="body-trend-title" class="font-semibold">{{ t('personal.bodyTrend') }}</h2>
      </div>
      <div class="grid gap-3 sm:grid-cols-2">
        <div v-if="availableKinds.length" class="space-y-1.5">
          <Label for="body-trend-kind">{{ t('personal.kindFilter') }}</Label>
          <NativeSelect
            id="body-trend-kind"
            :model-value="chartKind"
            @update:model-value="kind = $event as Kind"
          >
            <option v-for="option in availableKinds" :key="option" :value="option">
              {{ t(`personal.metric.${option}`) }}
            </option>
          </NativeSelect>
        </div>
      </div>
      <LineChart
        v-if="points.length > 1"
        :points="points"
        :label="t('personal.bodyTrendLabel', { kind: t(`personal.metric.${chartKind}`) })"
        :format-x="(value) => momentLabel(new Date(value).toISOString(), timezone, today)"
        :format-y="(value) => amountText(chartKind, String(value), displayUnits) ?? '—'"
      />
      <p v-else class="text-muted-foreground rounded-xl border border-dashed px-4 py-5 text-sm">
        {{ t('personal.bodyTrendEmpty') }}
      </p>
    </section>
    <div v-if="analyses.length" ref="listHeading" class="scroll-mt-20 space-y-1">
      <h2 class="font-semibold">
        {{ t('personal.analysisCount', { count: analyses.length }, analyses.length) }}
      </h2>
    </div>
    <EmptyState
      v-else
      :icon="HistoryIcon"
      :title="t('personal.bodyHistoryEmpty')"
      :description="t('personal.bodyHistoryEmptyText')"
    />
    <ul class="space-y-4">
      <li v-for="analysis in pageAnalyses" :key="analysis.id" class="body-history__card">
        <BodyAnalysisCard
          :analysis="analysis"
          :today="today"
          :timezone="timezone"
          :can-write="canWrite"
          @edit="emit('edit', $event)"
        />
      </li>
    </ul>
    <nav
      v-if="pageCount > 1"
      class="flex items-center justify-between gap-3"
      :aria-label="t('personal.analysisPages')"
    >
      <Button variant="outline" size="sm" :disabled="page === 0" @click="movePage(-1)">
        <ChevronLeft class="size-4" aria-hidden="true" />{{ t('personal.archive.previous') }}
      </Button>
      <span class="text-muted-foreground text-sm tabular-nums" role="status">{{
        t('personal.archive.page', { page: page + 1, total: pageCount })
      }}</span>
      <Button variant="outline" size="sm" :disabled="page + 1 >= pageCount" @click="movePage(1)">
        {{ t('personal.archive.next') }}<ChevronRight class="size-4" aria-hidden="true" />
      </Button>
    </nav>
  </div>
</template>

<style scoped>
.body-history__card {
  content-visibility: auto;
  contain-intrinsic-size: auto 300px;
}
</style>
