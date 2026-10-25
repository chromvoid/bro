<script setup lang="ts">
import messages from '@/locales/ring-collector-ru.json'
import { useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { dayIn, momentLabel } from '@/core/dates'
import { errorText } from '@/core/errors'
import type { z } from '@/core/zod'
import { useSession } from '@/stores/session'
import RecordQuality from '@/sections/personal/RecordQuality.vue'
import type { Rings } from '@/sections/personal/sensors'
import StoredRingState from './StoredRingState.vue'

type Collector = z.infer<typeof Rings>['rings'][number]

/**
 * One collector as the server sees it: its last delivery and problem, the ring's saved state and
 * how each metric arrives. Import is switched off here, through the server.
 */
const props = defineProps<{ collector: Collector }>()
const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const { te } = useI18n({ useScope: 'global' })
const session = useSession()
const queries = useQueryClient()
const canWrite = computed(() => session.can('personal.write'))
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const strap = computed(() => props.collector.profile === 'whoop-5')
const family = computed(() => (strap.value ? 'whoop' : 'ring'))
const busy = ref(false)
const error = ref('')
const labelTime = (value: string | null | undefined) =>
  value ? momentLabel(value, zone.value, today.value) : t(`${family.value}.never`)
const labelError = (code: string) =>
  te(`${family.value}.error.${code}`)
    ? t(`${family.value}.error.${code}`)
    : t(`${family.value}.error.request_failed`)
const metricName = (key: string) => (te(`ring.metric.${key}`) ? t(`ring.metric.${key}`) : key)
const importStatus: Record<string, string> = {
  available: t('ring.collector.importStatus.available'),
  unverified: t('ring.collector.importStatus.unverified'),
  missing: t('ring.collector.importStatus.missing'),
  unsupported: t('ring.collector.importStatus.unsupported'),
  no_observation: t('ring.collector.importStatus.no_observation'),
  unsupported_evidenced: t('ring.collector.importStatus.unsupported_evidenced'),
  source_unavailable: t('ring.collector.importStatus.source_unavailable'),
  protocol_unverified: t('ring.collector.importStatus.protocol_unverified'),
  semantic_unknown: t('ring.collector.importStatus.semantic_unknown'),
  ambiguous_layout: t('ring.collector.importStatus.ambiguous_layout'),
  workout_time_basis_unconfirmed: t('ring.collector.importStatus.workout_time_basis_unconfirmed'),
  interval_increment: t('ring.collector.importStatus.interval_increment'),
  day_total: t('ring.collector.importStatus.day_total'),
  counter: t('ring.collector.importStatus.counter'),
  day_counter_snapshot: t('ring.collector.importStatus.day_counter_snapshot'),
  workout_report: t('ring.collector.importStatus.workout_report'),
  point: t('ring.collector.importStatus.point'),
  interval_mean: t('ring.collector.importStatus.interval_mean'),
  sample_window_mean: t('ring.collector.importStatus.sample_window_mean'),
  range: t('ring.collector.importStatus.range'),
  sleep_report: t('ring.collector.importStatus.sleep_report'),
  reported_unknown: t('ring.collector.importStatus.reported_unknown'),
  reported_range: t('ring.collector.importStatus.reported_range'),
  reported_window: t('ring.collector.importStatus.reported_window'),
  partial: t('ring.collector.importStatus.partial'),
  unknown: t('ring.collector.importStatus.unknown'),
  connected: t('ring.collector.importStatus.connected'),
  disabled: t('ring.collector.importStatus.disabled'),
  unavailable: t('ring.collector.importStatus.unavailable'),
}
const captureStatus = {
  complete: t('ring.collector.captureStatus.complete'),
  empty: t('ring.collector.captureStatus.empty'),
  failed: t('ring.collector.captureStatus.failed'),
}
const captureScopes = {
  day: t('ring.collector.captureScopes.day'),
  history: t('ring.collector.captureScopes.history'),
  live: t('ring.collector.captureScopes.live'),
  workout: t('ring.collector.captureScopes.workout'),
  spot: t('ring.collector.captureScopes.spot'),
}
const captureContexts = {
  background: t('ring.collector.captureContexts.background'),
  running: t('ring.collector.captureContexts.running'),
  workout: t('ring.collector.captureContexts.workout'),
  recovery: t('ring.collector.captureContexts.recovery'),
  manual: t('ring.collector.captureContexts.manual'),
}
async function disable() {
  const user = session.me?.id
  if (!user || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api.post(
      `/v1/personal/rings/${encodeURIComponent(props.collector.collector_id)}/disable`,
      {},
      { expectedUser: user },
    )
    await queries.invalidateQueries({ queryKey: ['personal'] })
  } catch (failure) {
    error.value = errorText(failure, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="space-y-3 text-sm" data-testid="collector-details">
    <p class="text-muted-foreground">
      {{ t(`${family}.received`, { when: labelTime(collector.last_received_at) }) }}
    </p>
    <p v-if="collector.error" class="text-warning">{{ labelError(collector.error) }}</p>
    <StoredRingState
      v-if="collector.profile === 'qring-r20' && collector.device_state"
      :state="collector.device_state"
      :timezone="zone"
    />
    <details
      v-if="collector.metrics?.length"
      class="space-y-2"
      data-testid="ring-import-metric-status"
    >
      <summary class="text-primary min-h-11 cursor-pointer py-2">
        {{ t('ring.collector.title') }}
      </summary>
      <p class="text-muted-foreground text-xs">
        {{ t('ring.collector.hint') }}
      </p>
      <dl class="grid gap-3 sm:grid-cols-2">
        <div
          v-for="metric in collector.metrics"
          :key="metric.metric"
          :data-testid="`ring-import-metric-${metric.metric}`"
          class="space-y-1 rounded-xl border p-3"
        >
          <dt class="font-medium">{{ metricName(metric.metric) }}</dt>
          <dd>{{ importStatus[metric.status] }}</dd>
          <dd v-if="metric.reason" class="text-muted-foreground">
            {{ importStatus[metric.reason] }}
          </dd>
          <dd v-if="metric.raw_report_count" class="text-warning text-xs">
            {{ t('ring.collector.rawReports', { count: metric.raw_report_count }) }}
            <RouterLink
              :to="{
                name: 'personal-history',
                query: { kind: metric.original_kind ?? metric.metric, period: 7 },
              }"
              class="text-primary underline"
              >{{ t('ring.collector.viewOriginals') }}</RouterLink
            >
          </dd>
          <dd class="text-muted-foreground text-xs">
            {{ t('ring.collector.lastMeasurement') }} {{ labelTime(metric.last_measured_at) }}
          </dd>
          <dd v-if="metric.last_capture_at" class="text-muted-foreground text-xs">
            {{ t('ring.collector.lastCapture') }} {{ labelTime(metric.last_capture_at) }} ·
            {{
              metric.capture_status
                ? captureStatus[metric.capture_status]
                : t('ring.collector.captureUnknown')
            }}<template v-if="metric.capture_date">
              · {{ t('ring.collector.captureDate') }} {{ metric.capture_date }}</template
            >
          </dd>
          <dd
            v-if="metric.last_capture_at && metric.capture_scope"
            class="text-muted-foreground text-xs"
          >
            {{ t('ring.collector.captureScope') }} {{ captureScopes[metric.capture_scope] }}
          </dd>
          <dd
            v-if="metric.last_capture_at && metric.capture_context"
            class="text-muted-foreground text-xs"
          >
            {{ t('ring.collector.captureContext') }} {{ captureContexts[metric.capture_context] }}
          </dd>
          <dd
            v-if="
              metric.last_capture_at &&
              metric.capture_status === 'failed' &&
              metric.capture_error_code
            "
            class="text-warning text-xs"
          >
            {{
              te(`ring.error.${metric.capture_error_code}`)
                ? t(`ring.error.${metric.capture_error_code}`)
                : t('ring.error.query_failed')
            }}
          </dd>
          <dd class="text-muted-foreground text-xs">
            {{ t('ring.collector.received') }} {{ labelTime(metric.last_received_at) }} ·
            {{ importStatus[metric.import_status] ?? t('ring.collector.deliveryUnknown') }}
          </dd>
          <dd class="text-muted-foreground text-xs">
            {{ importStatus[metric.coverage.status]
            }}<template v-if="metric.coverage.start_at">
              · {{ labelTime(metric.coverage.start_at) }} —
              {{ labelTime(metric.coverage.end_at) }}</template
            >
          </dd>
          <dd class="text-muted-foreground text-xs">
            {{ t('ring.collector.shape') }}
            {{ importStatus[metric.coverage.shape] ?? metric.coverage.shape }}
          </dd>
          <dd v-if="metric.coverage.quality.length" class="text-muted-foreground text-xs">
            <RecordQuality :flags="metric.coverage.quality" />
          </dd>
        </div>
      </dl>
    </details>
    <Button
      v-if="canWrite && collector.enabled"
      size="sm"
      variant="ghost"
      :disabled="busy"
      @click="disable"
      >{{ t(`${family}.disableImport`) }}</Button
    >
    <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  </div>
</template>
