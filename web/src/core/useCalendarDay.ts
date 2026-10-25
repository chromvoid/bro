import { useEventListener, useIntervalFn } from '@vueuse/core'
import { computed, shallowRef, toValue, type MaybeRefOrGetter } from 'vue'
import { dayIn } from './dates'

/** The time to the minute, kept current across a suspended WebView. */
export function useMinuteNow() {
  const now = shallowRef(new Date())
  const refresh = () => (now.value = new Date())
  useIntervalFn(refresh, 60_000)
  useEventListener(document, 'visibilitychange', () => {
    if (document.visibilityState === 'visible') refresh()
  })
  useEventListener(window, 'focus', refresh)
  return computed(() => now.value)
}

/** Keep an open diary's calendar day current across midnight and a suspended WebView. */
export function useCalendarDay(timeZone: MaybeRefOrGetter<string>) {
  const now = useMinuteNow()
  return computed(() => dayIn(toValue(timeZone), now.value))
}
