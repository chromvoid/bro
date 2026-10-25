<script setup lang="ts">
import { computed } from 'vue'
import { linear } from './geometry'

/**
 * Items side by side on one signed scale: a bar from zero to each value, gains one color and losses another,
 * with the value in words at the end of the row. A row is a button when the item opens something.
 */
const props = defineProps<{
  items: Array<{ key: string; label: string; value: number | null; text: string }>
  label: string
  clickable?: boolean
}>()
const emit = defineEmits<{ select: [key: string] }>()
/** Bars in 0..100 units of the bar column; zero sits where the smallest and largest values leave it. */
const bars = computed(() => {
  const values = props.items.flatMap((item) => (item.value === null ? [] : [item.value]))
  const low = Math.min(0, ...values)
  const high = Math.max(0, ...values)
  const x = linear([low, high === low ? low + 1 : high], [0, 100])
  const zero = x(0)
  return {
    zero,
    rows: props.items.map((item) => {
      if (item.value === null) return { ...item, x: null, width: null }
      const end = x(item.value)
      return { ...item, x: Math.min(zero, end), width: Math.max(0.5, Math.abs(end - zero)) }
    }),
  }
})
</script>

<template>
  <ul class="space-y-1" :aria-label="label">
    <li v-for="row in bars.rows" :key="row.key">
      <component
        :is="clickable ? 'button' : 'div'"
        :type="clickable ? 'button' : undefined"
        :class="[
          'grid w-full grid-cols-[4rem_minmax(0,1fr)_auto] items-center gap-3 rounded-lg px-2 py-1.5 text-left text-sm',
          clickable
            ? 'hover:bg-muted/60 focus-visible:ring-ring/50 outline-none focus-visible:ring-3'
            : '',
        ]"
        @click="clickable && emit('select', row.key)"
      >
        <span class="truncate font-medium">{{ row.label }}</span>
        <svg
          v-if="row.value !== null"
          viewBox="0 0 100 10"
          preserveAspectRatio="none"
          class="h-3 w-full"
          aria-hidden="true"
        >
          <line
            :x1="bars.zero"
            :x2="bars.zero"
            y1="0"
            y2="10"
            class="stroke-border"
            stroke-width="0.6"
          />
          <rect
            :x="row.x!"
            y="1"
            :width="row.width!"
            height="8"
            :class="row.value >= 0 ? 'fill-success' : 'fill-destructive'"
          />
        </svg>
        <span v-else />
        <span
          :class="[
            'text-right tabular-nums',
            row.value === null
              ? 'text-muted-foreground'
              : row.value >= 0
                ? 'text-success'
                : 'text-destructive',
          ]"
          >{{ row.text }}</span
        >
      </component>
    </li>
  </ul>
</template>
