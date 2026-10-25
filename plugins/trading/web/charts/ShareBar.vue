<script setup lang="ts">
import { computed } from 'vue'
import { DOTS, FILLS } from './geometry'

/** What a whole is made of: one bar split by share, the largest parts in color and the rest together. */
const props = withDefaults(
  defineProps<{
    items: Array<{ key: string; label: string; value: number }>
    label: string
    othersLabel: string
    formatShare?: (share: number) => string
    shown?: number
  }>(),
  { shown: 5, formatShare: (share: number) => `${share.toFixed(share < 10 ? 1 : 0)} %` },
)
const parts = computed(() => {
  const positive = props.items.filter((item) => item.value > 0).sort((a, b) => b.value - a.value)
  const total = positive.reduce((sum, item) => sum + item.value, 0)
  if (!total) return []
  const head = positive.slice(0, props.shown - (positive.length > props.shown ? 1 : 0))
  const rest = positive.slice(head.length)
  const list = [
    ...head.map((item, index) => ({
      key: item.key,
      label: item.label,
      value: item.value,
      color: index,
    })),
    ...(rest.length
      ? [
          {
            key: '…',
            label: props.othersLabel,
            value: rest.reduce((sum, item) => sum + item.value, 0),
            color: -1,
          },
        ]
      : []),
  ]
  let at = 0
  return list.map((item) => {
    const share = (item.value / total) * 100
    const part = { ...item, share, x: at }
    at += share
    return part
  })
})
</script>

<template>
  <figure v-if="parts.length" class="space-y-2">
    <svg
      viewBox="0 0 100 8"
      preserveAspectRatio="none"
      class="h-2.5 w-full rounded-full"
      role="img"
      :aria-label="label"
    >
      <rect
        v-for="part in parts"
        :key="part.key"
        :x="part.x"
        y="0"
        :width="Math.max(part.share - 0.4, 0.2)"
        height="8"
        :class="part.color >= 0 ? FILLS[part.color % FILLS.length] : 'fill-muted-foreground/40'"
      />
    </svg>
    <figcaption class="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
      <span v-for="part in parts" :key="part.key" class="inline-flex items-center gap-1.5">
        <span
          :class="[
            'size-2 rounded-full',
            part.color >= 0 ? DOTS[part.color % DOTS.length] : 'bg-muted-foreground/40',
          ]"
          aria-hidden="true"
        />
        <span class="text-foreground font-medium">{{ part.label }}</span>
        <span class="tabular-nums">{{ formatShare(part.share) }}</span>
      </span>
    </figcaption>
  </figure>
</template>
