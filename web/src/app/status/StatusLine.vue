<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { cn } from '@/lib/utils'
import StatusActionButton from './StatusActionButton.vue'
import { TONE } from './tone'
import type { ShellStatus } from './useShellStatus'

/**
 * One line at the top of the content column: the most important status item, how many more wait
 * behind it, and the one action that must stay a tap away (stop a recording, end a call).
 */
const props = defineProps<{ status: ShellStatus }>()
const { t } = useI18n()
const top = computed(() => props.status.top.value)
const more = computed(() => props.status.items.value.length - 1)
const label = computed(() =>
  top.value
    ? [
        top.value.line,
        more.value > 0 ? t('status.more', { count: more.value }) : '',
        t('status.open'),
      ]
        .filter(Boolean)
        .join('. ')
    : '',
)
</script>

<template>
  <div
    v-if="top"
    class="bg-background border-b"
    data-testid="status-line"
    :data-items="status.items.value.map((item) => item.id).join(' ')"
  >
    <div class="mx-auto flex max-w-3xl items-center gap-2 px-2 md:px-6">
      <button
        type="button"
        class="hover:bg-accent focus-visible:ring-ring/50 flex min-h-11 min-w-0 flex-1 items-center gap-2 rounded-lg px-2 text-start text-sm outline-none focus-visible:ring-3"
        aria-haspopup="dialog"
        :aria-label="label"
        :data-testid="top.testid"
        @click="status.setOpen(true)"
      >
        <component
          :is="top.icon"
          :class="cn('size-5 shrink-0', TONE[top.tone])"
          aria-hidden="true"
        />
        <span class="min-w-0 flex-1 truncate font-medium">{{ top.line }}</span>
        <span
          v-if="more > 0"
          class="bg-muted text-muted-foreground shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums"
          aria-hidden="true"
          >+{{ more }}</span
        >
        <ChevronRight class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
      </button>
      <StatusActionButton v-if="top.quick" :action="top.quick" />
    </div>
    <!-- Heard once when the top item changes; the line's own words may change every moment. -->
    <p class="sr-only" role="status">{{ top.announce ?? top.line }}</p>
  </div>
</template>
