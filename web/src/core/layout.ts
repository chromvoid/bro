/** Layout decisions a media query cannot make. */
import { useResizeObserver } from '@vueuse/core'
import { computed, onScopeDispose, ref, type Ref } from 'vue'

/**
 * Whether an element is at least `rem` wide, counted in the root font's rem: with larger text a
 * split layout needs more room. Media queries keep the browser's default rem and do not see it.
 */
export function useRoomFor(area: Ref<HTMLElement | null>, rem: number) {
  return useMeasuredRoomFor(area, rem).roomy
}

/** Keep a panel unmounted until its first layout decision can use an actual measurement. */
export function useMeasuredRoomFor(area: Ref<HTMLElement | null>, rem: number) {
  const width = ref(0)
  const rootSize = ref(16)
  const measured = ref(false)
  let frame = 0
  useResizeObserver(area, ([entry]) => {
    if (!entry) return
    cancelAnimationFrame(frame)
    // A layout decision can resize the observed area. Apply it in the next frame, outside
    // ResizeObserver's delivery cycle, so WebKit never enters a resize notification loop.
    frame = requestAnimationFrame(() => {
      frame = 0
      if (entry.target !== area.value) return
      rootSize.value = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
      width.value = entry.contentRect.width
      measured.value = true
    })
  })
  onScopeDispose(() => cancelAnimationFrame(frame))
  return {
    roomy: computed(() => width.value > 0 && width.value >= rem * rootSize.value),
    measured,
  }
}
