<script setup lang="ts">
import type { Component } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * An action in a page header: an icon with a label for screen readers, so the header keeps one
 * row on a phone. The screen's main action is `primary`: filled, its label shown from md up.
 * Attributes (aria-expanded, data-testid, disabled, listeners) reach the button itself.
 */
const props = defineProps<{
  icon: Component
  label: string
  to?: RouteLocationRaw
  primary?: boolean
  /** The icon turns while the action runs (a refresh). */
  spin?: boolean
}>()
const classes = () =>
  cn(props.primary && 'size-11 px-0 md:size-auto md:px-4', !props.primary && 'shrink-0')
</script>

<template>
  <Button
    :as-child="!!to"
    :variant="primary ? 'default' : 'ghost'"
    :size="primary ? 'default' : 'icon'"
    :class="classes()"
    :title="label"
  >
    <RouterLink v-if="to" :to="to">
      <component :is="icon" :class="cn('size-5', spin && 'animate-spin')" aria-hidden="true" />
      <span :class="primary ? 'sr-only md:not-sr-only' : 'sr-only'">{{ label }}</span>
    </RouterLink>
    <template v-else>
      <component :is="icon" :class="cn('size-5', spin && 'animate-spin')" aria-hidden="true" />
      <span :class="primary ? 'sr-only md:not-sr-only' : 'sr-only'">{{ label }}</span>
    </template>
  </Button>
</template>
