<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

/** The platform's own select, styled like Input: the phone shows its native picker. */
defineOptions({ inheritAttrs: false })
const props = defineProps<{ class?: HTMLAttributes['class'] }>()
const model = defineModel<string>()
</script>

<template>
  <div class="relative">
    <select
      v-model="model"
      v-bind="$attrs"
      data-slot="native-select"
      :class="
        cn(
          'dark:bg-input/30 border-input focus-visible:border-ring focus-visible:ring-ring/50 h-11 w-full min-w-0 appearance-none truncate rounded-xl border bg-transparent py-2 pr-9 pl-3.5 text-base transition-colors outline-none focus-visible:ring-3 disabled:cursor-not-allowed disabled:opacity-50 md:pointer-fine:text-sm',
          props.class,
        )
      "
    >
      <slot />
    </select>
    <ChevronDown
      class="text-muted-foreground pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2"
    />
  </div>
</template>
