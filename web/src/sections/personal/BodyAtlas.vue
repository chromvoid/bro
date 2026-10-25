<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import { Activity, CircleDot, Droplets, Layers } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NativeSelect } from '@/components/ui/native-select'
import { momentLabel } from '@/core/dates'
import { amountText, type Kind, type Profile } from './api'
import { bodyDelta, type BodyAnalysis } from './body'
import { bodyVisual, comparisonScans, interpolateVisual } from './bodyVisual'
import type { BodyRendererState, BodyView } from './bodyGpu'
import { useBodyPresentation } from './useBodyPresentation'
import BodyFigure from './BodyFigure.vue'

const displayUnits = useDisplayUnits()
const props = defineProps<{
  analysis: BodyAnalysis
  analyses?: BodyAnalysis[]
  profile?: Profile | null
  canWrite?: boolean
  today: string
  timezone: string
}>()
const emit = defineEmits<{ profile: [] }>()
const { t } = useI18n()
const presentation = useBodyPresentation()
const figure = ref<InstanceType<typeof BodyFigure>>()
const characterChoices = ['sculpture', 'spartan'] as const
const views: BodyView[] = ['front', 'side', 'back', 'three-quarter']
const viewDisabled = computed(
  () => figureLoading.value || !renderer.value || renderer.value === 'poster',
)
const layers = [
  {
    id: 'composition',
    icon: Layers,
    kinds: ['weight', 'body_fat_percent', 'skeletal_muscle_mass', 'body_water'],
  },
  {
    id: 'muscle',
    icon: Activity,
    kinds: ['skeletal_muscle_mass', 'fat_free_mass', 'body_protein', 'body_minerals'],
  },
  {
    id: 'fat',
    icon: CircleDot,
    kinds: ['body_fat_percent', 'body_fat_mass', 'visceral_fat_level', 'bmi'],
  },
  {
    id: 'water',
    icon: Droplets,
    kinds: ['body_water', 'fat_free_mass', 'body_protein', 'body_minerals'],
  },
] as const
const chosen = ref<(typeof layers)[number]['id']>('composition')
const layer = computed(() => layers.find((item) => item.id === chosen.value)!)
const motionState = ref<BodyRendererState | null>(null)
const reducedMotion = ref(false)
const renderer = ref<'webgpu' | 'webgl' | 'poster' | null>(null)
const figureLoading = ref(true)
const mapping = computed(() => bodyVisual(props.analysis, props.profile))
const candidates = computed(() =>
  comparisonScans(props.analyses ?? [], props.analysis, props.profile),
)
const compareEnabled = ref(false)
const compareId = ref('')
const progress = ref(100)
const previous = computed(
  () => candidates.value.find((item) => item.id === compareId.value) ?? candidates.value[0],
)
const comparing = computed(() => compareEnabled.value && !!previous.value)
const motionHint = computed(() => {
  if (figureLoading.value) return t('personal.atlas.motion.loading')
  if (comparing.value) return t('personal.atlas.motion.comparing')
  if (reducedMotion.value) return t('personal.atlas.motion.reduced')
  if (renderer.value === 'poster') return t('personal.atlas.motion.unavailable')
  if (!renderer.value) return t('personal.atlas.motion.loading')
  if (motionState.value?.paused) return t('personal.atlas.motion.paused')
  if (motionState.value?.motionSource === 'static' || motionState.value?.playing === false)
    return t('personal.atlas.motion.standing')
  return t('personal.atlas.motion.idle')
})
const previousMapping = computed(() =>
  previous.value ? bodyVisual(previous.value, props.profile) : null,
)
const calibrationHint = computed(() => {
  const endpoints =
    comparing.value && previousMapping.value
      ? [
          { label: t('personal.atlas.before'), mapping: previousMapping.value },
          { label: t('personal.atlas.selected'), mapping: mapping.value },
        ]
      : [{ label: '', mapping: mapping.value }]
  const boundaries = endpoints.flatMap((endpoint) => {
    const axes = (['fat', 'muscle'] as const).flatMap((axis) => {
      const direction = endpoint.mapping.calibrationBoundaries[axis]
      return direction
        ? [
            t('personal.atlas.calibration.axisBoundary', {
              axis: t(`personal.atlas.calibration.axis.${axis}`),
              direction: t(`personal.atlas.calibration.direction.${direction}`),
            }),
          ]
        : []
    })
    if (!axes.length) return []
    const details = axes.join('; ')
    return [endpoint.label ? `${endpoint.label}: ${details}` : details]
  })
  return boundaries.length
    ? t(
        comparing.value
          ? 'personal.atlas.calibration.comparisonHint'
          : 'personal.atlas.calibration.hint',
        { boundaries: boundaries.join(' · ') },
      )
    : null
})
const visual = computed(() =>
  comparing.value && previousMapping.value
    ? interpolateVisual(previousMapping.value.visual, mapping.value.visual, progress.value / 100)
    : mapping.value.visual,
)
const heldAxes = computed(() =>
  (['fat', 'muscle'] as const).filter(
    (axis) =>
      comparing.value &&
      (mapping.value.visual[axis] === null || previousMapping.value?.visual[axis] === null),
  ),
)
watch(
  () => props.analysis.id,
  () => {
    compareEnabled.value = false
    compareId.value = ''
    progress.value = 100
  },
)
watch(compareId, () => {
  progress.value = 100
})
const metric = (kind: Kind) => props.analysis.values[kind]
const hasConflict = (kind: Kind) =>
  props.analysis.records.filter((record) => record.kind === kind && !record.excluded).length > 1
const when = (at: string) => momentLabel(at, props.timezone, props.today)
const analysisNumber = (item: BodyAnalysis) =>
  (props.analyses?.length ?? 0) - (props.analyses?.findIndex((entry) => entry.id === item.id) ?? 0)
function toggleCompare() {
  compareEnabled.value = !compareEnabled.value
  progress.value = 100
}
</script>

<template>
  <section
    class="body-atlas"
    :data-layer="chosen"
    data-testid="body-atlas"
    :aria-label="t('personal.atlas.title')"
  >
    <header class="atlas-heading">
      <div>
        <p class="atlas-eyebrow">{{ t('personal.atlas.eyebrow') }}</p>
        <h2>{{ t('personal.atlas.title') }}</h2>
        <p class="atlas-source">
          {{ analysis.sourceName ?? t('personal.unnamedSource') }} <span aria-hidden="true">·</span>
          <time :datetime="analysis.measuredAt">{{ when(analysis.measuredAt) }}</time>
        </p>
      </div>
    </header>
    <div
      v-if="profile && (mapping.genderMissing || mapping.heightMissing)"
      class="atlas-profile"
      data-testid="atlas-profile-hint"
    >
      <div>
        <p v-if="mapping.genderMissing">{{ t('personal.atlas.genderMissing') }}</p>
        <p v-if="mapping.heightMissing">{{ t('personal.atlas.heightMissing') }}</p>
      </div>
      <button v-if="canWrite && profile" type="button" @click="emit('profile')">
        {{
          t(mapping.genderMissing ? 'personal.atlas.specifyGender' : 'personal.atlas.specifyHeight')
        }}
      </button>
    </div>
    <div class="atlas-presentation">
      <div class="atlas-characters" role="group" :aria-label="t('personal.atlas.character.title')">
        <button
          v-for="choice in characterChoices"
          :key="choice"
          type="button"
          :aria-pressed="presentation.characterId.value === choice"
          :disabled="
            !canWrite ||
            !presentation.writable.value ||
            (choice === 'spartan' && visual.template === 'female')
          "
          :aria-describedby="
            choice === 'spartan' && visual.template === 'female'
              ? 'atlas-spartan-unavailable'
              : undefined
          "
          :data-testid="`atlas-character-${choice}`"
          @click="presentation.select(choice)"
        >
          {{ t(`personal.atlas.character.${choice}`) }}
        </button>
      </div>
      <p
        v-if="visual.template === 'female'"
        id="atlas-spartan-unavailable"
        class="atlas-caption"
        data-testid="atlas-character-unavailable"
      >
        {{
          t(
            presentation.characterId.value === 'spartan'
              ? 'personal.atlas.character.savedIncompatible'
              : 'personal.atlas.character.maleOnly',
          )
        }}
      </p>
      <p
        v-if="presentation.isLoadingError.value || presentation.isRefetchError.value"
        class="atlas-caption"
        role="status"
      >
        {{
          t(
            presentation.data.value
              ? 'personal.atlas.character.refreshFailed'
              : 'personal.atlas.character.loadFailed',
          )
        }}
        <button type="button" @click="presentation.refetch()">{{ t('common.retry') }}</button>
      </p>
      <p v-if="presentation.saving.value" class="atlas-caption" role="status">
        {{ t('personal.atlas.character.saving') }}
      </p>
    </div>
    <div class="atlas-layers" role="group" :aria-label="t('personal.atlas.layers')">
      <button
        v-for="item in layers"
        :key="item.id"
        type="button"
        :aria-pressed="chosen === item.id"
        @click="chosen = item.id"
      >
        <component :is="item.icon" :size="16" aria-hidden="true" />{{
          t(`personal.atlas.layer.${item.id}`)
        }}
      </button>
    </div>
    <div class="atlas-stage">
      <div class="atlas-figure">
        <BodyFigure
          v-if="presentation.ready.value"
          ref="figure"
          :character-id="presentation.characterId.value"
          :visual="visual"
          :layer="chosen"
          :comparing="comparing"
          @loading="figureLoading = $event"
          @motion-state="motionState = $event"
          @reduced-motion="reducedMotion = $event"
          @renderer="renderer = $event"
        />
      </div>
      <dl class="atlas-metrics" aria-live="polite" aria-atomic="true">
        <div
          v-for="(kind, index) in layer.kinds"
          :key="kind"
          class="atlas-metric"
          :class="`atlas-metric-${index + 1}`"
          :data-testid="`atlas-${kind}`"
        >
          <dt>{{ t(`personal.metric.${kind}`) }}</dt>
          <dd class="atlas-value">
            {{ amountText(kind, metric(kind)?.data.value ?? null, displayUnits) ?? '—' }}
          </dd>
          <dd v-if="!metric(kind)" class="atlas-caption">
            {{ t(hasConflict(kind) ? 'personal.disagree' : 'personal.noValue') }}
          </dd>
          <template v-if="comparing && previous">
            <dd class="atlas-change">
              <span>{{
                t('personal.atlas.wasValue', {
                  value:
                    amountText(kind, previous.values[kind]?.data.value ?? null, displayUnits) ??
                    '—',
                })
              }}</span>
              <template v-if="metric(kind) && previous.values[kind]">{{
                bodyDelta(metric(kind)!, previous.values[kind]!, displayUnits)
              }}</template>
            </dd>
          </template>
          <dd v-else-if="metric(kind) && analysis.previous[kind]" class="atlas-change">
            {{ bodyDelta(metric(kind)!, analysis.previous[kind]!.record, displayUnits) }}
            <span>{{
              t('personal.comparedWith', { when: when(analysis.previous[kind]!.measuredAt) })
            }}</span>
          </dd>
          <dd v-if="metric(kind)?.corrected" class="atlas-corrected">
            {{ t('personal.origin.corrected') }}
          </dd>
        </div>
      </dl>
    </div>
    <div class="atlas-views" role="group" :aria-label="t('personal.atlas.view.title')">
      <button
        v-for="view in views"
        :key="view"
        type="button"
        :disabled="viewDisabled"
        :data-testid="`atlas-view-${view}`"
        @click="figure?.setView(view)"
      >
        {{ t(`personal.atlas.view.${view}`) }}
      </button>
    </div>
    <div class="atlas-movement">
      <p class="atlas-caption atlas-motion-status" role="status" data-testid="atlas-motion-status">
        {{ motionHint }}
      </p>
    </div>
    <section class="atlas-comparison" :aria-label="t('personal.atlas.compare')">
      <button
        type="button"
        class="atlas-compare-button"
        :disabled="!candidates.length"
        :aria-pressed="comparing"
        @click="toggleCompare"
      >
        {{ t(comparing ? 'personal.atlas.closeComparison' : 'personal.atlas.compare') }}
      </button>
      <p v-if="!candidates.length" class="atlas-caption">
        {{ t('personal.atlas.compareUnavailable') }}
      </p>
      <div v-if="comparing && previous" class="atlas-comparison-controls">
        <label for="body-compare-analysis">{{ t('personal.atlas.compareFrom') }}</label>
        <NativeSelect
          id="body-compare-analysis"
          :model-value="previous.id"
          @update:model-value="compareId = $event ?? ''"
        >
          <option v-for="item in candidates" :key="item.id" :value="item.id">
            {{ when(item.measuredAt) }} ·
            {{ t('personal.atlas.scanNumber', { number: analysisNumber(item) }) }}
          </option>
        </NativeSelect>
        <div class="atlas-endpoints" data-testid="atlas-comparison-endpoints">
          <span
            >{{ t('personal.atlas.before')
            }}<time :datetime="previous.measuredAt">{{ when(previous.measuredAt) }}</time></span
          >
          <span
            >{{ t('personal.atlas.selected')
            }}<time :datetime="analysis.measuredAt">{{ when(analysis.measuredAt) }}</time></span
          >
        </div>
        <label for="body-compare-progress" class="sr-only">{{ t('personal.atlas.scrub') }}</label>
        <input
          id="body-compare-progress"
          v-model.number="progress"
          type="range"
          min="0"
          max="100"
          step="1"
          :aria-valuetext="t('personal.atlas.scrubPosition', { percent: progress })"
        />
        <p v-if="heldAxes.length" class="atlas-caption">
          {{
            t('personal.atlas.heldAxes', {
              axes: heldAxes.map((axis) => t(`personal.atlas.axis.${axis}`)).join(', '),
            })
          }}
        </p>
      </div>
    </section>
    <footer class="atlas-footer">
      <p class="atlas-layer-note">{{ t(`personal.atlas.note.${chosen}`) }}</p>
      <p class="atlas-caption">{{ t('personal.atlas.schematic') }}</p>
      <p
        v-if="calibrationHint && !figureLoading && renderer && renderer !== 'poster'"
        class="atlas-caption"
        role="status"
        data-testid="atlas-calibration-hint"
      >
        {{ calibrationHint }}
      </p>
      <p v-if="renderer === 'poster' && !figureLoading" class="atlas-caption">
        {{ t('personal.atlas.staticView') }}
      </p>
    </footer>
  </section>
</template>

<style scoped>
.body-atlas {
  --atlas-accent: var(--primary);
  position: relative;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 1.75rem;
  background: var(--card);
  color: var(--foreground);
  isolation: isolate;
}
.atlas-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.6rem 1.7rem 1rem;
}
.atlas-eyebrow {
  color: var(--muted-foreground);
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.16em;
}
h2 {
  margin-top: 0.4rem;
  font-size: 1.8rem;
  font-weight: 550;
  letter-spacing: -0.04em;
}
.atlas-source {
  color: var(--muted-foreground);
  font-size: 0.75rem;
  margin-top: 0.4rem;
  line-height: 1.6;
}
.atlas-source span {
  padding: 0 0.3rem;
}
button:focus-visible,
input:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 4px;
}
.atlas-profile {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 0.7rem;
  margin: 0 1.7rem 1rem;
  padding: 0.85rem 1rem;
  background: var(--muted);
  border: 1px solid var(--border);
  border-radius: 0.9rem;
  font-size: 0.75rem;
  line-height: 1.6;
  color: var(--muted-foreground);
}
.atlas-profile button {
  color: var(--accent-foreground);
  text-decoration: underline;
  text-underline-offset: 0.2em;
  min-height: 2.5rem;
}
.atlas-presentation {
  margin: 0 1.7rem 1rem;
}
.atlas-characters,
.atlas-views {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}
.atlas-characters button,
.atlas-views button {
  min-height: 2.75rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--input);
  border-radius: 0.75rem;
  font-size: 0.75rem;
  color: var(--muted-foreground);
}
.atlas-characters button[aria-pressed='true'] {
  color: var(--accent-foreground);
  border-color: var(--primary);
  background: var(--accent);
}
.atlas-characters button:hover:not(:disabled),
.atlas-views button:hover:not(:disabled) {
  background: var(--accent);
}
.atlas-characters button:disabled,
.atlas-views button:disabled {
  opacity: 0.5;
  cursor: default;
}
.atlas-views {
  margin: 0 1.7rem 1rem;
}
.atlas-layers {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  padding: 0 1.7rem;
}
.atlas-layers button {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  min-height: 2.75rem;
  padding: 0.55rem 0.8rem;
  border: 1px solid transparent;
  border-radius: 2rem;
  font-size: 0.8rem;
  color: var(--muted-foreground);
}
.atlas-layers button[aria-pressed='true'] {
  color: var(--accent-foreground);
  border-color: var(--primary);
  background: var(--accent);
}
.atlas-layers button:hover {
  color: var(--foreground);
}
.atlas-stage {
  position: relative;
  min-height: 29rem;
  margin: 0.75rem 1.7rem 0;
}
.atlas-figure {
  position: absolute;
  inset: 0 15%;
  z-index: 0;
}
.atlas-figure :deep(.body-figure) {
  height: 100%;
}
.atlas-metrics {
  position: relative;
  z-index: 1;
  min-height: 29rem;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(10rem, 1.5fr) minmax(0, 1fr);
  grid-template-rows: 1fr 1fr;
  align-items: center;
  column-gap: 0.7rem;
  row-gap: 1rem;
  padding: 2rem 0;
  pointer-events: none;
}
.atlas-metric {
  padding: 1rem 0;
  position: relative;
}
.atlas-metric::before {
  content: '';
  display: block;
  width: 1.5rem;
  height: 1px;
  margin-bottom: 0.65rem;
  background: var(--atlas-accent);
  opacity: 0.65;
}
.atlas-metric-1 {
  grid-column: 1;
  grid-row: 1;
}
.atlas-metric-2 {
  grid-column: 1;
  grid-row: 2;
}
.atlas-metric-3 {
  grid-column: 3;
  grid-row: 1;
}
.atlas-metric-4 {
  grid-column: 3;
  grid-row: 2;
}
dt {
  font-size: 0.75rem;
  line-height: 1.45;
  color: var(--muted-foreground);
}
.atlas-value {
  margin-top: 0.4rem;
  font-size: clamp(1.5rem, 3vw, 2rem);
  line-height: 1.2;
  font-weight: 550;
  letter-spacing: -0.045em;
  font-variant-numeric: tabular-nums;
}
.atlas-change {
  margin-top: 0.5rem;
  color: var(--atlas-accent);
  font-size: 0.8rem;
  font-variant-numeric: tabular-nums;
}
.atlas-change span {
  display: block;
  color: var(--muted-foreground);
  font-size: 0.75rem;
  line-height: 1.5;
  margin-top: 0.15rem;
}
.atlas-corrected {
  font-size: 0.75rem;
  color: var(--atlas-accent);
  margin-top: 0.45rem;
}
.atlas-movement {
  margin: 0 1.7rem 1.2rem;
}
.atlas-motion-status {
  min-height: 1.6em;
}
.atlas-comparison {
  margin: 0 1.7rem 1.2rem;
  border-top: 1px solid var(--border);
  padding-top: 1rem;
}
.atlas-compare-button {
  min-height: 2.75rem;
  border: 1px solid var(--input);
  border-radius: 0.8rem;
  padding: 0.55rem 0.85rem;
  font-size: 0.8rem;
  color: var(--primary);
}
.atlas-compare-button:disabled {
  color: var(--muted-foreground);
  border-color: var(--input);
  cursor: default;
}
.atlas-comparison-controls {
  display: grid;
  gap: 0.65rem;
  margin-top: 1rem;
  max-width: 36rem;
  font-size: 0.8rem;
}
.atlas-comparison-controls :deep(select) {
  border-color: var(--input);
  color: var(--foreground);
  background: var(--background);
}
.atlas-endpoints {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  margin-top: 0.5rem;
  color: var(--muted-foreground);
}
.atlas-endpoints span:last-child {
  text-align: right;
}
.atlas-endpoints time {
  display: block;
  color: var(--foreground);
  margin-top: 0.35rem;
  font-size: 0.75rem;
}
input[type='range'] {
  width: 100%;
  min-height: 2.75rem;
  accent-color: var(--primary);
  cursor: pointer;
}
.atlas-footer {
  border-top: 1px solid var(--border);
  padding: 1.05rem 1.7rem 1.35rem;
}
.atlas-layer-note {
  color: var(--foreground);
  font-size: 0.8rem;
  line-height: 1.6;
  max-width: 48rem;
}
.atlas-caption {
  color: var(--muted-foreground);
  font-size: 0.75rem;
  line-height: 1.6;
  margin-top: 0.5rem;
}
@media (max-width: 600px) {
  .atlas-heading {
    padding: 1.4rem 1.2rem 1rem;
  }
  h2 {
    font-size: 1.55rem;
  }
  .atlas-source {
    max-width: 16rem;
  }
  .atlas-profile {
    margin: 0 1.2rem 1rem;
  }
  .atlas-layers {
    padding: 0 1rem;
    gap: 0;
  }
  .atlas-layers button {
    padding: 0.5rem 0.65rem;
    font-size: 0.75rem;
  }
  .atlas-stage {
    margin: 0.5rem 1.2rem 0;
    min-height: auto;
  }
  .atlas-figure {
    position: relative;
    height: 23rem;
    inset: auto;
    margin-bottom: 0.3rem;
  }
  .atlas-metrics {
    position: relative;
    z-index: 1;
    min-height: auto;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: auto auto;
    gap: 1rem 1.25rem;
    padding: 0 0 1.4rem;
  }
  .atlas-metric {
    padding: 0.75rem 0 0;
    border-top: 1px solid var(--border);
  }
  .atlas-metric::before {
    display: none;
  }
  .atlas-metric-1 {
    grid-column: 1;
    grid-row: 1;
  }
  .atlas-metric-2 {
    grid-column: 2;
    grid-row: 1;
  }
  .atlas-metric-3 {
    grid-column: 1;
    grid-row: 2;
  }
  .atlas-metric-4 {
    grid-column: 2;
    grid-row: 2;
  }
  .atlas-value {
    font-size: 1.75rem;
  }
  .atlas-presentation,
  .atlas-views,
  .atlas-movement,
  .atlas-comparison {
    margin: 0 1.2rem 1.2rem;
  }
  .atlas-footer {
    padding: 1rem 1.2rem 1.3rem;
  }
}
@media print {
  .atlas-presentation,
  .atlas-views,
  .atlas-movement,
  .atlas-comparison {
    display: none;
  }
}
</style>
