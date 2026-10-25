<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { dateLabel, dayIn } from '@/core/dates'
import { useSession } from '@/stores/session'
import MetricSourcesPanel from './MetricSourcesPanel.vue'
import {
  rankedContributions,
  shortNames,
  SOURCE_ROWS,
  useSources,
  useSourcesDay,
  type Contribution,
} from './sources'

/**
 * «Источник: кольцо, WHOOP» under a value: the devices that supplied it, the largest first.
 * It opens where this kind of measurement comes from.
 */
const props = defineProps<{ metric: string; contributions?: unknown[] | null; day: string }>()
const { t } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const canWrite = computed(() => session.can('personal.write'))
const row = computed(() => SOURCE_ROWS.find((item) => item.metrics.includes(props.metric)))
const ranked = computed(() => rankedContributions(props.contributions))
const shown = computed(() => !!row.value && ranked.value.length > 0)
const sources = useSources(shown)
const list = computed(() => sources.data.value?.sources ?? [])
const names = computed(() =>
  shortNames(t, list.value, (source) =>
    dateLabel(dayIn(zone.value, new Date(source.created_at!)), today.value),
  ),
)
const nameOf = (item: Contribution) =>
  (item.source_id && names.value.get(item.source_id)) ||
  item.source_name ||
  t(item.source_id ? 'sources.device.custom' : 'resolution.manual')
const text = computed(() =>
  t('sources.line', { names: [...new Set(ranked.value.map(nameOf))].join(', ') }),
)

// The day's decisions are read only once the sheet is asked for; it opens with them, since a pin
// carries the revision of the order it replaces.
const opened = ref(false)
const open = ref(false)
const page = useSourcesDay(toRef(props, 'day'), opened)
function show() {
  opened.value = true
  open.value = true
}
watch(
  () => props.day,
  () => {
    opened.value = false
    open.value = false
  },
)
</script>

<template>
  <div v-if="shown && row">
    <button
      type="button"
      class="text-primary inline-flex min-h-11 items-center text-left text-sm underline-offset-4 hover:underline"
      data-testid="source-line"
      @click="show"
    >
      {{ text }}
    </button>
    <MetricSourcesPanel
      v-if="opened && !page.isPending.value"
      v-model:open="open"
      :row="row"
      :sources="list"
      :page="page.data.value"
      :today="today"
      :day="day"
      :zone="zone"
      :names="names"
      :can-write="canWrite"
    />
  </div>
</template>
