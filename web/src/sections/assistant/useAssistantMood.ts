/**
 * The brand mark's mouth follows this conversation: B⋮ while a turn runs, then BD or B( briefly.
 */
import { onBeforeUnmount, watch, type Ref } from 'vue'
import { assistantMood, MoodTracker } from '@/components/brandMood'
import type { Exchange } from './model'
import type { PendingSend } from './pending'

export function useAssistantMood(
  exchanges: Readonly<Ref<readonly Exchange[]>>,
  pending: Readonly<Ref<readonly PendingSend[]>>,
  /** An action of this screen is under way. */
  busy: Readonly<Ref<boolean>>,
  /** A message is being delivered. */
  sending: Readonly<Ref<boolean>>,
) {
  const moods = new MoodTracker()
  let timer: ReturnType<typeof setTimeout> | undefined
  function show() {
    clearTimeout(timer)
    const delivering = pending.value.some(
      (entry) => entry.status === 'sending' || entry.status === 'delivered',
    )
    const working = busy.value || sending.value || delivering
    const { mood, until } = moods.update(exchanges.value, working, Date.now())
    assistantMood.value = mood
    if (until !== null) timer = setTimeout(show, until - Date.now())
  }
  watch([exchanges, pending, busy, sending], show, { immediate: true })
  onBeforeUnmount(() => {
    clearTimeout(timer)
    assistantMood.value = 'ok'
  })
}
