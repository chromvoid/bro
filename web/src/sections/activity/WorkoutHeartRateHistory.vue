<script setup lang="ts">
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useSession } from '@/stores/session'
import WorkoutHeartRateChart from './WorkoutHeartRateChart.vue'
import {
  WorkoutHeartRatePage,
  coherentWorkoutHeartRatePages,
  workoutHeartRateSamples,
} from './workoutHeartRate'

const props = defineProps<{ workoutId: string; sourceId: string; timezone: string }>()
const { t } = useI18n()
const account = useSession()
const user = computed(() => account.me?.id ?? null)
const permitted = computed(() => !!user.value && account.can('personal.read'))
const client = useQueryClient()
const reader = useId()
const generation = ref(0)
const historyKey = computed(() => [
  'personal',
  'workout-heart-rate',
  user.value,
  props.sourceId,
  props.workoutId,
  reader,
  generation.value,
])
watch(
  () => [user.value, props.sourceId, props.workoutId, permitted.value] as const,
  (_current, previous) => {
    const epoch = generation.value++
    client.removeQueries({
      queryKey: [
        'personal',
        'workout-heart-rate',
        previous[0],
        previous[1],
        previous[2],
        reader,
        epoch,
      ],
      exact: true,
    })
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  const key = historyKey.value
  generation.value++
  client.removeQueries({ queryKey: key, exact: true })
})
const history = useInfiniteQuery({
  queryKey: historyKey,
  enabled: permitted,
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const epoch = generation.value
    const owner = user.value
    const workout = props.workoutId
    const source = props.sourceId
    if (!permitted.value) throw new DOMException('Permission changed', 'AbortError')
    const query = new URLSearchParams({ source_id: source, limit: '100' })
    if (pageParam !== null) query.set('cursor', pageParam)
    const page = WorkoutHeartRatePage.parse(
      await api.get(
        `/v1/personal/rings/workouts/${encodeURIComponent(workout)}/heart-rate?${query}`,
        signal,
      ),
    )
    if (
      signal.aborted ||
      generation.value !== epoch ||
      user.value !== owner ||
      props.sourceId !== source ||
      props.workoutId !== workout ||
      !permitted.value
    )
      throw new DOMException('Workout history context changed', 'AbortError')
    if (page.workout_id !== workout || page.source_id !== source)
      throw new Error('Mismatched workout source')
    return { ...page, readEpoch: epoch, readOwner: owner }
  },
  getNextPageParam: (page) => page.next_cursor ?? undefined,
})
const storedPages = computed(() => {
  const loaded = history.data.value?.pages ?? []
  return loaded.every(
    (page) =>
      page.readEpoch === generation.value &&
      page.readOwner === user.value &&
      page.source_id === props.sourceId &&
      page.workout_id === props.workoutId,
  )
    ? loaded
    : []
})
const coherent = computed(() => coherentWorkoutHeartRatePages(storedPages.value))
const failed = computed(
  () => !coherent.value || history.isFetchNextPageError.value || history.isRefetchError.value,
)
const pages = computed(() => (permitted.value && !failed.value ? storedPages.value : []))
function restartHistory() {
  const key = historyKey.value
  generation.value++
  client.removeQueries({ queryKey: key, exact: true })
  void client.resetQueries({ queryKey: historyKey.value, exact: true })
}
const samples = computed(() => workoutHeartRateSamples(pages.value))
const gapCount = computed(() =>
  pages.value.reduce(
    (count, page) => count + page.chunks.reduce((sum, chunk) => sum + chunk.gaps.length, 0),
    0,
  ),
)
const qualityUnknown = computed(() =>
  pages.value.some((page) =>
    page.chunks.some((chunk) => chunk.samples.some((sample) => sample.quality === 'unknown')),
  ),
)
</script>

<template>
  <section v-if="permitted" class="space-y-3" data-testid="workout-heart-rate-history">
    <p v-if="history.isPending.value" class="text-muted-foreground text-sm" role="status">
      {{ t('ring.workout.loadingHistory') }}
    </p>
    <p v-if="failed" class="text-destructive text-sm" role="alert">
      {{ t('workoutPulse.incomplete') }}
    </p>
    <p v-else-if="history.isLoadingError.value" class="text-destructive text-sm" role="alert">
      {{ errorText(history.error.value, t) }}
    </p>
    <WorkoutHeartRateChart v-if="samples.length" :samples="samples" :timezone="timezone" />
    <p
      v-else-if="!history.isPending.value && !history.isLoadingError.value && !failed"
      class="text-muted-foreground text-sm"
    >
      {{ t('ring.workout.noSamples') }}
    </p>
    <p v-if="gapCount" class="text-warning text-sm">
      {{ t('ring.workout.gaps', { count: gapCount }) }}
    </p>
    <p v-if="qualityUnknown" class="text-muted-foreground text-xs">
      {{ t('ring.workout.qualityUnknown') }}
    </p>
    <details v-if="pages.length" class="text-muted-foreground text-xs">
      <summary class="min-h-11 cursor-pointer py-2">{{ t('workoutPulse.rawSamples') }}</summary>
      <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(
          pages.flatMap((page) => page.chunks),
          null,
          2,
        )
      }}</pre>
    </details>
    <Button
      v-if="history.isLoadingError.value || failed"
      size="sm"
      variant="outline"
      :disabled="history.isFetching.value"
      @click="restartHistory"
      >{{ t('workoutPulse.refresh') }}</Button
    >
    <Button
      v-if="history.hasNextPage.value && !failed"
      size="sm"
      variant="outline"
      :disabled="history.isFetching.value"
      @click="history.fetchNextPage()"
      >{{ t('ring.loadMore') }}</Button
    >
  </section>
</template>
