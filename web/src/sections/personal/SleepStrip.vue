<script setup lang="ts">
import { computed } from 'vue'
import { nightLanes, WIDTH, type Lane, type Part } from './sleepChart'

/** A night at a glance on the dashboard: sleep, waking and unknown along one thin bar. */
const props = defineProps<{ start: string; end: string; parts: Part[] }>()
const parts = computed(() =>
  nightLanes(props.start, props.end, props.parts).flatMap((row) => row.parts),
)
const FILL: Record<Lane, string> = {
  awake: 'fill-warning-fill',
  in_bed: 'fill-warning-fill/50',
  rem: 'fill-primary',
  light: 'fill-primary',
  deep: 'fill-primary',
  sleep: 'fill-primary',
  unknown: 'fill-muted-foreground/40',
  conflict: 'fill-destructive',
}
</script>

<template>
  <svg
    :viewBox="`0 0 ${WIDTH} 1`"
    preserveAspectRatio="none"
    class="bg-muted block h-2 w-full max-w-56 rounded-full"
    aria-hidden="true"
    data-testid="sleep-strip"
  >
    <rect
      v-for="part in parts"
      :key="part.start_at"
      :x="part.x"
      :width="part.width"
      y="0"
      height="1"
      :class="FILL[part.lane]"
      :data-state="part.lane"
    />
  </svg>
</template>
