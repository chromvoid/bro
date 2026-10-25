<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import { History } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { momentLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import {
  BODY_KINDS,
  amountText,
  originOf,
  provenance,
  type BodyKind,
  type Metric,
  type Source,
  type SourceMetric,
} from './api'
import { capturePersonalReturn, personalHistoryLink } from './personalNavigation'

const displayUnits = useDisplayUnits()
const props = defineProps<{
  metrics: Partial<Record<BodyKind, Metric>>
  today: string
  timezone: string
  day?: string
  sources?: Source[]
  fatSources?: SourceMetric[]
}>()
const { t } = useI18n()
const historyLink = computed(() =>
  personalHistoryLink(props.day ?? props.today, 'body', 'body', 365),
)
const metricLinks = computed(() =>
  Object.fromEntries(
    BODY_KINDS.map((kind) => [kind, personalHistoryLink(props.day ?? props.today, 'body', kind)]),
  ),
)
const bodySources = computed(
  () =>
    props.sources?.filter((source) =>
      source.kinds.some((kind) => (BODY_KINDS as readonly string[]).includes(kind)),
    ) ?? [],
)
const restricted = computed(() =>
  bodySources.value.some((source) => source.error === 'history_restricted'),
)
// Each kind can come from a different analysis. Keep each record's date and source visible.
const reported = computed(() =>
  BODY_KINDS.map((kind) => props.metrics[kind]).filter(
    (metric): metric is Metric =>
      !!metric &&
      metric.status !== 'no_data' &&
      (amountText(metric.kind, metric.value, displayUnits.value) !== null ||
        metric.status === 'conflict' ||
        (metric.kind === 'body_fat_percent' && !!props.fatSources?.length)),
  ),
)
</script>

<template>
  <section
    v-if="reported.length || bodySources.length"
    class="bg-card space-y-4 rounded-3xl border p-5"
    aria-labelledby="composition-title"
    data-testid="body-composition"
  >
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 id="composition-title" class="font-semibold">{{ t('personal.bodyComposition') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('personal.latestBodyMeasurements') }}</p>
      </div>
      <Button variant="outline" size="sm" as-child>
        <RouterLink :to="historyLink" @click="capturePersonalReturn(historyLink)"
          ><History />{{ t('personal.atlas.open') }}</RouterLink
        >
      </Button>
    </div>
    <p v-if="restricted" class="text-warning text-sm">
      {{ t('personal.sourceError.history_restricted') }}
    </p>
    <p v-if="!reported.length" class="text-muted-foreground text-sm">
      {{ t('personal.bodyNotReceived') }}
    </p>
    <ul class="grid gap-5 sm:grid-cols-2">
      <li v-for="metric in reported" :key="metric.kind" :data-testid="`metric-${metric.kind}`">
        <h3 class="text-muted-foreground text-sm font-medium">
          <RouterLink
            :to="metricLinks[metric.kind]!"
            class="hover:text-primary hover:underline"
            @click="capturePersonalReturn(metricLinks[metric.kind]!)"
            >{{ t(`personal.metric.${metric.kind}`) }}</RouterLink
          >
        </h3>
        <ul
          v-if="metric.kind === 'body_fat_percent' && fatSources?.length"
          class="mt-2 space-y-3"
          data-testid="body-fat-sources"
        >
          <li
            v-for="source in fatSources"
            :key="source.source_id ?? 'manual'"
            :data-source-id="source.source_id ?? 'manual'"
          >
            <p class="text-sm font-medium">
              {{ source.source_name ?? t('personal.caliper.source') }}
            </p>
            <p v-if="source.value !== null" class="text-2xl font-semibold tabular-nums">
              {{ formatAmount(source.value, 1) }} %
            </p>
            <p v-else class="text-muted-foreground text-sm">{{ t('personal.noData') }}</p>
            <ul class="text-muted-foreground mt-1 space-y-1 text-sm">
              <li
                v-for="record in source.records?.filter((item) => !item.excluded)"
                :key="record.id"
              >
                <time :datetime="record.start_at" class="block">{{
                  momentLabel(record.start_at, timezone, today)
                }}</time>
                {{ t(`personal.origin.${originOf(record)}`) }}
              </li>
            </ul>
            <p v-if="source.source_unavailable" class="text-warning mt-1 text-sm">
              {{ t('personal.sourceDown') }}
            </p>
          </li>
        </ul>
        <p v-else-if="metric.status === 'conflict'" class="text-warning text-lg font-semibold">
          {{ t('resolution.status.conflict') }}
        </p>
        <p v-else class="mt-1 text-2xl font-semibold tabular-nums">
          {{ amountText(metric.kind, metric.value, displayUnits) }}
        </p>
        <ul
          v-if="metric.kind !== 'body_fat_percent' || !fatSources?.length"
          class="text-muted-foreground mt-1 space-y-1 text-sm"
        >
          <li v-for="record in metric.records?.filter((item) => !item.excluded)" :key="record.id">
            <time :datetime="record.start_at" class="block">
              {{ momentLabel(record.start_at, timezone, today) }}
            </time>
            {{ provenance(record, (origin) => t(`personal.origin.${origin}`)) }}
          </li>
        </ul>
        <p v-if="metric.source_unavailable" class="text-warning mt-1 text-sm">
          {{ t('personal.sourceDown') }}
        </p>
      </li>
    </ul>
    <details v-if="reported.length" class="border-t pt-3 text-sm">
      <summary class="text-primary cursor-pointer font-medium">
        {{ t('personal.aboutBodyMetrics') }}
      </summary>
      <dl class="mt-3 space-y-3">
        <div v-for="metric in reported" :key="metric.kind">
          <dt class="font-medium">{{ t(`personal.metric.${metric.kind}`) }}</dt>
          <dd class="text-muted-foreground mt-1">{{ t(`personal.bodyMeaning.${metric.kind}`) }}</dd>
        </div>
      </dl>
    </details>
  </section>
</template>
