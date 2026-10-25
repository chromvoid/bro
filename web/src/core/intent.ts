/**
 * A link can ask a screen to start an action, e.g. `/food?add=1` from an empty card on «Сегодня».
 * The screen does it once and drops the parameter, so Back and reload do not repeat it.
 */
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

export function useQueryIntent(key: string, run: () => void): void {
  const route = useRoute()
  const router = useRouter()
  watch(
    () => route.query[key],
    (value) => {
      if (value !== '1') return
      run()
      const query = { ...route.query }
      delete query[key]
      void router.replace({ query })
    },
    { immediate: true },
  )
}
