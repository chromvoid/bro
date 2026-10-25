<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useDisplayUnits, distanceText } from '@/sections/personal/displayUnits'
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { api } from '@/core/client'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/button'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import WorkoutTrackMap from './WorkoutTrackMap.vue'
import {
  WorkoutTrackPage,
  WorkoutTrackSummaryVersionsPage,
  trackQualityText,
  type WorkoutTrackSummarySnapshot,
} from './workoutTrack'

const { t } = useI18n()
const displayUnits = useDisplayUnits()
const props = defineProps<{ workoutId: string; sourceId: string; timezone: string }>()
const session = useSession()
const user = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!user.value && session.can('personal.read'))
const queryClient = useQueryClient()
const archiveOpen = ref(false)
let archiveGeneration = 0
const archiveKey = computed(() => [
  'personal',
  'workout-track-summary-versions',
  user.value,
  props.sourceId,
  props.workoutId,
])
watch(
  [user, () => props.sourceId, () => props.workoutId, permitted],
  (_, [previousUser, previousSource, previousWorkout]) => {
    archiveGeneration++
    archiveOpen.value = false
    queryClient.removeQueries({
      queryKey: [
        'personal',
        'workout-track-summary-versions',
        previousUser,
        previousSource,
        previousWorkout,
      ],
      exact: true,
    })
  },
  { flush: 'sync' },
)
const query = useInfiniteQuery({
  queryKey: computed(() => [
    'personal',
    'workout-track',
    user.value,
    props.sourceId,
    props.workoutId,
  ]),
  enabled: permitted,
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const owner = user.value
    const source = props.sourceId
    const workout = props.workoutId
    const params = new URLSearchParams({
      source_id: source,
      limit: '100',
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = WorkoutTrackPage.parse(
      await api.get(
        `/v1/personal/rings/workouts/${encodeURIComponent(workout)}/track?${params}`,
        signal,
      ),
    )
    if (
      !permitted.value ||
      owner !== user.value ||
      source !== props.sourceId ||
      workout !== props.workoutId ||
      page.source_id !== source ||
      page.workout_id !== workout
    )
      throw new DOMException('Workout source changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const pages = computed(() => (permitted.value ? (query.data.value?.pages ?? []) : []))
const summary = computed(() => pages.value[0]?.summary)
const coherent = computed(() =>
  pages.value.every(
    (page) =>
      page.fingerprint === pages.value[0]?.fingerprint &&
      page.summary?.fingerprint === summary.value?.fingerprint,
  ),
)
const segments = computed(() =>
  coherent.value ? pages.value.flatMap((page) => page.segments) : [],
)
const contexts = computed(() =>
  (['running', 'workout', 'recovery'] as const).flatMap((context) => {
    const value = summary.value?.contexts[context]
    return value ? [{ context, value }] : []
  }),
)
const archiveQuery = useInfiniteQuery({
  queryKey: archiveKey,
  enabled: computed(() => permitted.value && archiveOpen.value),
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const owner = user.value
    const source = props.sourceId
    const workout = props.workoutId
    const generation = archiveGeneration
    if (!permitted.value) throw new DOMException('Workout access changed', 'AbortError')
    const params = new URLSearchParams({
      source_id: source,
      limit: '20',
      ...(pageParam ? { cursor: pageParam } : {}),
    })
    const page = WorkoutTrackSummaryVersionsPage.parse(
      await api.get(
        `/v1/personal/rings/workouts/${encodeURIComponent(workout)}/track-summary-versions?${params}`,
        signal,
      ),
    )
    if (
      signal.aborted ||
      generation !== archiveGeneration ||
      !permitted.value ||
      owner !== user.value ||
      source !== props.sourceId ||
      workout !== props.workoutId ||
      page.source_id !== source ||
      page.workout_id !== workout
    )
      throw new DOMException('Workout source changed', 'AbortError')
    return page
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const archivePages = computed(() =>
  permitted.value && archiveOpen.value ? (archiveQuery.data.value?.pages ?? []) : [],
)
const archiveCoherent = computed(() => {
  const first = archivePages.value[0]
  if (!first) return true
  if (
    archivePages.value.some(
      (page) =>
        page.source_id !== props.sourceId ||
        page.workout_id !== props.workoutId ||
        page.fingerprint !== first.fingerprint ||
        page.latest_revision !== first.latest_revision,
    )
  )
    return false
  const versions = archivePages.value.flatMap((page) => page.items)
  if (first.latest_revision === null)
    return versions.length === 0 && archivePages.value.every((page) => page.next_cursor === null)
  return (
    versions[0]?.revision === first.latest_revision &&
    versions.every(
      (item, index) =>
        item.is_latest === (item.revision === first.latest_revision) &&
        (index === 0 || item.revision < versions[index - 1]!.revision),
    )
  )
})
const archiveFailed = computed(
  () =>
    !archiveCoherent.value ||
    archiveQuery.isFetchNextPageError.value ||
    archiveQuery.isRefetchError.value,
)
const archiveVersions = computed(() =>
  archiveFailed.value ? [] : archivePages.value.flatMap((page) => page.items),
)
function toggleArchive(event: Event) {
  archiveOpen.value = permitted.value && (event.currentTarget as HTMLDetailsElement).open
}
function reloadArchive() {
  archiveGeneration++
  void queryClient.resetQueries({ queryKey: archiveKey.value, exact: true })
}
function versionContexts(snapshot: WorkoutTrackSummarySnapshot) {
  return (['running', 'workout', 'recovery'] as const).flatMap((context) => {
    const value = snapshot.contexts[context]
    return value ? [{ context, value }] : []
  })
}
function contextName(context: 'running' | 'workout' | 'recovery') {
  return t(`workoutTrack.contexts.${context}`)
}
</script>

<template>
  <section v-if="permitted" class="space-y-3" data-testid="workout-track-history">
    <RefreshFailed :query="query" />
    <LoadingState v-if="query.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="query.isLoadingError.value"
      :error="query.error.value"
      @retry="query.refetch()"
    />
    <WorkoutTrackMap
      v-if="segments.some((segment) => segment.points.length)"
      :segments="segments"
    />
    <p
      v-else-if="coherent && !query.isPending.value && !query.isLoadingError.value"
      class="text-muted-foreground text-sm"
    >
      {{ t('workoutTrack.empty') }}
    </p>
    <div v-if="summary && coherent" class="grid gap-3 sm:grid-cols-2">
      <div
        v-for="{ context, value } in contexts"
        :key="context"
        class="bg-muted space-y-1 rounded-xl p-3 text-sm"
      >
        <h3 class="font-medium">
          {{ contextName(context) }}
        </h3>
        <p>
          {{
            value.distance_meters === null
              ? t('workoutTrack.unknownDistance')
              : distanceText(value.distance_meters!, displayUnits)
          }}
        </p>
        <p>
          {{
            t('workoutTrack.coordinates', {
              usable: value.usable_points,
              reported: value.reported_points,
            })
          }}
        </p>
        <p>
          {{
            t('workoutTrack.coverage', {
              seconds: formatAmount(value.coverage_seconds, 0),
              gaps: value.gap_count,
            })
          }}
        </p>
        <p v-if="value.paused_points !== undefined">
          {{ t('workoutTrack.pausedExcluded', { count: value.paused_points }) }}
        </p>
      </div>
    </div>
    <p v-if="query.hasNextPage.value" class="text-muted-foreground text-xs">
      {{ t('workoutTrack.loadedSegments') }}
    </p>
    <p
      v-if="!coherent || query.isFetchNextPageError.value"
      class="text-warning text-sm"
      role="alert"
    >
      {{ t('workoutTrack.incomplete') }}
      <Button size="sm" variant="ghost" @click="query.refetch()">{{
        t('workoutTrack.refresh')
      }}</Button>
    </p>
    <Button
      v-if="query.hasNextPage.value && coherent"
      size="sm"
      variant="outline"
      :disabled="query.isFetchingNextPage.value"
      @click="query.fetchNextPage()"
      >{{ t('workoutTrack.loadMore') }}</Button
    >
    <details
      v-if="summary && coherent"
      class="text-muted-foreground text-xs"
      data-testid="workout-track-current-details"
    >
      <summary class="min-h-11 cursor-pointer py-2">{{ t('workoutTrack.currentDetails') }}</summary>
      <p>{{ summary.algorithm_version }} · {{ timezone }}</p>
      <pre class="whitespace-pre-wrap break-all">{{ JSON.stringify(summary, null, 2) }}</pre>
      <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(
          pages.flatMap((page) => page.chunks),
          null,
          2,
        )
      }}</pre>
    </details>
    <details
      class="space-y-3"
      data-testid="workout-track-summary-archive"
      :open="archiveOpen"
      @toggle="toggleArchive"
    >
      <summary class="min-h-11 cursor-pointer py-2 font-medium">
        {{ t('workoutTrack.versions') }}
      </summary>
      <div v-if="archiveOpen" class="space-y-3">
        <LoadingState v-if="archiveQuery.isPending.value" :rows="2" />
        <ErrorState
          v-else-if="archiveQuery.isLoadingError.value"
          :error="archiveQuery.error.value"
          @retry="reloadArchive"
        />
        <p v-else-if="archiveFailed" class="text-warning text-sm" role="alert">
          {{ t('workoutTrack.versionsIncomplete') }}
          <Button size="sm" variant="ghost" @click="reloadArchive">{{
            t('workoutTrack.refreshVersions')
          }}</Button>
        </p>
        <template v-else>
          <p v-if="!archiveVersions.length" class="text-muted-foreground text-sm">
            {{ t('workoutTrack.noVersions') }}
          </p>
          <article
            v-for="item in archiveVersions"
            :key="item.revision"
            class="border-border space-y-3 rounded-xl border p-3 text-sm"
            data-testid="workout-track-summary-version"
            :data-revision="item.revision"
          >
            <h3 class="font-medium">
              {{
                t('workoutTrack.version', {
                  revision: item.revision,
                  status: t(item.is_latest ? 'workoutTrack.latest' : 'workoutTrack.previous'),
                })
              }}
            </h3>
            <p>
              {{
                t('workoutTrack.gpsDistance', {
                  distance:
                    item.snapshot.value_meters === null
                      ? t('workoutTrack.unknown')
                      : distanceText(item.snapshot.value_meters, displayUnits),
                })
              }}
            </p>
            <p>{{ t('workoutTrack.formula', { version: item.snapshot.algorithm_version }) }}</p>
            <div class="grid gap-3 sm:grid-cols-2">
              <div
                v-for="{ context, value } in versionContexts(item.snapshot)"
                :key="context"
                class="bg-muted space-y-1 rounded-xl p-3"
              >
                <h4 class="font-medium">{{ contextName(context) }}</h4>
                <p>
                  {{
                    value.distance_meters === null
                      ? t('workoutTrack.unknownDistance')
                      : distanceText(value.distance_meters, displayUnits)
                  }}
                </p>
                <p>
                  {{
                    t('workoutTrack.coordinates', {
                      usable: value.usable_points,
                      reported: value.reported_points,
                    })
                  }}
                </p>
                <p>
                  {{
                    t('workoutTrack.coverage', {
                      seconds: formatAmount(value.coverage_seconds, 0),
                      gaps: value.gap_count,
                    })
                  }}
                </p>
                <p>
                  {{
                    t('workoutTrack.segments', {
                      count: value.usable_segments ?? t('workoutTrack.unknownSegments'),
                    })
                  }}
                </p>
                <p>{{ t('workoutTrack.quality', { quality: trackQualityText(value.quality) }) }}</p>
                <p v-if="value.paused_points !== undefined">
                  {{ t('workoutTrack.pausedCoordinates', { count: value.paused_points }) }}
                </p>
                <p v-if="value.paused_seconds !== undefined">
                  {{
                    t('workoutTrack.pauseDuration', {
                      seconds: formatAmount(value.paused_seconds, 0),
                    })
                  }}
                </p>
              </div>
            </div>
            <div class="text-muted-foreground space-y-1 text-xs">
              <p>
                {{
                  t('workoutTrack.speedLimit', {
                    value:
                      item.snapshot.speed_limit_meters_per_second === undefined
                        ? t('workoutTrack.notSavedMasculine')
                        : t('workoutTrack.speedValue', {
                            value: formatAmount(item.snapshot.speed_limit_meters_per_second),
                          }),
                  })
                }}
              </p>
              <p>
                {{
                  t('workoutTrack.maxInterval', {
                    value:
                      item.snapshot.max_segment_seconds === undefined
                        ? t('workoutTrack.notSavedMasculine')
                        : t('workoutTrack.secondsValue', {
                            value: formatAmount(item.snapshot.max_segment_seconds),
                          }),
                  })
                }}
              </p>
              <p>
                {{
                  t('workoutTrack.maxAccuracy', {
                    value:
                      item.snapshot.max_accuracy_meters === undefined
                        ? t('workoutTrack.notSavedFeminine')
                        : t('workoutTrack.metersValue', {
                            value: formatAmount(item.snapshot.max_accuracy_meters),
                          }),
                  })
                }}
              </p>
              <p>
                {{
                  t('workoutTrack.pausePolicy', {
                    policy:
                      item.snapshot.pause_policy === 'exclude_points_and_crossing_segments-v1'
                        ? t('workoutTrack.excludePaused')
                        : (item.snapshot.pause_policy ?? t('workoutTrack.notSavedNeuter')),
                  })
                }}
              </p>
              <p class="break-all">
                {{
                  t('workoutTrack.reportRef', {
                    id: item.snapshot.report_ref.id,
                    revision: item.snapshot.report_ref.revision,
                  })
                }}
              </p>
            </div>
            <details class="text-muted-foreground text-xs">
              <summary class="min-h-11 cursor-pointer py-2">
                {{ t('workoutTrack.inputCount', { count: item.snapshot.input_refs.length }) }}
              </summary>
              <ul v-if="item.snapshot.input_refs.length" class="space-y-2">
                <li v-for="input in item.snapshot.input_refs" :key="input.id" class="break-all">
                  {{
                    t('workoutTrack.inputRef', {
                      id: input.id,
                      sequence: input.sequence,
                      hash: input.hash,
                    })
                  }}
                </li>
              </ul>
              <p v-else>{{ t('workoutTrack.noInputs') }}</p>
            </details>
            <details class="text-muted-foreground text-xs">
              <summary class="min-h-11 cursor-pointer py-2">
                {{ t('workoutTrack.allVersionFields') }}
              </summary>
              <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
                JSON.stringify(item.snapshot, null, 2)
              }}</pre>
            </details>
          </article>
          <Button
            v-if="archiveQuery.hasNextPage.value"
            size="sm"
            variant="outline"
            :disabled="archiveQuery.isFetchingNextPage.value"
            @click="archiveQuery.fetchNextPage()"
            >{{ t('workoutTrack.loadMoreVersions') }}</Button
          >
        </template>
      </div>
    </details>
  </section>
</template>
