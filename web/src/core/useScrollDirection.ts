import { useEventListener } from '@vueuse/core'
import { ref } from 'vue'

/**
 * Which way the page last moved and whether it reached its end: a floating button steps aside
 * while the person reads down and comes back when they turn up or reach the bottom. Moves under
 * `threshold` pixels (a finger resting on the glass) change nothing.
 */
export function useScrollDirection(threshold = 8, end = 48) {
  const direction = ref<'up' | 'down' | null>(null)
  const atEnd = ref(false)
  let last = typeof window === 'undefined' ? 0 : window.scrollY
  function update() {
    const y = window.scrollY
    const root = document.documentElement
    atEnd.value = y + window.innerHeight >= root.scrollHeight - end
    if (Math.abs(y - last) < threshold) return
    direction.value = y > last && y > 0 ? 'down' : 'up'
    last = y
  }
  useEventListener(window, 'scroll', update, { passive: true })
  return { direction, atEnd }
}
