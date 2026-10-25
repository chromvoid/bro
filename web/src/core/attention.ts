/** Every write, in any section, whose outcome is still unknown: what the user must look at. */
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { useSession } from '@/stores/session'
import type { Submission } from './journal'

/** The section a write belongs to, from its endpoint (/v1/<section>/...). */
export function sectionOf(entry: Submission): string {
  return entry.endpoint.split('/')[2] ?? ''
}

export function useAttention() {
  const session = useSession()
  const entries = shallowRef<Submission[]>([])
  async function reload() {
    const all = (await session.account?.journal.pending()) ?? []
    entries.value = all.filter((entry) => entry.state === 'uncertain')
  }
  let unsubscribe: (() => void) | undefined
  watch(
    () => session.account,
    (account) => {
      unsubscribe?.()
      unsubscribe = account?.journal.subscribe(() => void reload().catch(() => undefined))
      void reload().catch(() => undefined)
    },
    { immediate: true },
  )
  onBeforeUnmount(() => unsubscribe?.())
  const bySection = computed(() => {
    const groups = new Map<string, Submission[]>()
    for (const entry of entries.value)
      groups.set(sectionOf(entry), [...(groups.get(sectionOf(entry)) ?? []), entry])
    return groups
  })
  return { entries, bySection }
}
