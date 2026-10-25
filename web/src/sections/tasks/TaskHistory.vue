<script setup lang="ts">
import { useInfiniteQuery, useQueryClient, type InfiniteData } from '@tanstack/vue-query'
import { computed, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import { TaskHistoryPage, historyItems, historyLines, historyPeople } from './history'

const props = withDefaults(
  defineProps<{
    taskId: string | null
    revision?: number
    active?: boolean
    error?: unknown
  }>(),
  { active: true },
)
const emit = defineEmits<{ retry: []; denied: [error: ApiError] }>()
const { t } = useI18n()
const session = useSession()
const queries = useQueryClient()
const key = computed(() => ['tasks', 'history', props.taskId, session.me?.id ?? null] as const)
const denied = shallowRef<ApiError | null>(null)
const accessError = (error: unknown): error is ApiError =>
  error instanceof ApiError && [401, 403, 404].includes(error.status)

watch(key, () => (denied.value = null), { flush: 'sync' })
const history = useInfiniteQuery({
  queryKey: key,
  initialPageParam: null as string | null,
  enabled: computed(
    () => !!session.me?.id && !!props.taskId && props.active && !denied.value && !props.error,
  ),
  // An unmounted panel retains no audit cache. Task/account switches discard inactive pages.
  gcTime: 0,
  retry: (count, error) => !accessError(error) && count < 1,
  queryFn: async ({ signal, pageParam, queryKey }) => {
    const params = new URLSearchParams({ limit: '50' })
    if (pageParam) params.set('cursor', pageParam)
    return TaskHistoryPage.parse(
      await api.get(
        `/v1/tasks/items/${encodeURIComponent(queryKey[2]!)}/history?${params}`,
        signal,
      ),
    )
  },
  getNextPageParam: (page) => page.next_cursor,
})

watch(
  [() => props.error, history.error],
  ([external, error]) => {
    const refusal = accessError(external) ? external : accessError(error) ? error : null
    if (!refusal || denied.value === refusal) return
    denied.value = refusal
    const deniedKey = key.value
    // TanStack normally retains data after a failed refresh. An access refusal must erase it.
    void queries.cancelQueries({ queryKey: deniedKey, exact: true }).then(() => {
      if (queries.getQueryState(deniedKey))
        queries.setQueryData<InfiniteData<TaskHistoryPage, string | null>>(deniedKey, {
          pages: [],
          pageParams: [],
        })
    })
    emit('denied', refusal)
  },
  { immediate: true, flush: 'sync' },
)
watch(
  () => [props.taskId, props.revision] as const,
  ([taskId, revision], [previousId, previousRevision]) => {
    if (taskId === previousId && revision !== previousRevision && !denied.value)
      void queries.invalidateQueries({ queryKey: key.value, exact: true })
  },
)
const pages = computed(() =>
  denied.value || props.error || !session.me?.id || !props.taskId
    ? []
    : (history.data.value?.pages ?? []),
)
const items = computed(() => historyItems(pages.value))
const people = computed(() => historyPeople(pages.value))
const failure = computed(() => denied.value ?? props.error ?? history.error.value)
function retry() {
  if (props.error) emit('retry')
  else {
    denied.value = null
    // Re-enabling starts a read. Reuse it instead of cancelling it and issuing a second one.
    void history.refetch({ cancelRefetch: false })
  }
}
function stamp(value: string) {
  return new Date(value).toLocaleString('ru-RU', { timeZone: session.me?.timezone ?? 'UTC' })
}
</script>

<template>
  <section
    v-if="active && taskId && session.me"
    class="space-y-4"
    :aria-label="t('tasks.tabs.history')"
    data-testid="task-history"
  >
    <p class="text-muted-foreground text-sm">{{ t('tasks.history.scope') }}</p>
    <ErrorState
      v-if="denied || error || history.isLoadingError.value"
      :error="failure"
      :retrying="history.isFetching.value"
      @retry="retry"
    />
    <LoadingState v-else-if="history.isPending.value" :rows="3" />
    <template v-else>
      <RefreshFailed :query="history" />
      <p v-if="!items.length" class="text-muted-foreground text-sm">
        {{ t('tasks.history.empty') }}
      </p>
      <ol v-else class="space-y-3">
        <li v-for="event in items" :key="event.id" :data-history-event="event.id">
          <article class="border-border space-y-2 rounded-xl border p-3">
            <p class="font-medium">{{ t(`tasks.history.kinds.${event.kind}`) }}</p>
            <p class="text-muted-foreground flex flex-wrap gap-x-2 text-sm">
              <span>{{ event.actor.name || t('tasks.history.unknownPerson') }}</span>
              <time :datetime="event.created_at">{{ stamp(event.created_at) }}</time>
            </p>
            <ul v-if="historyLines(event, people, t).length" class="space-y-1 text-sm">
              <li v-for="line in historyLines(event, people, t)" :key="line.field">
                <template v-if="line.kind === 'value'">
                  <span>{{ line.label }}: </span>
                  <span class="text-muted-foreground">{{ line.before }}</span>
                  <span aria-hidden="true"> → </span>
                  <span class="sr-only">{{ t('tasks.history.changedTo') }} </span>
                  <span>{{ line.after }}</span>
                </template>
                <template v-else>{{ line.text }}</template>
              </li>
            </ul>
            <p v-if="event.reason === 'member_left'" class="text-muted-foreground text-sm">
              {{ t('tasks.history.memberLeft') }}
            </p>
            <p v-else-if="event.reason === 'account_deleted'" class="text-muted-foreground text-sm">
              {{ t('tasks.history.accountDeleted') }}
            </p>
          </article>
        </li>
      </ol>
      <p v-if="history.isFetchNextPageError.value" class="text-destructive text-sm" role="alert">
        {{ t('tasks.history.loadMoreFailed') }}
      </p>
      <Button
        v-if="history.hasNextPage.value"
        type="button"
        variant="outline"
        :disabled="history.isFetching.value"
        @click="history.fetchNextPage()"
      >
        {{ t('tasks.history.loadMore') }}
      </Button>
    </template>
  </section>
</template>
