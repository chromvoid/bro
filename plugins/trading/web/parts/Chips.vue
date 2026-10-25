<script setup lang="ts" generic="T">
import ScrollStrip from '@/components/ScrollStrip.vue'
import { SEGMENT_CHOSEN } from '@/components/segmented'

/** A single choice among a few values, as a segmented row that scrolls on phones. */
defineProps<{
  values: readonly T[]
  selected: T | null
  label: (value: T) => string
  name: string
}>()
const emit = defineEmits<{ select: [value: T] }>()
</script>

<template>
  <ScrollStrip class="max-w-full" role="radiogroup" :aria-label="name">
    <div class="bg-muted inline-flex w-max shrink-0 gap-1 rounded-xl p-1">
      <button
        v-for="(value, index) in values"
        :key="index"
        type="button"
        role="radio"
        :aria-checked="value === selected"
        :class="[
          'inline-flex h-8 items-center rounded-lg border border-transparent px-3 text-sm font-medium whitespace-nowrap transition-colors pointer-coarse:h-11',
          value === selected ? SEGMENT_CHOSEN : 'text-muted-foreground',
        ]"
        @click="emit('select', value)"
      >
        {{ label(value) }}
      </button>
    </div>
  </ScrollStrip>
</template>
