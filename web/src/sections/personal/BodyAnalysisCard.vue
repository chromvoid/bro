<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { momentLabel, momentPhrase } from '@/core/dates'
import { amountText, provenance, type Observation } from './api'
import { bodyDelta, type BodyAnalysis } from './body'

const displayUnits = useDisplayUnits()
const props = defineProps<{
  analysis: BodyAnalysis
  today: string
  timezone: string
  canWrite: boolean
}>()
const emit = defineEmits<{ edit: [record: Observation] }>()
const { t } = useI18n()
const keyKinds = ['weight', 'body_fat_percent', 'skeletal_muscle_mass'] as const
const hasPrevious = computed(() => Object.keys(props.analysis.previous).length > 0)
const detailsOpen = ref(false)
function toggleDetails(event: Event) {
  detailsOpen.value = (event.currentTarget as HTMLDetailsElement).open
}
</script>

<template>
  <article class="bg-card overflow-hidden rounded-3xl border" data-testid="body-analysis">
    <header class="space-y-1 px-5 pt-5">
      <h3 class="font-semibold">
        <time :datetime="analysis.measuredAt">{{
          momentLabel(analysis.measuredAt, timezone, today)
        }}</time>
      </h3>
      <p class="text-muted-foreground text-sm">
        {{ analysis.sourceName ?? t('personal.unnamedSource') }}
      </p>
    </header>
    <dl class="grid grid-cols-1 gap-4 px-5 py-5 min-[400px]:grid-cols-3">
      <div v-for="kind in keyKinds" :key="kind">
        <dt class="text-muted-foreground text-xs">{{ t(`personal.metric.${kind}`) }}</dt>
        <dd class="mt-1 text-xl font-semibold tabular-nums">
          {{ amountText(kind, analysis.values[kind]?.data.value ?? null, displayUnits) ?? '—' }}
        </dd>
        <dd v-if="!analysis.values[kind]" class="text-muted-foreground mt-1 text-xs">
          {{ t('personal.noValue') }}
        </dd>
        <dd
          v-else-if="analysis.previous[kind]"
          class="text-muted-foreground mt-1 text-xs tabular-nums"
        >
          {{ bodyDelta(analysis.values[kind]!, analysis.previous[kind]!.record, displayUnits) }}
          <span class="block">{{
            t('personal.comparedWith', {
              when: momentPhrase(analysis.previous[kind]!.measuredAt, timezone, today),
            })
          }}</span>
        </dd>
        <dd v-if="analysis.values[kind]?.corrected" class="text-muted-foreground mt-1 text-xs">
          {{ t('personal.origin.corrected') }}
        </dd>
      </div>
    </dl>
    <p v-if="!hasPrevious" class="text-muted-foreground px-5 pb-4 text-sm">
      {{ t('personal.noPreviousAnalysis') }}
    </p>
    <details class="border-t" data-testid="analysis-details" @toggle="toggleDetails">
      <summary class="text-primary cursor-pointer px-5 py-3 text-sm font-medium">
        {{ t('personal.analysisDetails', { count: analysis.records.length }) }}
      </summary>
      <ul v-if="detailsOpen" class="divide-y px-5">
        <li v-for="record in analysis.records" :key="record.id" class="space-y-2 py-4">
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="text-sm font-medium">{{ t(`personal.metric.${record.kind}`) }}</p>
              <p
                :class="[
                  'mt-1 text-lg font-semibold tabular-nums',
                  record.excluded && 'text-muted-foreground line-through',
                ]"
              >
                {{
                  amountText(record.kind, record.data.value, displayUnits) ?? t('personal.noValue')
                }}
              </p>
            </div>
            <Button
              v-if="canWrite"
              variant="ghost"
              size="sm"
              class="min-h-11"
              :data-record-id="record.id"
              :aria-label="
                t('personal.correctMetric', { kind: t(`personal.metric.${record.kind}`) })
              "
              @click="emit('edit', record)"
              >{{ t('personal.correctMeasurement') }}</Button
            >
          </div>
          <p
            v-if="analysis.values[record.kind]?.id === record.id && analysis.previous[record.kind]"
            class="text-muted-foreground text-sm tabular-nums"
          >
            {{ bodyDelta(record, analysis.previous[record.kind]!.record, displayUnits) }} ·
            {{
              t('personal.comparedWith', {
                when: momentPhrase(analysis.previous[record.kind]!.measuredAt, timezone, today),
              })
            }}
          </p>
          <p class="text-muted-foreground text-xs">
            {{ provenance(record, (origin) => t(`personal.origin.${origin}`)) }} ·
            {{ momentLabel(record.start_at, timezone, today) }}
          </p>
          <p v-if="record.corrected && record.original" class="text-muted-foreground text-xs">
            {{
              t('personal.originalValue', {
                value:
                  amountText(record.kind, record.original.data.value, displayUnits) ??
                  t('personal.noValue'),
              })
            }}
          </p>
          <Badge v-if="record.excluded" variant="outline">{{ t('personal.excluded') }}</Badge>
          <p class="text-muted-foreground text-sm">
            {{ t(`personal.bodyMeaning.${record.kind}`) }}
          </p>
        </li>
      </ul>
    </details>
  </article>
</template>
