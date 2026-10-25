<script setup lang="ts">
import { computed } from 'vue'
import { line, scale, trend, type Point } from './scale'

/** A small trend line next to a number: decoration, the text beside it says the change. */
const props = withDefaults(defineProps<{ points: Point[]; width?: number; height?: number }>(), {
  width: 96,
  height: 32,
})
const path = computed(() => {
  const scaled = scale(props.points, { width: props.width, height: props.height, padding: 3 })
  return scaled ? line(trend(props.points, 7 * 86_400_000), scaled.x, scaled.y) : ''
})
</script>

<template>
  <svg
    v-if="points.length > 1"
    :viewBox="`0 0 ${width} ${height}`"
    :width="width"
    :height="height"
    aria-hidden="true"
    class="shrink-0"
  >
    <path
      :d="path"
      fill="none"
      stroke-width="2"
      stroke-linejoin="round"
      stroke-linecap="round"
      class="stroke-chart-1"
    />
  </svg>
</template>
