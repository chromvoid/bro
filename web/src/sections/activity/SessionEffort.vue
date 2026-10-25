<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/core/client'
import { z } from '@/core/zod'
import { useSession } from '@/stores/session'
import type { ActivitySession } from './api'
import { sessionWindow } from './running'
import WorkoutEffort from './WorkoutEffort.vue'

/** The strap's heart over a logged session: the diary says what was done, the strap how hard. */
const props = defineProps<{ session: ActivitySession }>()
const { t } = useI18n()
const account = useSession()
const StrapEffort = z.looseObject({
  effort: z
    .looseObject({
      source_name: z.string(),
      heart_rate: z.looseObject({ mean: z.number(), max: z.number() }),
      zone_seconds: z.array(z.number()),
      trimp: z.string(),
      effort: z.looseObject({ rest: z.string(), max: z.string(), basis: z.string() }),
    })
    .nullable(),
})
const range = computed(() => {
  const window = sessionWindow(props.session)
  return window
    ? { from: new Date(window.start).toISOString(), to: new Date(window.end).toISOString() }
    : null
})
const strap = useQuery({
  queryKey: computed(() => ['personal', 'effort', range.value]),
  enabled: computed(() => range.value !== null && account.can('personal.read')),
  queryFn: async ({ signal }) =>
    StrapEffort.parse(
      await api.get(`/v1/personal/effort?${new URLSearchParams(range.value!)}`, signal),
    ).effort,
})
</script>

<template>
  <section
    v-if="strap.data.value"
    class="bg-card space-y-2 rounded-2xl border p-4"
    data-testid="session-strap-effort"
  >
    <h3 class="font-medium">{{ t('activity.running.strapEffort') }}</h3>
    <p class="text-muted-foreground text-sm">{{ strap.data.value.source_name }}</p>
    <WorkoutEffort
      :heart-rate="strap.data.value.heart_rate"
      :zone-seconds="strap.data.value.zone_seconds"
      :trimp="strap.data.value.trimp"
      :effort="strap.data.value.effort"
    />
  </section>
</template>
