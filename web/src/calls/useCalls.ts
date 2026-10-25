/**
 * Calls on this page: whether this person may call, the server's open call, and what the call
 * overlay shows. Ringing is visible only while the app is open on screen, and silent.
 */
import { useQuery } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { computed, onBeforeUnmount, watch, type ComputedRef } from 'vue'
import { updates } from '@/core/client'
import { useSession } from '@/stores/session'
import { fetchCurrent, type Call } from './api'
import type { CallSession, CallView } from './session'
import { calls } from './store'

export type CallModel =
  | { mode: 'session'; session: CallSession; view: CallView }
  | { mode: 'incoming'; call: Call }
  /** Ringing as the caller, or active, without a session in this tab. */
  | { mode: 'elsewhere'; call: Call }
  | { mode: 'none' }

/** Calls exist only for people the administrator gave both rights. */
export function useCallsEnabled(): ComputedRef<boolean> {
  const session = useSession()
  return computed(() => session.can('calls.read') && session.can('calls.write'))
}

/** Called once, by the app shell. */
export function useCalls(): ComputedRef<CallModel> {
  const session = useSession()
  const enabled = useCallsEnabled()
  // When the latest successful read was sent: only a read sent after a session learned its call
  // id can tell that the call is over.
  let sent = 0
  const query = useQuery({
    queryKey: ['calls', 'current'],
    queryFn: async ({ signal }) => {
      const started = Date.now()
      const current = await fetchCurrent(signal)
      sent = started
      return current
    },
    enabled,
  })
  watch(
    [query.dataUpdatedAt, enabled],
    () => {
      const current = enabled.value ? (query.data.value ?? null) : null
      calls.current.value = current
      const active = calls.session.value
      const since = active?.since ?? null
      if (current && active && since !== null && sent > since) active.observe(current)
    },
    { immediate: true },
  )

  const model = computed<CallModel>(() => {
    const active = calls.session.value
    if (active) return { mode: 'session', session: active, view: active.view.value }
    const call = calls.current.value?.call
    if (!call || call.state === 'ended' || call.id === calls.finishedId.value)
      return { mode: 'none' }
    if (call.state === 'ringing' && call.role === 'callee') return { mode: 'incoming', call }
    return { mode: 'elsewhere', call }
  })

  // A deploy must not reload the page under a ringing or running call.
  let release: (() => void) | null = null
  const holding = computed(
    () =>
      model.value.mode === 'incoming' ||
      (model.value.mode === 'session' && model.value.view.phase !== 'ended'),
  )
  watch(
    holding,
    (hold) => {
      if (hold) release ??= updates.hold()
      else {
        release?.()
        release = null
      }
    },
    { immediate: true },
  )

  useEventListener(window, 'pagehide', () => calls.teardown('pagehide'))
  useEventListener(document, 'visibilitychange', () => {
    if (document.visibilityState === 'visible') calls.resume()
  })
  watch(
    () => session.me?.id,
    () => calls.teardown('signed_out'),
  )
  onBeforeUnmount(() => {
    calls.teardown('signed_out')
    release?.()
    release = null
  })
  return model
}
