<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { useIntervalFn } from '@vueuse/core'
import { Timer as TimerIcon } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/core/client'
import { Timers, clock, remaining } from '@/sections/tasks/api'

/** Running timers only; the card is absent when nothing runs. */
const { t } = useI18n()
const readAt = ref(performance.now())
const now = ref(performance.now())
const running = useQuery({
  queryKey: ['timers', 'running'],
  queryFn: async ({ signal }) => {
    const result = Timers.parse(await api.get('/v1/timers/running', signal))
    readAt.value = performance.now()
    return result
  },
})
useIntervalFn(() => (now.value = performance.now()), 500)
const timers = computed(() => running.data.value?.timers ?? [])
</script>

<template>
  <RouterLink
    v-if="timers.length"
    :to="{ name: 'timers' }"
    class="bg-card hover:bg-accent/40 block space-y-2 rounded-3xl border p-5"
    data-testid="today-timers"
  >
    <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
      <TimerIcon class="size-4" />{{ t('today.timers') }}
    </h2>
    <ul class="flex flex-wrap gap-x-6 gap-y-1">
      <li v-for="timer in timers" :key="timer.id" class="tabular-nums">
        <span :class="['text-2xl font-semibold', timer.label && 'me-2']">{{
          clock(remaining(timer, readAt, now))
        }}</span>
        <!-- Digits and a word have no break between them: at large text the label takes the next
             line instead of running off the screen. The gap stays with the reading, so a label on
             its own line starts under it. -->
        <wbr v-if="timer.label" />
        <span v-if="timer.label" class="text-muted-foreground text-sm">{{ timer.label }}</span>
      </li>
    </ul>
  </RouterLink>
</template>
