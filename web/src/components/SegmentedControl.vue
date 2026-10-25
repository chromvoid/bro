<script setup lang="ts">
import { RadioGroupItem, RadioGroupRoot } from 'reka-ui'
import { computed, nextTick, onMounted, useTemplateRef, watch, type Component } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import ScrollStrip from '@/components/ScrollStrip.vue'
import { SEGMENT_CHOSEN } from '@/components/segmented'
import { cn } from '@/lib/utils'

/**
 * One look for every small switch of a few options. With `to` on its segments it is navigation
 * (links, the current one marked with aria-current); otherwise a radio group bound with v-model,
 * with arrow keys. Segments reach 44 px on touch screens. The segments never wrap onto a second
 * row: a row wider than the screen scrolls sideways and keeps the chosen segment in view.
 */
export interface Segment {
  value: string
  label: string
  to?: RouteLocationRaw
  /** Shown before the label. */
  icon?: Component
}

const props = defineProps<{
  segments: Segment[]
  /** The accessible name of the group. */
  label: string
  /** The selected value; for navigation, the current one. */
  modelValue?: string
  /** Fill the width with equal segments instead of fitting the labels. */
  stretch?: boolean
  /**
   * Four or so equal segments that must all stay visible: two by two in a narrow container, one
   * row from 28rem (an ancestor carries `@container`).
   */
  even?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const navigation = computed(() => props.segments.some((segment) => segment.to !== undefined))
const group = computed(() =>
  cn(
    'bg-muted gap-1 rounded-xl p-1',
    props.even
      ? 'grid w-full grid-cols-2 @md:grid-cols-4'
      : props.stretch
        ? 'grid w-full auto-cols-fr grid-flow-col'
        : 'inline-flex w-max shrink-0',
  ),
)
// Equal stretched segments get narrower padding so short labels stay on one line.
const segmentClass = computed(() =>
  cn(
    'text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 inline-flex min-h-9 border border-transparent items-center justify-center gap-1.5 rounded-lg text-center text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-3 pointer-coarse:min-h-11 [&_svg]:size-4 [&_svg]:shrink-0',
    props.stretch || props.even ? 'px-1.5' : 'px-3',
  ),
)

// The chosen segment stays in view when the row scrolls.
const strip = useTemplateRef<InstanceType<typeof ScrollStrip>>('strip')
function reveal() {
  void nextTick(() =>
    strip.value?.reveal(
      strip.value.element?.querySelector('[aria-current="page"], [data-state="checked"]'),
    ),
  )
}
onMounted(reveal)
watch(() => props.modelValue, reveal)
</script>

<template>
  <ScrollStrip ref="strip" :class="stretch || even ? 'w-full' : 'max-w-full'" data-slot="segmented">
    <nav v-if="navigation" :class="group" :aria-label="label">
      <RouterLink
        v-for="segment in segments"
        :key="segment.value"
        :to="segment.to!"
        :aria-current="segment.value === modelValue ? 'page' : undefined"
        :class="cn(segmentClass, segment.value === modelValue && SEGMENT_CHOSEN)"
        ><component :is="segment.icon" v-if="segment.icon" aria-hidden="true" />{{
          segment.label
        }}</RouterLink
      >
    </nav>
    <RadioGroupRoot
      v-else
      :model-value="modelValue"
      :aria-label="label"
      orientation="horizontal"
      loop
      :class="group"
      @update:model-value="(value) => emit('update:modelValue', String(value))"
    >
      <RadioGroupItem
        v-for="segment in segments"
        :key="segment.value"
        :value="segment.value"
        :class="
          cn(
            segmentClass,
            'data-[state=checked]:bg-background data-[state=checked]:text-foreground data-[state=checked]:shadow-sm dark:data-[state=checked]:border-border dark:data-[state=checked]:bg-accent',
          )
        "
        ><component :is="segment.icon" v-if="segment.icon" aria-hidden="true" />{{
          segment.label
        }}</RadioGroupItem
      >
    </RadioGroupRoot>
  </ScrollStrip>
</template>
