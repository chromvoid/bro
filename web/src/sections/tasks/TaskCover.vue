<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Task } from './api'
import { taskImageUrl } from './images'

/** The first picture of a task's title, small beside it in a list or on the board. */
const props = defineProps<{ task: Task }>()
const cover = computed(() => props.task.title_images?.[0] ?? null)
const failed = ref<string | null>(null)
</script>

<template>
  <img
    v-if="cover && failed !== cover.id"
    :src="taskImageUrl(task.id, cover.id)"
    alt=""
    width="40"
    height="40"
    loading="lazy"
    decoding="async"
    class="bg-muted pointer-events-none relative size-10 shrink-0 rounded-md object-cover"
    data-testid="task-cover"
    @error="failed = cover.id"
  />
</template>
