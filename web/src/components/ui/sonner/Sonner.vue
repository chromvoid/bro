<script lang="ts" setup>
import type { ToasterProps } from 'vue-sonner'

import {
  CircleCheckIcon,
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
  XIcon,
} from '@lucide/vue'
import { reactiveOmit } from '@vueuse/core'
import { Toaster as Sonner } from 'vue-sonner'
import { cn } from '@/lib/utils'

const props = defineProps<ToasterProps>()
const delegatedProps = reactiveOmit(props, 'class', 'toastOptions')
</script>

<template>
  <Sonner
    :class="cn('toaster group', props.class)"
    :style="{
      '--normal-bg': 'var(--popover)',
      '--normal-bg-hover': 'var(--accent)',
      '--normal-text': 'var(--popover-foreground)',
      '--normal-border': 'var(--border)',
      '--normal-border-hover': 'var(--input)',
      '--success-bg': 'var(--success-surface)',
      '--success-border': 'var(--toast-success-border)',
      '--success-text': 'var(--success)',
      '--info-bg': 'var(--accent)',
      '--info-border': 'var(--toast-info-border)',
      '--info-text': 'var(--accent-foreground)',
      '--warning-bg': 'var(--warning-surface)',
      '--warning-border': 'var(--toast-warning-border)',
      '--warning-text': 'var(--warning)',
      '--error-bg': 'var(--destructive-surface)',
      '--error-border': 'var(--toast-error-border)',
      '--error-text': 'var(--destructive)',
      '--border-radius': 'var(--radius)',
      '--gray1': 'var(--popover)',
      '--gray2': 'var(--toast-background)',
      '--gray3': 'var(--border)',
      '--gray4': 'var(--border)',
      '--gray5': 'var(--border)',
      '--gray6': 'var(--border)',
      '--gray7': 'var(--border)',
      '--gray8': 'var(--border)',
      '--gray9': 'var(--muted-foreground)',
      '--gray10': 'var(--muted-foreground)',
      '--gray11': 'var(--muted-foreground)',
      '--gray12': 'var(--popover-foreground)',
    }"
    :toast-options="
      props.toastOptions ?? {
        classes: {
          toast: 'rounded-2xl',
        },
      }
    "
    v-bind="delegatedProps"
  >
    <template #success-icon>
      <CircleCheckIcon class="size-4" />
    </template>
    <template #info-icon>
      <InfoIcon class="size-4" />
    </template>
    <template #warning-icon>
      <TriangleAlertIcon class="size-4" />
    </template>
    <template #error-icon>
      <OctagonXIcon class="size-4" />
    </template>
    <template #loading-icon>
      <div>
        <Loader2Icon class="size-4 animate-spin" />
      </div>
    </template>
    <template #close-icon>
      <XIcon class="size-4" />
    </template>
  </Sonner>
</template>

<style>
/* Keep vendor defaults out of the app palette, including colors without Sonner variables. */
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true'] {
  box-shadow: var(--toast-shadow);
}
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true']:focus-visible,
.toaster[data-sonner-toaster]
  [data-sonner-toast][data-styled='true']
  [data-close-button]:focus-visible {
  box-shadow: var(--toast-focus-shadow);
}
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true'] [data-button]:focus-visible {
  box-shadow: var(--toast-action-focus-shadow);
}
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true'] [data-description] {
  color: var(--muted-foreground);
}
.toaster[data-sonner-toaster]
  [data-sonner-toast][data-styled='true'][data-rich-colors='true']
  [data-description] {
  color: inherit;
}
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true'] [data-cancel] {
  color: var(--secondary-foreground);
  background: var(--secondary);
}
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true'] [data-close-button] {
  color: inherit;
}
.toaster[data-sonner-toaster] [data-sonner-toast][data-styled='true'][data-invert='true'] {
  --normal-bg: var(--foreground);
  --normal-text: var(--background);
  --normal-border: var(--muted-foreground);
}
.toaster[data-sonner-toaster]
  [data-sonner-toast][data-styled='true'][data-invert='true']
  [data-description] {
  color: inherit;
}
</style>
