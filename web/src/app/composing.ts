/**
 * Typing on a phone: while the on-screen keyboard is up over the assistant's composer, the tab
 * bar steps aside so the conversation keeps the room the keyboard leaves. It comes back once the
 * keyboard closes, even when the field keeps its focus (Android's back button), and on wide
 * screens there is no tab bar to hide.
 */
import { useEventListener } from '@vueuse/core'
import { computed, ref } from 'vue'

/** Less than this is the browser's own toolbar collapsing, not a keyboard. */
const KEYBOARD_MIN_PX = 150
/** Focus leaving the composer waits this long, so a tap on its buttons lands where it began. */
const FOCUS_SETTLE_MS = 300

const composerFocused = ref(false)
const keyboardOpen = ref(false)

export const tabBarHidden = computed(() => composerFocused.value && keyboardOpen.value)

let settling: ReturnType<typeof setTimeout> | undefined

/** The composer reports whether focus is inside it; losing it settles first. */
export function setComposerFocus(focused: boolean) {
  clearTimeout(settling)
  if (focused) composerFocused.value = true
  else settling = setTimeout(() => (composerFocused.value = false), FOCUS_SETTLE_MS)
}

/**
 * Follows the visual viewport: the keyboard is whatever took its height away. The tallest height
 * seen at the current width stands for the screen without a keyboard; a new width (rotation,
 * zoom) starts over. Call once from the shell's setup.
 */
export function watchKeyboard() {
  const viewport = typeof window === 'undefined' ? undefined : window.visualViewport
  if (!viewport) return
  let width = 0
  let tallest = 0
  const measure = () => {
    if (viewport.width !== width) {
      width = viewport.width
      tallest = 0
    }
    tallest = Math.max(tallest, viewport.height)
    keyboardOpen.value = tallest - viewport.height > KEYBOARD_MIN_PX
  }
  measure()
  useEventListener(viewport, 'resize', measure)
}
