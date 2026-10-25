import { computed, shallowReactive, watch, type Ref } from 'vue'

interface InitialLoadQuery {
  data: Readonly<Ref<unknown>>
  error: Readonly<Ref<unknown>>
  errorUpdatedAt: Readonly<Ref<number>>
  isLoadingError: Readonly<Ref<boolean>>
  isFetching: Readonly<Ref<boolean>>
}

/** Keep the last failed initial load visible while TanStack resets error/status for a retry. */
export function useInitialLoadError(query: InitialLoadQuery) {
  const failures = shallowReactive(new Map<number, { error: unknown }>())
  watch(
    [query.data, query.error, query.isLoadingError, query.errorUpdatedAt],
    ([data, error, isLoadingError, errorUpdatedAt]) => {
      // Each query keeps its failure timestamp during retries, even while another key loads.
      if (data === undefined && isLoadingError && errorUpdatedAt > 0)
        failures.set(errorUpdatedAt, { error })
    },
    { immediate: true },
  )
  return computed(() => {
    if (query.data.value !== undefined) return null
    const failure = failures.get(query.errorUpdatedAt.value)
    return failure ? { error: failure.error, retrying: query.isFetching.value } : null
  })
}
