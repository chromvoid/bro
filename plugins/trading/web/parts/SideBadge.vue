<script setup lang="ts">
import { sideLabel } from '../labels'

/** A buy or a sell, told by color as well as by word. */
defineProps<{ side: string }>()
</script>

<template>
  <span
    :class="[
      'inline-flex h-6 shrink-0 items-center rounded-full px-2 text-xs font-medium',
      side.toLowerCase() === 'sell'
        ? 'bg-destructive/10 text-destructive'
        : 'bg-success-surface text-success',
    ]"
    >{{ sideLabel(side.toLowerCase()) }}</span
  >
</template>
