<script setup lang="ts">
import { MessageCircle } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import type { Task } from './api'

defineProps<{ discussion: Task['discussion'] }>()
const emit = defineEmits<{ open: [] }>()
const { t } = useI18n()
</script>

<template>
  <button
    v-if="discussion && discussion.total > 0"
    type="button"
    :class="[
      'pointer-events-auto relative inline-flex min-h-8 items-center gap-1 rounded-full px-2 text-xs tabular-nums outline-none focus-visible:ring-2 focus-visible:ring-ring pointer-coarse:min-h-11',
      discussion.unread
        ? 'bg-accent text-accent-foreground font-semibold'
        : 'bg-muted text-muted-foreground',
    ]"
    :aria-label="
      t(
        discussion.unread ? 'tasks.discussionBadge.unread' : 'tasks.discussionBadge.messages',
        discussion,
      )
    "
    data-task-discussion
    @click.stop="emit('open')"
    @pointerdown.stop
    @mousedown.stop
    @touchstart.stop
  >
    <MessageCircle class="size-3.5" aria-hidden="true" />
    <span aria-hidden="true">{{ discussion.total }}</span>
    <span v-if="discussion.unread" class="bg-primary size-1.5 rounded-full" aria-hidden="true" />
  </button>
</template>
