<script setup lang="ts">
import type { Component } from 'vue'
import BrandMark from '@/components/BrandMark.vue'

/**
 * `brand` is for the good kind of empty (nothing needs attention, nothing left for today): Bro
 * smiles in place of the grey icon tile. «No data yet» keeps its icon.
 */
defineProps<{ title: string; description?: string; icon?: Component; brand?: boolean }>()
</script>

<template>
  <div class="flex flex-col items-center gap-3 px-6 py-12 text-center" data-state="empty">
    <BrandMark v-if="brand" mood="ok" class="size-12" data-testid="empty-brand" />
    <div
      v-else-if="icon"
      class="bg-muted text-muted-foreground grid size-12 place-items-center rounded-2xl"
    >
      <component :is="icon" class="size-6" />
    </div>
    <div class="max-w-sm space-y-1">
      <h2 class="text-lg font-semibold">{{ title }}</h2>
      <p v-if="description" class="text-muted-foreground">{{ description }}</p>
    </div>
    <slot />
  </div>
</template>
