/**
 * A board whose columns do not fit side by side scrolls sideways: the column in view, and moving
 * to one. On phones and medium screens the state tabs above the board follow it.
 */
import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'

export function useBoardColumns(
  scroller: Readonly<Ref<HTMLElement | null>>,
  /** All four columns fit side by side. */
  allColumns: Readonly<Ref<boolean>>,
  /** A wide screen: the columns start at the scroller's edge, without a phone's margin. */
  wide: Readonly<Ref<boolean>>,
) {
  const current = ref(0)
  watch(allColumns, async (all, wasAll) => {
    if (all || !wasAll) return
    await nextTick()
    jump(current.value)
  })
  function jump(index: number) {
    const target = scroller.value?.children[index] as HTMLElement | undefined
    if (!target || !scroller.value) return
    scroller.value.scrollTo({
      left:
        scroller.value.scrollLeft +
        target.getBoundingClientRect().left -
        scroller.value.getBoundingClientRect().left -
        (wide.value ? 0 : 16),
      // Native smooth motion with scroll snapping can stop between columns in WebKit.
      behavior: 'auto',
    })
    current.value = index
    // A resize can change the final position, and a no-op emits no scrollend.
    // The navigation and mobile add action follow the column that is actually visible.
    scrolled()
  }
  let frame = 0
  function scrolled() {
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      const box = scroller.value
      if (!box) return
      const children = [...box.children] as HTMLElement[]
      const viewport = box.getBoundingClientRect()
      const active = children[current.value]?.getBoundingClientRect()
      if (active && active.left >= viewport.left && active.right <= viewport.right) return
      const left = viewport.left + (wide.value ? 0 : 16)
      const index = children.reduce(
        (nearest, child, index) =>
          Math.abs(child.getBoundingClientRect().left - left) <
          Math.abs(children[nearest]!.getBoundingClientRect().left - left)
            ? index
            : nearest,
        0,
      )
      current.value = index
    })
  }
  onBeforeUnmount(() => cancelAnimationFrame(frame))

  return {
    /** The index of the column in view. */
    current,
    jump,
    /** Follows the column the reader scrolled to, once the scroll settles in a frame. */
    scrolled,
  }
}
