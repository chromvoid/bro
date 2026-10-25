<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatAmount } from '@/core/numbers'
import { duration } from '@/sections/personal/api'

/** How hard a stretch was by the strap: heart rate, the zones reached and the TRIMP load. */
const props = defineProps<{
  heartRate: { mean: number; max: number }
  zoneSeconds?: number[] | null
  trimp?: number | string | null
  effort?: { rest: string; max: string; basis: string } | null
}>()
const { t } = useI18n()
// Zones are fifths of the upper half of the heart-rate reserve; only the ones reached are listed.
const zones = computed(() =>
  (props.zoneSeconds ?? [])
    .map((seconds, index) => ({ zone: index + 1, from: 50 + index * 10, minutes: seconds / 60 }))
    .filter((item) => item.minutes >= 0.5)
    .reverse(),
)
</script>

<template>
  <p class="text-sm" data-testid="device-workout-heart-rate">
    {{ t('activity.running.heartRate', { mean: heartRate.mean, max: heartRate.max }) }}
  </p>
  <section v-if="zones.length" class="text-sm" data-testid="device-workout-zones">
    <h4 class="text-muted-foreground">{{ t('activity.running.zones') }}</h4>
    <ul>
      <li v-for="item in zones" :key="item.zone" class="flex justify-between gap-3 tabular-nums">
        <span>{{
          t('activity.running.zone', { zone: item.zone, from: item.from, to: item.from + 10 })
        }}</span>
        <span>{{ duration(item.minutes) }}</span>
      </li>
    </ul>
  </section>
  <p v-if="trimp != null" class="text-sm" data-testid="device-workout-load">
    {{ t('activity.running.load', { value: formatAmount(trimp, 0) }) }}
  </p>
  <p v-if="effort" class="text-muted-foreground text-xs">
    {{
      t('activity.running.effort', {
        rest: formatAmount(effort.rest, 0),
        max: formatAmount(effort.max, 0),
        basis: t(`activity.running.effortBasis.${effort.basis}`),
      })
    }}
  </p>
</template>
