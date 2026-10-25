import { nextTick, watch, type Ref, type ShallowRef } from 'vue'

/**
 * The room left of a chart for its value labels, measured once they are drawn. A fixed gutter cut
 * «290 тыс. $» down to «0 тыс. $» on a phone with larger system text. `gutter` starts at the
 * chart's default and follows the widest label; the labels do not depend on the gutter, so it
 * settles at once.
 */
export function useAxisGutter(
  gutter: Ref<number>,
  svg: Readonly<ShallowRef<SVGSVGElement | null>>,
  labels: () => string,
) {
  watch(
    [labels, svg],
    async () => {
      await nextTick()
      let widest = 0
      for (const text of svg.value?.querySelectorAll<SVGGraphicsElement>('[data-axis-label]') ??
        []) {
        if (typeof text.getBBox !== 'function') return
        widest = Math.max(widest, text.getBBox().width)
      }
      const next = Math.ceil(widest) + 10
      if (widest && Math.abs(next - gutter.value) > 1) gutter.value = next
    },
    { immediate: true, flush: 'post' },
  )
}
