<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import historyMessages from '@/locales/personal-history-ru.json'
import { computed } from 'vue'
import { momentLabel } from '@/core/dates'
import RecordQuality from './RecordQuality.vue'

const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const props = defineProps<{
  record: {
    id?: string
    external_id?: string
    source_id?: string | null
    source_name?: string | null
    start_at?: string
    end_at?: string
    timezone?: string | null
    local_date?: string | null
    received_at?: string | null
    revision?: number
    algorithm_version?: string | null
    method?: string
    origin?: unknown
    value?: string | null
    reported_min?: string | null
    reported_max?: string | null
    unit?: string
    original?: unknown
    data?: unknown
    quality?: string[] | null
    excluded?: boolean | null
    corrected?: boolean | null
  }
  timezone: string
  today: string
}>()
const labels: Record<string, string> = {
  provider: t('personalHistory.record.fields.provider'),
  device: t('personalHistory.record.fields.device'),
  source: t('personalHistory.record.fields.source'),
  record_id: t('personalHistory.record.fields.record_id'),
  hardware: t('personalHistory.record.fields.hardware'),
  firmware: t('personalHistory.record.fields.firmware'),
  protocol: t('personalHistory.record.fields.protocol'),
  opcode: t('personalHistory.record.fields.opcode'),
  interpretation_version: t('personalHistory.record.fields.interpretation_version'),
  measurement_kind: t('personalHistory.record.fields.measurement_kind'),
  context: t('personalHistory.record.fields.context'),
  sensor_location: t('personalHistory.record.fields.sensor_location'),
  temperature_basis: t('personalHistory.record.fields.temperature_basis'),
  definition_id: t('personalHistory.record.fields.definition_id'),
  formula_version: t('personalHistory.record.fields.formula_version'),
  time_basis: t('personalHistory.record.fields.time_basis'),
  sport_type: t('personalHistory.record.fields.sport_type'),
  status: t('personalHistory.record.fields.status'),
  trigger: t('personalHistory.record.fields.trigger'),
  stop_reason: t('personalHistory.record.fields.stop_reason'),
  recovery_end_at: t('personalHistory.record.fields.recovery_end_at'),
  sample_count: t('personalHistory.record.fields.sample_count'),
  usable_sample_count: t('personalHistory.record.fields.usable_sample_count'),
  sampled_seconds: t('personalHistory.record.fields.sampled_seconds'),
  sample_coverage_ratio: t('personalHistory.record.fields.sample_coverage_ratio'),
  gap_count: t('personalHistory.record.fields.gap_count'),
  value: t('personalHistory.record.fields.value'),
  reported_min: t('personalHistory.record.fields.reported_min'),
  reported_max: t('personalHistory.record.fields.reported_max'),
  unit: t('personalHistory.record.fields.unit'),
  shape: t('personalHistory.record.fields.shape'),
  unit_evidence: t('personalHistory.record.fields.unit_evidence'),
  date_basis: t('personalHistory.record.fields.date_basis'),
  provider_namespace: t('personalHistory.record.fields.provider_namespace'),
  lineage_key: t('personalHistory.record.fields.lineage_key'),
  confidence: t('personalHistory.record.fields.confidence'),
}
const values: Record<string, string> = {
  measured: t('personalHistory.record.values.measured'),
  observed: t('personalHistory.record.values.observed'),
  interval_mean: t('personalHistory.record.values.interval_mean'),
  reported_range: t('personalHistory.record.values.reported_range'),
  sample_window_mean: t('personalHistory.record.values.sample_window_mean'),
  derived: t('personalHistory.record.values.derived'),
  running: t('personalHistory.record.values.running'),
  recovery: t('personalHistory.record.values.recovery'),
  background: t('personalHistory.record.values.background'),
  unknown: t('personalHistory.record.values.unknown'),
  phone_recorded: t('personalHistory.record.values.phone_recorded'),
  ring: t('personalHistory.record.values.ring'),
  manual: t('personalHistory.record.values.manual'),
  automatic: t('personalHistory.record.values.automatic'),
  manual_takeover: t('personalHistory.record.values.manual_takeover'),
  completed: t('personalHistory.record.values.completed'),
  interrupted: t('personalHistory.record.values.interrupted'),
}
function fields(input: unknown): Array<{ key: string; label: string; value: string }> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return []
  return Object.entries(input).flatMap(([key, value]) => {
    if (key === 'details' || key === 'resolution') return fields(value)
    if (!(key in labels) || value === null || value === undefined) return []
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
      return [{ key, label: labels[key]!, value: values[String(value)] ?? String(value) }]
    return []
  })
}
const metadata = computed(() => {
  const entries = [
    ...fields(props.record.origin),
    ...fields(props.record.data),
    ...fields({
      value: props.record.value,
      reported_min: props.record.reported_min,
      reported_max: props.record.reported_max,
      unit: props.record.unit,
    }),
  ]
  return [...new Map(entries.map((field) => [field.key, field])).values()]
})
</script>

<template>
  <details class="space-y-2 text-sm" data-testid="record-details">
    <summary class="text-primary min-h-11 cursor-pointer py-2">
      {{ t('personalHistory.record.title') }}
    </summary>
    <dl class="grid gap-2 sm:grid-cols-2">
      <div v-if="record.source_name">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.source') }}</dt>
        <dd>{{ record.source_name }}</dd>
      </div>
      <div v-if="record.start_at">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.start') }}</dt>
        <dd>{{ momentLabel(record.start_at, timezone, today) }}</dd>
      </div>
      <div v-if="record.end_at && record.end_at !== record.start_at">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.end') }}</dt>
        <dd>{{ momentLabel(record.end_at, timezone, today) }}</dd>
      </div>
      <div v-if="record.local_date">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.date') }}</dt>
        <dd>{{ record.local_date }}</dd>
      </div>
      <div v-if="record.timezone">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.timezone') }}</dt>
        <dd>{{ record.timezone }}</dd>
      </div>
      <div v-if="record.received_at">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.received') }}</dt>
        <dd>{{ momentLabel(record.received_at, timezone, today) }}</dd>
      </div>
      <div v-if="record.method">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.method') }}</dt>
        <dd>{{ values[record.method] ?? record.method }}</dd>
      </div>
      <div v-if="record.algorithm_version">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.algorithm') }}</dt>
        <dd>{{ record.algorithm_version }}</dd>
      </div>
      <div v-if="record.revision !== undefined">
        <dt class="text-muted-foreground">{{ t('personalHistory.record.revision') }}</dt>
        <dd>{{ record.revision }}</dd>
      </div>
      <div v-for="field in metadata" :key="field.key">
        <dt class="text-muted-foreground">{{ field.label }}</dt>
        <dd>{{ field.value }}</dd>
      </div>
    </dl>
    <p v-if="record.excluded" class="text-warning">{{ t('personalHistory.record.excluded') }}</p>
    <p v-if="record.corrected">{{ t('personalHistory.record.corrected') }}</p>
    <RecordQuality :flags="record.quality" />
    <details
      v-if="record.data || record.origin || record.value !== undefined"
      class="text-muted-foreground"
    >
      <summary class="min-h-11 cursor-pointer py-2">
        {{ t('personalHistory.record.original') }}
      </summary>
      <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(
          {
            value: record.value,
            reported_min: record.reported_min,
            reported_max: record.reported_max,
            unit: record.unit,
            data: record.data,
            origin: record.origin,
            quality: record.quality,
            original: record.original,
          },
          null,
          2,
        )
      }}</pre>
    </details>
    <details
      v-if="record.id || record.external_id || record.source_id"
      class="text-muted-foreground"
    >
      <summary class="cursor-pointer">{{ t('personalHistory.record.identifiers') }}</summary>
      <dl class="mt-2 space-y-1 break-all">
        <div v-if="record.id">
          <dt>{{ t('personalHistory.record.record') }}</dt>
          <dd>{{ record.id }}</dd>
        </div>
        <div v-if="record.external_id">
          <dt>{{ t('personalHistory.record.deviceRecord') }}</dt>
          <dd>{{ record.external_id }}</dd>
        </div>
        <div v-if="record.source_id">
          <dt>{{ t('personalHistory.record.source') }}</dt>
          <dd>{{ record.source_id }}</dd>
        </div>
      </dl>
    </details>
  </details>
</template>
