<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed } from 'vue'
import { Button } from '@/components/ui/button'
import type { Freshness } from '../contracts'
import { isStale, moment } from '../format'
import { connectionLabel, t } from '../labels'

/** How current the shown data is, in one line: live, updated at, or stale (with the connection's trouble), and a refresh. */
const props = defineProps<{
  freshness: Freshness | null
  live: boolean
  busy: boolean
  now: number
}>()
const emit = defineEmits<{ refresh: [] }>()
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const state = computed(() => {
  const value = props.freshness
  if (!value) return { tone: 'idle', text: t('fresh_none') }
  const at = moment(value.as_of, zone, props.now)
  if (isStale(value, props.now)) {
    const trouble =
      value.connection_state === 'active' ? '' : ` · ${connectionLabel(value.connection_state)}`
    return { tone: 'stale', text: `${t('fresh_stale', at)}${trouble}` }
  }
  return props.live
    ? { tone: 'live', text: t('fresh_live', at) }
    : { tone: 'idle', text: t('fresh_at', at) }
})
</script>

<template>
  <div class="text-muted-foreground flex items-center gap-1 text-xs" data-testid="freshness">
    <span
      :class="[
        'size-2 shrink-0 rounded-full',
        state.tone === 'live'
          ? 'bg-success'
          : state.tone === 'stale'
            ? 'bg-warning-fill'
            : 'bg-muted-foreground/50',
      ]"
      aria-hidden="true"
    />
    <span :class="state.tone === 'stale' ? 'text-warning' : ''" role="status">{{
      state.text
    }}</span>
    <Button
      variant="ghost"
      size="icon"
      class="size-8"
      :aria-label="t('refresh')"
      @click="emit('refresh')"
    >
      <RefreshCw :class="['size-4', busy ? 'animate-spin' : '']" />
    </Button>
  </div>
</template>
