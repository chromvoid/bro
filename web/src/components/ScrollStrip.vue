<script setup lang="ts">
import { useEventListener, useMutationObserver, useResizeObserver } from '@vueuse/core'
import { onMounted, ref, useTemplateRef, type HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

/**
 * A row that scrolls sideways when it does not fit: chips, tabs, segments. No scrollbar; the side
 * where more items hide fades out, so a cut item reads as «there is more», not as a broken layout.
 * Attributes (aria-label, data-testid) go to the scrolling element itself.
 */
const props = withDefaults(
  defineProps<{
    /** The element: a `nav` for a row of links. */
    as?: 'div' | 'nav'
    /** Reaches the screen edges past the page padding (16 px), the first item still aligned. */
    bleed?: boolean
    class?: HTMLAttributes['class']
  }>(),
  { as: 'div' },
)

const strip = useTemplateRef<HTMLElement>('strip')
const start = ref(false)
const end = ref(false)
function measure() {
  const element = strip.value
  if (!element) return
  const hidden = element.scrollWidth - element.clientWidth
  start.value = element.scrollLeft > 1
  end.value = hidden - element.scrollLeft > 1
}
useEventListener(strip, 'scroll', measure, { passive: true })
useResizeObserver(strip, measure)
useMutationObserver(strip, measure, { childList: true, subtree: true, characterData: true })
onMounted(measure)

/** Brings an item into the middle of the row without moving the page up or down. */
function reveal(item: Element | null | undefined, behavior: ScrollBehavior = 'auto') {
  const element = strip.value
  if (!element || !item) return
  const row = element.getBoundingClientRect()
  const box = item.getBoundingClientRect()
  const left = element.scrollLeft + box.left - row.left - (row.width - box.width) / 2
  element.scrollTo({ left: Math.max(0, left), behavior })
}
defineExpose({ reveal, measure, element: strip })
</script>

<template>
  <component
    :is="props.as"
    ref="strip"
    :class="
      cn(
        'flex overflow-x-auto overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
        bleed && '-mx-4 scroll-px-4 px-4',
        start && 'mask-l-from-[calc(100%-1.5rem)] mask-l-to-100%',
        end && 'mask-r-from-[calc(100%-1.5rem)] mask-r-to-100%',
        props.class,
      )
    "
    :data-overflow-start="start || undefined"
    :data-overflow-end="end || undefined"
  >
    <slot />
  </component>
</template>
