import { useEventListener, useIntervalFn } from '@vueuse/core'
import { sendFix } from './api'
import { positionSource } from './position'

/** How often the open app tells the server where this device is (docs/location-weather-spec.md §3.1). */
export const REPORT_EVERY = 10 * 60_000

/**
 * While the signed-in app is on screen: on start, on return to the screen and every ten minutes,
 * send this device's position — only when the permission is already granted, never prompting.
 * A failed send waits for the next chance; nothing is queued.
 */
export function useLocationReporter(now: () => number = Date.now) {
  let last = -Infinity
  let busy = false

  async function report(force = false) {
    if (busy || document.visibilityState !== 'visible' || (!force && now() - last < REPORT_EVERY))
      return
    busy = true
    try {
      const source = await positionSource()
      if ((await source.status()) !== 'granted') return
      const fix = await source.current()
      if (!fix) return
      await sendFix(fix)
      last = now()
    } catch {
      // Offline or signed out: the next visible minute tries again.
    } finally {
      busy = false
    }
  }

  void report(true)
  useIntervalFn(() => void report(), REPORT_EVERY)
  useEventListener(document, 'visibilitychange', () => {
    if (document.visibilityState === 'visible') void report()
  })
  return { report }
}
