/** The system bars over the page in a native shell, in pixels, from the `--inset-*` properties. */
import { createGlobalState, useEventListener } from '@vueuse/core'
import { computed, ref } from 'vue'

export interface Insets {
  top: number
  right: number
  bottom: number
  left: number
}

const SIDES = ['top', 'right', 'bottom', 'left'] as const
/** Room a floating menu keeps beyond a system bar or the screen edge. */
const GAP = 8

/** A registered length reads as pixels; an engine that cannot resolve it reads as no inset. */
export function readInsets(style: CSSStyleDeclaration): Insets {
  const side = (name: (typeof SIDES)[number]) => {
    const value = Number.parseFloat(style.getPropertyValue(`--inset-${name}`))
    return Number.isFinite(value) ? value : 0
  }
  return { top: side('top'), right: side('right'), bottom: side('bottom'), left: side('left') }
}

export const useSafeArea = createGlobalState(() => {
  const insets = ref<Insets>({ top: 0, right: 0, bottom: 0, left: 0 })
  function measure() {
    const next = readInsets(getComputedStyle(document.documentElement))
    if (SIDES.some((name) => next[name] !== insets.value[name])) insets.value = next
  }
  measure()
  // Rotation moves the cut-out; the keyboard changes the visual viewport, not the insets.
  useEventListener(window, 'resize', measure, { passive: true })
  if (window.visualViewport) useEventListener(window.visualViewport, 'resize', measure)

  /** For reka's floating content: menus and popovers stay clear of the bars. */
  const collisionPadding = computed(() => ({
    top: insets.value.top + GAP,
    right: insets.value.right + GAP,
    bottom: insets.value.bottom + GAP,
    left: insets.value.left + GAP,
  }))
  return { insets, collisionPadding, measure }
})
