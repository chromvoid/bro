<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQueryRaw } from 'vue-router'
import LineChart from '@/components/chart/LineChart.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { formatAmount } from '@/core/numbers'
import type { ExerciseDefinition, LoadBasis } from './api'
import { atDay, progressPoints, shortDate } from './charts'
import { rememberStrengthDisclosure, strengthDisclosureOpen } from './strengthDisclosureState'
import { strengthDays, type ProgressSeries, type ProgressSet } from './strengthSummary'
import { useExerciseProgress, type ActivityRange } from './useActivity'

const props = withDefaults(
  defineProps<{
    catalog: ExerciseDefinition[]
    range: ActivityRange
    canWrite?: boolean
    returnQuery?: LocationQueryRaw
  }>(),
  { canWrite: false },
)
const selected = defineModel<string>('selected', { required: true })
const emit = defineEmits<{
  'add-strength': []
  catalog: []
  activate: [elementId: string]
  ready: [ready: boolean]
}>()
const { t } = useI18n()
const range = computed(() => props.range)
const progress = useExerciseProgress(selected, range)
const choices = computed(() => {
  const archived = progress.data.value?.exercise
  return archived && !props.catalog.some((exercise) => exercise.id === archived.id)
    ? [...props.catalog, archived]
    : props.catalog
})
const seriesResults = computed(
  () => progress.data.value?.series.map((series) => ({ series, ...strengthDays(series) })) ?? [],
)
const working = computed(
  () =>
    progress.data.value?.daily.map((day) => ({ x: atDay(day.date), y: day.working_sets })) ?? [],
)
function setLabel(point: ProgressSet, basis: LoadBasis) {
  const repetitions = formatAmount(point.repetitions, 0)
  if (basis === 'bodyweight' && point.load_kg === null)
    return t('activityDetail.bodyweightSet', { repetitions })
  const load = `${formatAmount(point.load_kg, 2)} ${t('activity.loadUnits.kg')}`
  return t(basis === 'assistance' ? 'activityDetail.assistanceSet' : 'activityDetail.setPair', {
    load,
    repetitions,
  })
}
const returnQuery = computed(() => ({
  ...props.returnQuery,
  tab: 'progress',
  exercise: selected.value,
  metric: 'strength',
}))
const disclosureContext = computed(() =>
  JSON.stringify(Object.entries(returnQuery.value).sort(([a], [b]) => a.localeCompare(b))),
)
function seriesDisclosure(series: ProgressSeries) {
  return JSON.stringify([
    series.snapshot.equipment,
    series.snapshot.variant,
    series.snapshot.load_basis,
    series.implement_count,
  ])
}
function rememberDisclosure(id: string, event: Event) {
  const details = event.currentTarget as HTMLDetailsElement
  rememberStrengthDisclosure(disclosureContext.value, id, details.open)
}
const setElementId = (id: string) => `activity-progress-set-${id}`
watch(
  () => !selected.value || !progress.isPending.value || progress.isLoadingError.value,
  (ready) => emit('ready', ready),
  { immediate: true },
)
</script>

<template>
  <div class="space-y-4" data-testid="activity-progress">
    <div v-if="choices.length || selected" class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1 space-y-2 sm:max-w-md">
        <Label for="progress-exercise">{{ t('activity.chooseExercise') }}</Label>
        <NativeSelect id="progress-exercise" v-model="selected">
          <option value="">{{ t('activity.chooseExercise') }}</option>
          <option v-for="exercise in choices" :key="exercise.id" :value="exercise.id">
            {{ exercise.name }}{{ exercise.variant ? ` · ${exercise.variant}` : '' }}
          </option>
        </NativeSelect>
      </div>
      <Button variant="outline" class="min-h-11" @click="emit('catalog')">
        {{ t('activityDetail.catalog') }}
      </Button>
    </div>
    <RefreshFailed :query="progress" />
    <div v-if="!selected" class="space-y-3 py-2">
      <template v-if="!choices.length">
        <p class="font-medium">{{ t('activityDetail.noExercises') }}</p>
        <p class="text-muted-foreground max-w-xl text-sm">
          {{ t('activityDetail.noExercisesHint') }}
        </p>
        <div class="flex flex-wrap gap-2">
          <Button v-if="canWrite" class="min-h-11" @click="emit('add-strength')">
            {{ t('activityDetail.addStrength') }}
          </Button>
          <Button variant="outline" class="min-h-11" @click="emit('catalog')">
            {{ t('activityDetail.catalog') }}
          </Button>
        </div>
      </template>
      <p v-else class="text-muted-foreground text-sm">
        {{ t('activityDetail.chooseExerciseHint') }}
      </p>
    </div>
    <LoadingState v-else-if="progress.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="progress.isLoadingError.value"
      :error="progress.error.value"
      @retry="progress.refetch()"
    />
    <template v-else-if="progress.data.value">
      <div v-if="!seriesResults.length" class="space-y-3 py-2">
        <p class="font-medium">{{ t('activity.noProgress') }}</p>
        <p class="text-muted-foreground text-sm">{{ t('activity.noProgressHint') }}</p>
        <Button v-if="canWrite" class="min-h-11" @click="emit('add-strength')">
          {{ t('activityDetail.addStrength') }}
        </Button>
      </div>
      <section
        v-for="({ series, latest, previous }, index) in seriesResults"
        :key="index"
        class="bg-card space-y-4 rounded-2xl border p-4"
        data-testid="strength-series"
      >
        <div>
          <h2 class="font-semibold">{{ series.snapshot.name }}</h2>
          <p class="text-muted-foreground text-sm">
            {{
              [
                series.snapshot.equipment,
                series.snapshot.variant,
                t(`activity.loadBases.${series.snapshot.load_basis}`),
              ]
                .filter(Boolean)
                .join(' · ')
            }}<span v-if="series.snapshot.load_basis === 'per_implement'">
              · {{ t('activity.implements', { count: series.implement_count }) }}</span
            >
          </p>
          <p
            v-if="series.snapshot.load_basis === 'assistance'"
            class="text-muted-foreground mt-1 text-sm"
          >
            {{ t('activity.assistanceHint') }}
          </p>
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <div v-if="latest" data-testid="strength-latest">
            <h3 class="text-sm font-medium">{{ t('activityDetail.latestSets') }}</h3>
            <p class="text-muted-foreground mt-1 text-sm">{{ shortDate(atDay(latest.date)) }}</p>
            <ul
              class="mt-2 flex flex-wrap gap-2 text-sm tabular-nums"
              :aria-label="t('activityDetail.setsForDay', { date: shortDate(atDay(latest.date)) })"
            >
              <li
                v-for="point in latest.points.slice(0, 3)"
                :key="point.set_id"
                class="bg-muted rounded-lg px-3 py-2 font-medium"
              >
                {{ setLabel(point, series.snapshot.load_basis) }}
              </li>
            </ul>
            <p v-if="latest.points.length > 3" class="text-muted-foreground mt-2 text-xs">
              {{ t('activityDetail.moreSets', { count: latest.points.length - 3 }) }}
            </p>
          </div>
          <div v-if="previous" data-testid="strength-previous">
            <h3 class="text-sm font-medium">{{ t('activityDetail.previousDay') }}</h3>
            <p class="text-muted-foreground mt-1 text-sm">
              {{ shortDate(atDay(previous.date)) }}
            </p>
            <ul
              class="text-muted-foreground mt-2 flex flex-wrap gap-2 text-sm tabular-nums"
              :aria-label="
                t('activityDetail.setsForDay', { date: shortDate(atDay(previous.date)) })
              "
            >
              <li
                v-for="point in previous.points.slice(0, 3)"
                :key="point.set_id"
                class="rounded-lg border px-3 py-2"
              >
                {{ setLabel(point, series.snapshot.load_basis) }}
              </li>
            </ul>
            <p v-if="previous.points.length > 3" class="text-muted-foreground mt-2 text-xs">
              {{ t('activityDetail.moreSets', { count: previous.points.length - 3 }) }}
            </p>
          </div>
          <p v-else class="text-muted-foreground text-sm">
            {{ t('activityDetail.noPreviousDay') }}
          </p>
        </div>
        <details
          :key="`${disclosureContext}:${seriesDisclosure(series)}`"
          class="border-t pt-2"
          :open="strengthDisclosureOpen(disclosureContext, seriesDisclosure(series))"
          @toggle="rememberDisclosure(seriesDisclosure(series), $event)"
        >
          <summary class="min-h-11 cursor-pointer py-3 text-sm font-medium">
            {{ t('activityDetail.chartsAndSets') }}
          </summary>
          <div class="space-y-4 pt-2">
            <p class="text-muted-foreground text-sm">
              {{ t('activityDetail.comparisonBasis') }}
            </p>
            <p class="text-muted-foreground text-sm">
              {{
                t(
                  series.snapshot.load_basis === 'assistance'
                    ? 'activityDetail.assistanceMaxima'
                    : 'activityDetail.separateMaxima',
                )
              }}
            </p>
            <div class="grid gap-4 md:grid-cols-2">
              <div>
                <h3 class="text-sm font-medium">
                  {{
                    t(
                      series.snapshot.load_basis === 'assistance'
                        ? 'activity.lowestAssistance'
                        : 'activity.heaviestLoad',
                    )
                  }}
                </h3>
                <LineChart
                  v-if="progressPoints(series, 'load_kg').length"
                  :points="progressPoints(series, 'load_kg')"
                  :label="t('activity.loadGraph')"
                  :format-x="shortDate"
                  :format-y="(value) => `${formatAmount(value, 2)} ${t('activity.loadUnits.kg')}`"
                  :gap-days="1"
                />
                <p v-else class="text-muted-foreground py-4 text-sm">
                  {{ t('activity.noLoadData') }}
                </p>
              </div>
              <div>
                <h3 class="text-sm font-medium">{{ t('activity.mostRepetitions') }}</h3>
                <LineChart
                  v-if="progressPoints(series, 'repetitions').length"
                  :points="progressPoints(series, 'repetitions')"
                  :label="t('activity.repetitionsGraph')"
                  :format-x="shortDate"
                  :format-y="(value) => formatAmount(value, 0)"
                  :format-tick="(value) => (Number.isInteger(value) ? formatAmount(value, 0) : '')"
                  :gap-days="1"
                />
                <p v-else class="text-muted-foreground py-4 text-sm">
                  {{ t('activity.noRepetitionData') }}
                </p>
              </div>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-left text-sm tabular-nums">
                <caption class="sr-only">
                  {{
                    t('activity.setHistory')
                  }}
                </caption>
                <thead class="text-muted-foreground">
                  <tr>
                    <th class="py-2">{{ t('activity.sessionDate') }}</th>
                    <th>{{ t('activity.repetitions') }}</th>
                    <th>{{ t('activity.loadNormalized') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="point in series.points" :key="point.set_id" class="border-t">
                    <td>
                      <RouterLink
                        :id="setElementId(point.set_id)"
                        :to="{
                          name: 'activity-session',
                          params: { session: point.session_id },
                          query: returnQuery,
                        }"
                        class="text-primary inline-flex min-h-11 min-w-11 items-center py-2 underline underline-offset-4"
                        @click="emit('activate', setElementId(point.set_id))"
                        >{{ shortDate(atDay(point.date)) }}</RouterLink
                      >
                    </td>
                    <td>{{ formatAmount(point.repetitions, 0) }}</td>
                    <td>{{ formatAmount(point.load_kg, 2) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </details>
      </section>
      <details
        v-if="working.length"
        :key="`${disclosureContext}:working-sets`"
        class="rounded-2xl border p-4"
        :open="strengthDisclosureOpen(disclosureContext, 'working-sets')"
        @toggle="rememberDisclosure('working-sets', $event)"
      >
        <summary class="min-h-11 cursor-pointer py-3 text-sm font-medium">
          {{ t('activityDetail.workingSetsDetails') }}
        </summary>
        <div class="pt-4">
          <LineChart
            :points="working"
            :label="t('activity.workingSets')"
            :format-x="shortDate"
            :format-y="(value) => formatAmount(value, 0)"
            :format-tick="(value) => (Number.isInteger(value) ? formatAmount(value, 0) : '')"
            :gap-days="1"
          />
        </div>
      </details>
    </template>
  </div>
</template>
