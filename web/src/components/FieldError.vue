<script setup lang="ts">
import { CircleAlert } from '@lucide/vue'

/** A field's problem in words with an icon: a red border alone says it only in colour. */
defineProps<{ id?: string }>()
</script>

<template>
  <p :id="id" class="text-destructive flex items-start gap-1.5 text-sm" role="alert">
    <CircleAlert class="mt-0.5 size-4 shrink-0" aria-hidden="true" /><span><slot /></span>
  </p>
</template>
