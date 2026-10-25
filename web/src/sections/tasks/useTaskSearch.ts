/**
 * The search field of «Дела»: what is typed reaches the address a moment later, and an address
 * changed elsewhere (Back) fills the field again. Search sits behind an icon; a search in the
 * address opens it.
 */
import { refDebounced } from '@vueuse/core'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

export function useTaskSearch() {
  const route = useRoute()
  const router = useRouter()
  const search = ref(typeof route.query.q === 'string' ? route.query.q : '')
  const query = refDebounced(
    computed(() => search.value.trim()),
    250,
  )
  watch(
    () => route.query.q,
    (q) => {
      const value = typeof q === 'string' ? q : ''
      if (search.value !== value) search.value = value
    },
  )
  watch(query, (q) => void router.replace({ query: { ...route.query, q: q || undefined } }))
  // Search sits behind an icon; a search in the address opens it.
  const searchOpen = ref(!!search.value)
  watch(search, (value) => {
    if (value) searchOpen.value = true
  })
  function toggleSearch() {
    searchOpen.value = !searchOpen.value
    if (searchOpen.value) void nextTick(() => document.getElementById('task-search')?.focus())
    else search.value = ''
  }

  return { search, query, searchOpen, toggleSearch }
}
