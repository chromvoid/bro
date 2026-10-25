import { useEventListener, useResizeObserver } from '@vueuse/core'
import { nextTick, readonly, ref, type InjectionKey, type Ref } from 'vue'

/** The conversation's scroller, for observers that need it as their root. */
const SCROLL_KEYS = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '])

export const conversationScroller: InjectionKey<Readonly<Ref<HTMLElement | null>>> =
  Symbol('conversationScroller')

/**
 * Keeps the conversation at its newest message while the user stays at the bottom. Any upward
 * scroll of theirs stops following at once, even a few pixels: a streamed answer grows several
 * times a second and must never pull them back. Coming back to the bottom resumes it. The view
 * follows size changes (a growing answer, a loaded photo, the keyboard), not the events behind
 * them.
 */
export function useFollowBottom(
  scroller: Readonly<Ref<HTMLElement | null>>,
  content: Readonly<Ref<HTMLElement | null>>,
  paused: () => boolean,
) {
  const following = ref(true)
  const atBottom = ref(true)
  // A question just sent stands at the top with room below for its answer: reaching the end of that
  // room is not a return to the newest message. Only the reader's own scroll ends the hold.
  const held = ref(false)
  let touching = false
  let lastTop = 0
  let lastHeight = 0
  const gap = (element: HTMLElement) =>
    element.scrollHeight - element.scrollTop - element.clientHeight

  function track() {
    const element = scroller.value
    if (!element) return
    const top = element.scrollTop
    const height = element.scrollHeight
    atBottom.value = gap(element) <= 2
    if (atBottom.value) {
      // Content shrinking below a reader clamps them to the bottom; that is not their move.
      if (!held.value && (top >= lastTop || height === lastHeight)) following.value = true
    } else if (top < lastTop && top < lastHeight - element.clientHeight - 2)
      // Only a move above the previous bottom: an iOS bounce settling onto it while the answer
      // grows is not one.
      following.value = false
    lastTop = top
    lastHeight = height
  }

  function pin(force = false) {
    const element = scroller.value
    if (!element) return
    atBottom.value = gap(element) <= 2
    if (paused() || (!force && (!following.value || touching))) return
    if (gap(element) > 1) element.scrollTop = element.scrollHeight
    atBottom.value = gap(element) <= 2
    // Where the conversation now stands is not the reader's move, and the next reported scroll is
    // measured from here. WebKit reports a clamp (rows measured shorter) a frame later, after the
    // answer may have grown, and a reader's scroll in the frame of the write above comes as one
    // event with it: from the older position the first reads as scrolling up, the second as down.
    lastTop = element.scrollTop
    lastHeight = element.scrollHeight
  }

  // Fingers on the conversation own the position; lifting the last one catches up with what grew
  // meanwhile. The end of a touch goes to the node it started on, which may have left the page by
  // then (a live answer replaced by the final one), so it is heard there and not on the scroller.
  // Fingers are told apart: one resting on the composer does not hold the conversation.
  const fingers = new Map<number, { x: number; y: number }>()
  const touched: Array<() => void> = []
  function touchEnded(event: TouchEvent) {
    const element = scroller.value
    for (const finger of Array.from(event.changedTouches)) {
      const start = fingers.get(finger.identifier)
      if (!start) continue
      fingers.delete(finger.identifier)
      // A finger that pulled the list down has read up even before its scroll is reported: a
      // flick may only start moving the list as it lifts.
      const down = finger.clientY - start.y
      if (Math.abs(down) > 10) held.value = false
      if (
        down > 10 &&
        down > Math.abs(finger.clientX - start.x) &&
        element &&
        element.scrollHeight > element.clientHeight
      )
        following.value = false
    }
    const still = new Set(Array.from(event.touches, (finger) => finger.identifier))
    for (const identifier of fingers.keys()) if (!still.has(identifier)) fingers.delete(identifier)
    if (fingers.size) return
    touching = false
    for (const forget of touched.splice(0)) forget()
    track()
    pin()
  }
  useEventListener(scroller, 'scroll', track, { passive: true })
  useEventListener(
    scroller,
    'touchstart',
    (event) => {
      for (const finger of Array.from(event.changedTouches))
        fingers.set(finger.identifier, { x: finger.clientX, y: finger.clientY })
      touching = true
      const node = event.target
      if (!node) return
      node.addEventListener('touchend', touchEnded as EventListener, { passive: true })
      node.addEventListener('touchcancel', touchEnded as EventListener, { passive: true })
      touched.push(() => {
        node.removeEventListener('touchend', touchEnded as EventListener)
        node.removeEventListener('touchcancel', touchEnded as EventListener)
      })
    },
    { passive: true },
  )
  // Before the scroll itself: a write would cut a smooth wheel scroll short. A wheel that cannot
  // move the conversation, a sideways swipe over a wide table or a pinch is not reading up.
  useEventListener(
    scroller,
    'wheel',
    (event) => {
      if (event.deltaY) held.value = false
      if (
        event.deltaY < 0 &&
        Math.abs(event.deltaY) > Math.abs(event.deltaX) &&
        !event.ctrlKey &&
        (scroller.value?.scrollTop ?? 0) > 0
      )
        following.value = false
    },
    { passive: true },
  )
  useResizeObserver([scroller, content], () => pin())
  // Keys that move the conversation are the reader's own scroll too.
  useEventListener(scroller, 'keydown', (event: KeyboardEvent) => {
    if (SCROLL_KEYS.has(event.key)) held.value = false
  })

  return {
    /** Whether content growth should keep the newest message visible. */
    following: readonly(following),
    /** The actual position, also updated when content grows without a scroll event. */
    atBottom: readonly(atBottom),
    /** A just-sent question holds the screen; see `held`. */
    held: readonly(held),
    /** Shows the newest message and follows it again, even under a finger. */
    follow() {
      held.value = false
      following.value = true
      void nextTick(() => pin(true))
    },
    /** Stops following until the user comes back to the bottom. */
    release() {
      held.value = false
      following.value = false
    },
    /** Keeps the screen where it is placed until the reader scrolls themselves. */
    hold() {
      held.value = true
      following.value = false
    },
  }
}
