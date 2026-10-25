<script setup lang="ts">
import { History } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import DateField from '@/components/DateField.vue'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { dayLabel, shiftDay, timeIn, zonedInstant } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import type { ResolvedPage } from './resolutionContract'
import {
  combinedComparisons,
  contributors,
  pinOf,
  policyEntries,
  rowDaily,
  sourcesOf,
  TIMED_ROWS,
  useSourcesWeek,
  type SourceEntry,
  type SourceRowSpec,
} from './sources'
import { usePersonal } from './usePersonal'

/**
 * One kind of measurement: which source it takes — automatically the best data in every slot,
 * or always one device while it is worn — from which day, how a day was split between devices
 * and how far they disagree over the last week.
 */
const props = defineProps<{
  row: SourceRowSpec
  sources: SourceEntry[]
  /** The decisions of `day`. */
  page: ResolvedPage | undefined
  today: string
  /** The day whose split is shown; today unless the sheet opened from an earlier day. */
  day?: string
  zone: string
  names: Map<string, string>
  canWrite: boolean
}>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const personal = usePersonal()
const AUTOMATIC = 'automatic'
const BAR_COLOURS = ['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4', 'bg-chart-5']
const VITALS = new Set(['heart_rate', 'hrv', 'spo2', 'temperature', 'stress'])

const candidates = computed(() => sourcesOf(props.row, props.sources))
const policies = computed(() => props.page?.policies ?? [])
const pinned = computed(() => pinOf(props.row, policies.value, props.today))
const current = computed(() =>
  pinned.value && candidates.value.some((source) => source.id === pinned.value)
    ? pinned.value
    : AUTOMATIC,
)
const choice = ref(current.value)
const effectiveFrom = ref(props.today)
const busy = ref(false)
watch(
  () => [open.value, props.row.key] as const,
  ([shown]) => {
    if (!shown) return
    choice.value = current.value
    effectiveFrom.value = props.today
  },
  { immediate: true },
)
const changed = computed(
  () => choice.value !== current.value || effectiveFrom.value !== props.today,
)
const name = (id: string | null, fallback?: string | null) =>
  (id && props.names.get(id)) || fallback || t('sources.device.custom')

const metric = computed(() => props.row.metrics[0]!)
const unit = computed(() => {
  const value = props.page?.catalog?.find((item) => item.key === metric.value)?.unit
  const key = `sources.unit.${value}`
  return value && t(key) !== key ? t(key) : ''
})
const amount = (value: number, digits = 0) =>
  [formatAmount(value, digits), unit.value].filter(Boolean).join(' ')

// The day: who supplied the value, when, on a bar across the day.
const timed = computed(() => TIMED_ROWS.has(props.row.key))
const shownDay = computed(() => props.day ?? props.today)
const split = computed(() => contributors(rowDaily(props.row, props.page, shownDay.value)))
const dayStart = computed(() =>
  Date.parse(zonedInstant(`${shownDay.value}T00:00`, props.zone) ?? ''),
)
const dayEnd = computed(() =>
  Date.parse(zonedInstant(`${shiftDay(shownDay.value, 1)}T00:00`, props.zone) ?? ''),
)
const segments = computed(() => {
  const length = dayEnd.value - dayStart.value
  if (!Number.isFinite(length) || length <= 0) return []
  return split.value.flatMap((item, index) =>
    item.spans.map((span) => {
      const start = Math.max(Date.parse(span.start_at), dayStart.value)
      const end = Math.min(Date.parse(span.end_at), dayEnd.value)
      return {
        key: `${item.source_id}-${span.start_at}`,
        colour: BAR_COLOURS[index % BAR_COLOURS.length]!,
        left: ((start - dayStart.value) / length) * 100,
        width: Math.max(((end - start) / length) * 100, 0.5),
      }
    }),
  )
})
const spanText = (spans: { start_at: string; end_at: string }[]) =>
  spans
    .slice(0, 3)
    .map((span) => `${timeIn(props.zone, span.start_at)}–${timeIn(props.zone, span.end_at)}`)
    .join(', ') + (spans.length > 3 ? ' …' : '')

// The last week: how two devices differ where both measured.
const comparable = computed(
  () => open.value && candidates.value.length > 1 && (timed.value || VITALS.has(metric.value)),
)
const week = useSourcesWeek(
  computed(() => (comparable.value ? metric.value : null)),
  computed(() => props.today),
)
const comparisons = computed(() => (comparable.value ? combinedComparisons(week.data.value) : []))
function comparisonLines(item: (typeof comparisons.value)[number]) {
  const [first] = item.source_ids
  if (VITALS.has(metric.value)) {
    return [
      t('sources.meanDifference', { value: amount(item.mean_absolute_difference, 1) }),
      t('sources.matches', item.count),
      ...(item.outside_reference ? [t('sources.outside', { count: item.outside_reference })] : []),
    ]
  }
  const [mine, theirs] = item.totals
  const share = theirs ? Math.round(Math.abs(mine / theirs - 1) * 100) : 0
  return [
    share < 1
      ? t('sources.same')
      : t(mine > theirs ? 'sources.more' : 'sources.less', { name: name(first), value: share }),
    t('sources.hours', item.count),
  ]
}

const historyKind = computed(() => (props.row.key === 'body' ? 'body' : metric.value))

async function save() {
  if (busy.value || !changed.value) return
  busy.value = true
  try {
    const source = choice.value === AUTOMATIC ? null : choice.value
    const entries = policyEntries(
      props.row,
      source,
      candidates.value,
      policies.value,
      effectiveFrom.value,
    )
    const expected = Object.fromEntries(
      entries.flatMap((entry) =>
        entry.existing?.id ? [[entry.existing.id, entry.existing.revision]] : [],
      ),
    )
    const orders = entries.map(({ metric, source_ids, revision }) => ({
      metric,
      source_ids,
      revision,
    }))
    if (orders.length === 1)
      await personal.operate(
        'resolution.policy.set',
        { ...orders[0], effective_from: effectiveFrom.value },
        expected,
      )
    else if (orders.length)
      await personal.operate(
        'resolution.policies.set',
        { effective_from: effectiveFrom.value, policies: orders },
        expected,
      )
    toast.success(t('sources.saved'))
    open.value = false
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Panel
    v-model:open="open"
    :title="t(`sources.row.${row.key}`)"
    :autofocus="false"
    data-testid="metric-sources-panel"
  >
    <RadioGroup
      v-model="choice"
      class="gap-2"
      :disabled="!canWrite || busy"
      data-testid="metric-sources-choice"
    >
      <Label
        class="has-[[data-state=checked]]:border-primary flex items-start gap-3 rounded-2xl border p-3 font-normal"
      >
        <RadioGroupItem :value="AUTOMATIC" class="mt-0.5" />
        <span>
          <span class="block font-medium">{{ t('sources.automatic') }}</span>
          <span class="text-muted-foreground block text-sm">{{ t('sources.automaticText') }}</span>
        </span>
      </Label>
      <Label
        v-for="source in candidates"
        :key="source.id"
        class="has-[[data-state=checked]]:border-primary flex items-start gap-3 rounded-2xl border p-3 font-normal"
        :data-source-id="source.id"
      >
        <RadioGroupItem :value="source.id" class="mt-0.5" />
        <span>
          <span class="block font-medium">{{
            t('sources.always', { name: name(source.id) })
          }}</span>
          <span class="text-muted-foreground block text-sm">{{ t('sources.alwaysText') }}</span>
        </span>
      </Label>
    </RadioGroup>

    <div v-if="canWrite" class="space-y-2">
      <Label for="metric-sources-from">{{ t('sources.effectiveFrom') }}</Label>
      <DateField id="metric-sources-from" v-model="effectiveFrom" :max="today" :disabled="busy" />
    </div>

    <section v-if="timed" class="space-y-2" aria-labelledby="metric-sources-today">
      <h3 id="metric-sources-today" class="font-medium">
        {{ shownDay === today ? t('sources.today') : dayLabel(shownDay, today) }}
      </h3>
      <template v-if="split.length">
        <div class="bg-muted relative h-3 overflow-hidden rounded-full" aria-hidden="true">
          <span
            v-for="segment in segments"
            :key="segment.key"
            class="absolute inset-y-0 rounded-full"
            :class="segment.colour"
            :style="{ left: `${segment.left}%`, width: `${segment.width}%` }"
          />
        </div>
        <ul class="space-y-1 text-sm" data-testid="metric-sources-today">
          <li v-for="(item, index) in split" :key="item.source_id ?? 'manual'" class="flex gap-2">
            <span
              class="mt-1.5 size-2.5 shrink-0 rounded-full"
              :class="BAR_COLOURS[index % BAR_COLOURS.length]"
              aria-hidden="true"
            />
            <span>
              <span class="font-medium">{{ name(item.source_id, item.source_name) }}</span>
              <span class="text-muted-foreground">
                ·
                {{
                  item.value !== null && item.value !== undefined
                    ? amount(item.value)
                    : spanText(item.spans)
                }}
              </span>
            </span>
          </li>
        </ul>
      </template>
      <p v-else class="text-muted-foreground text-sm">
        {{ shownDay === today ? t('sources.todayNone') : t('personal.noData') }}
      </p>
    </section>

    <section v-if="comparable" class="space-y-2" aria-labelledby="metric-sources-compare">
      <h3 id="metric-sources-compare" class="font-medium">{{ t('sources.compare') }}</h3>
      <ul v-if="comparisons.length" class="space-y-2" data-testid="metric-sources-compare">
        <li
          v-for="item in comparisons"
          :key="item.source_ids.join('|')"
          class="rounded-2xl border p-3 text-sm"
        >
          <p class="font-medium">
            {{ t('sources.pair', { a: name(item.source_ids[0]), b: name(item.source_ids[1]) }) }}
          </p>
          <p v-for="line in comparisonLines(item)" :key="line" class="text-muted-foreground">
            {{ line }}
          </p>
        </li>
      </ul>
      <p v-else-if="!week.isPending.value" class="text-muted-foreground text-sm">
        {{ t('sources.noComparison') }}
      </p>
    </section>

    <RouterLink
      :to="{ name: 'personal-history', query: { kind: historyKind, period: 7 } }"
      class="text-primary inline-flex min-h-11 items-center gap-1 text-sm underline-offset-4 hover:underline"
      ><History class="size-4" aria-hidden="true" />{{ t('sources.history') }}</RouterLink
    >

    <template v-if="canWrite" #footer>
      <Button class="w-full" :disabled="!changed || busy" @click="save">{{
        t('sources.save')
      }}</Button>
    </template>
  </Panel>
</template>
