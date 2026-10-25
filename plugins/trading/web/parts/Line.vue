<script setup lang="ts">
defineProps<{ label: string; value: string }>()
</script>

<template>
  <div class="flex flex-wrap items-baseline justify-between gap-3 py-1 text-sm">
    <dt class="text-muted-foreground">{{ label }}</dt>
    <dd class="text-right font-medium tabular-nums">{{ value }}</dd>
  </div>
</template>
