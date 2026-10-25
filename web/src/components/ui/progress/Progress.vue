<script setup lang="ts">
import type { ProgressRootProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { ProgressIndicator, ProgressRoot } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<
    ProgressRootProps & {
      class?: HTMLAttributes['class']
      /**
       * `warning`: past a limit, worth noticing; `success`: a minimum reached (protein), which is
       * good news and looks like it.
       */
      tone?: 'default' | 'warning' | 'success'
    }
  >(),
  { modelValue: 0, tone: 'default' },
)

const delegatedProps = reactiveOmit(props, 'class', 'tone')
</script>

<template>
  <ProgressRoot
    data-slot="progress"
    v-bind="delegatedProps"
    :class="
      cn(
        'bg-border h-1 rounded-full relative flex w-full items-center overflow-x-hidden',
        props.class,
      )
    "
  >
    <ProgressIndicator
      data-slot="progress-indicator"
      :data-tone="tone"
      :class="
        cn(
          'size-full flex-1 transition-all',
          tone === 'warning' ? 'bg-warning-fill' : tone === 'success' ? 'bg-success' : 'bg-primary',
        )
      "
      :style="`transform: translateX(-${100 - (props.modelValue ?? 0)}%);`"
    />
  </ProgressRoot>
</template>
