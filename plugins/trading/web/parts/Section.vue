<script setup lang="ts">
/** A block of a screen, titled when the screen has more than one. */
defineProps<{ title?: string }>()
</script>

<template>
  <section class="space-y-3">
    <h2 v-if="title" class="text-base font-semibold">{{ title }}</h2>
    <slot />
  </section>
</template>
