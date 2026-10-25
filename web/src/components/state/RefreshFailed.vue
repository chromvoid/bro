<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

/** A query (useQuery or useInfiniteQuery) whose loaded data stays on screen. */
interface Refreshable {
  isRefetchError: Readonly<Ref<boolean>>
  isFetching: Readonly<Ref<boolean>>
  refetch: () => Promise<unknown>
}

/**
 * A failed refresh over loaded data: TanStack Query reports it as an error but keeps the data,
 * so the screen keeps it too and says here that it may be out of date. ErrorState is for data
 * that never loaded (isLoadingError).
 */
const props = defineProps<{ query: Refreshable | readonly Refreshable[] }>()
const { t } = useI18n()
const failed = computed(() => [props.query].flat().filter((query) => query.isRefetchError.value))
const retrying = computed(() => failed.value.some((query) => query.isFetching.value))
function retry() {
  for (const query of failed.value) void query.refetch()
}
</script>

<template>
  <div
    v-if="failed.length"
    class="bg-muted flex flex-wrap items-center gap-2 rounded-xl p-3 text-sm"
    role="status"
    data-state="refresh-failed"
  >
    <p class="min-w-0 flex-1">{{ t('state.refreshFailed') }}</p>
    <Button type="button" size="sm" variant="outline" :disabled="retrying" @click="retry">
      <RefreshCw :class="{ 'animate-spin': retrying }" />
      {{ t('common.retry') }}
    </Button>
  </div>
</template>
