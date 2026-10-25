<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import historyMessages from '@/locales/personal-history-ru.json'
import { momentLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import RecordQuality from './RecordQuality.vue'
import { derivedInputLabel, derivedSyntheticReason } from './derivedMetadata'
import {
  componentValueLabel,
  referenceLabel,
  type DerivedDefinition,
  type DerivedScore,
} from './derivedScores'
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
defineProps<{
  score: DerivedScore
  definition?: DerivedDefinition | null
  timezone: string
  today: string
}>()
</script>

<template>
  <details class="text-sm" data-testid="derived-score-details">
    <summary class="text-primary min-h-11 cursor-pointer py-2">
      {{ t('personalHistory.derived.title') }}
    </summary>
    <dl class="space-y-2">
      <div>
        <dt>{{ t('personalHistory.derived.definition') }}</dt>
        <dd>{{ score.definition_id }} · {{ score.formula_version }} · {{ score.qualification }}</dd>
      </div>
      <div>
        <dt>{{ t('personalHistory.derived.requiredInputs') }}</dt>
        <dd>
          {{
            definition?.required_inputs.map(derivedInputLabel).join(', ') ??
            t('personalHistory.derived.openSavedDefinition')
          }}
        </dd>
      </div>
      <div>
        <dt>{{ t('personalHistory.derived.asOf') }}</dt>
        <dd>
          {{ momentLabel(score.context.as_of, timezone, today) }} · {{ score.context.time_zone }} ·
          {{ score.context.evaluation }}
        </dd>
      </div>
      <div>
        <dt>{{ t('personalHistory.record.source') }}</dt>
        <dd class="break-all">
          {{ score.context.source_id ?? t('personalHistory.derived.sourceUnspecified') }}
        </dd>
      </div>
      <div>
        <dt>{{ t('personalHistory.derived.coverage') }}</dt>
        <dd>
          {{ score.coverage.observed }} / {{ score.coverage.expected
          }}<template v-if="score.coverage.ratio !== null">
            · {{ formatAmount(score.coverage.ratio * 100, 1) }} %</template
          >
        </dd>
      </div>
      <div>
        <dt>{{ t('personalHistory.derived.availability') }}</dt>
        <dd>
          {{ score.confidence.level }} · {{ score.confidence.available_inputs }} /
          {{ score.confidence.required_inputs }}
        </dd>
      </div>
      <div v-if="score.baseline" data-testid="derived-baseline">
        <dt>{{ t('personalHistory.derived.baseline') }}</dt>
        <dd>
          <p>
            {{
              t('personalHistory.derived.baselineSource', {
                as_of: score.baseline.as_of,
                value2: score.baseline.source_id ?? t('personalHistory.derived.ownerContext'),
              })
            }}
          </p>
          <ul class="mt-2 space-y-2">
            <li
              v-for="component in score.baseline.components"
              :key="JSON.stringify(component)"
              class="rounded-xl border p-3"
            >
              <p class="font-medium">{{ component.id }}</p>
              <p
                v-if="
                  'window_start' in component &&
                  typeof component.window_start === 'string' &&
                  'window_end' in component &&
                  typeof component.window_end === 'string'
                "
              >
                {{ component.window_start }} — {{ component.window_end }}
              </p>
              <p v-if="'points' in component && typeof component.points === 'number'">
                {{ t('personalHistory.derived.points', { points: component.points }) }}
              </p>
              <p v-if="'estimator' in component && typeof component.estimator === 'string'">
                {{ t('personalHistory.derived.estimator', { estimator: component.estimator }) }}
              </p>
              <details class="text-muted-foreground text-xs">
                <summary class="min-h-11 cursor-pointer py-2">
                  {{ t('personalHistory.derived.baselineEvidence') }}
                </summary>
                <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
                  JSON.stringify(component, null, 2)
                }}</pre>
              </details>
            </li>
          </ul>
        </dd>
      </div>
    </dl>
    <p class="text-muted-foreground mt-2 text-xs">
      {{ t('personalHistory.derived.coverageExplanation') }}
    </p>
    <ul class="mt-2 space-y-1">
      <li
        v-for="(component, index) in score.components"
        :key="`${component.id}:${index}`"
        data-testid="derived-component"
      >
        {{ derivedInputLabel(component.id) }}:
        {{
          component.value === null
            ? t('personalHistory.derived.noData')
            : typeof component.value === 'number'
              ? formatAmount(component.value, 2)
              : componentValueLabel(component.value)
        }}
        {{ component.unit
        }}<span v-if="component.synthetic" class="text-warning">
          {{
            t('personalHistory.derived.synthetic', {
              value1: derivedSyntheticReason(component.reason),
            })
          }}</span
        >
        <RecordQuality
          v-if="!component.synthetic && component.quality.length"
          :flags="component.quality"
        />
        <details class="text-muted-foreground text-xs" data-testid="derived-component-codes">
          <summary class="min-h-11 cursor-pointer py-2">
            {{ t('personalHistory.derived.componentCode') }}
          </summary>
          <p>
            <code>{{ component.id }}</code>
          </p>
          <p v-if="component.synthetic">
            {{ t('personalHistory.derived.reason') }} <code>{{ component.reason }}</code>
          </p>
        </details>
        <details v-if="Array.isArray(component.value)" class="text-muted-foreground text-xs">
          <summary class="min-h-11 cursor-pointer py-2">
            {{ t('personalHistory.derived.componentSeries') }}
          </summary>
          <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
            JSON.stringify(component.value, null, 2)
          }}</pre>
        </details>
      </li>
    </ul>
    <RecordQuality v-if="score.quality.length" class="mt-2" :flags="score.quality" />
    <details class="text-muted-foreground mt-2">
      <summary class="min-h-11 cursor-pointer py-2">
        {{ t('personalHistory.derived.inputVersions') }}
      </summary>
      <ul class="mt-2 space-y-1 break-all">
        <li v-for="input in score.input_revisions" :key="JSON.stringify(input)">
          {{ referenceLabel(input) }} · {{ input.id }} · v{{ input.revision }}
        </li>
      </ul>
      <pre class="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(score.input_revisions, null, 2)
      }}</pre>
    </details>
    <details
      v-if="score.input_windows.length"
      class="text-muted-foreground mt-2"
      data-testid="derived-input-windows"
    >
      <summary class="min-h-11 cursor-pointer py-2">
        {{ t('personalHistory.derived.inputWindows') }}
      </summary>
      <ul class="mt-2 space-y-1 break-all">
        <li v-for="window in score.input_windows" :key="JSON.stringify(window)">
          {{ window.metric }} ·
          {{ t('personalHistory.derived.points', { points: window.count }) }} ·
          {{ window.window_start }} — {{ window.window_end }}
        </li>
      </ul>
      <pre class="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(score.input_windows, null, 2)
      }}</pre>
    </details>
    <details class="text-muted-foreground mt-2">
      <summary class="min-h-11 cursor-pointer py-2">
        {{ t('personalHistory.derived.definitionEvidence') }}
      </summary>
      <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(
          {
            definition: definition ?? null,
            components: score.components,
            fingerprint: score.fingerprint,
          },
          null,
          2,
        )
      }}</pre>
    </details>
  </details>
</template>
