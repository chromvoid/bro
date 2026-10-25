<script setup lang="ts">
import { useResizeObserver } from '@vueuse/core'
import { computed, ref, useId, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { timeIn } from '@/core/dates'
import { duration } from './api'
import {
  hourTicks,
  isStage,
  nightLanes,
  turnMarks,
  WIDTH,
  type Lane,
  type Part,
} from './sleepChart'
import { sleepInterval } from './sleep'

/**
 * One night as a hypnogram: a row per state with its total, the device's turns as marks on their
 * own row, the hours underneath. The rows are plain SVG stretched across a grid cell (no runtime
 * styles, so it fits the CSP), inset in a rounded track so every block keeps square corners and a
 * mark at the very edge stays whole; the axis, inset the same, is drawn at the real width of its
 * box, so its labels keep their shape.
 */
const props = withDefaults(
  defineProps<{
    start: string
    end: string
    parts: Part[]
    timezone: string
    today: string
    changes?: readonly string[]
  }>(),
  { changes: () => [] },
)
const { t } = useI18n()
const lanes = computed(() => nightLanes(props.start, props.end, props.parts))
const staged = computed(() => lanes.value.some((row) => isStage(row.lane)))
const ticks = computed(() => hourTicks(props.start, props.end, props.timezone))
const turns = computed(() => turnMarks(props.start, props.end, props.changes))
const name = (lane: Lane) => {
  if (lane === 'in_bed') return t('personal.sleepChart.inBed')
  if (lane === 'sleep')
    return t(staged.value ? 'personal.sleepStage.sleeping' : 'personal.metric.sleep')
  if (lane === 'unknown' || lane === 'conflict') return t(`personal.sleepAnalysis.legend.${lane}`)
  return t(`personal.sleepStage.${lane}`)
}
// Deep sleep takes the strongest hue; sleep without a stage is primary unless stages share it.
const fill = (lane: Lane) =>
  ({
    awake: 'fill-warning-fill',
    in_bed: 'fill-warning-fill/50',
    rem: 'fill-chart-3',
    light: 'fill-chart-2',
    deep: 'fill-primary',
    sleep: staged.value ? 'fill-primary/50' : 'fill-primary',
    unknown: 'fill-muted-foreground/40',
    conflict: '',
  })[lane]
const stripes = `sleep-conflict-${useId()}`

const axis = useTemplateRef<HTMLElement>('axis')
const width = ref(280)
useResizeObserver(axis, ([entry]) => {
  if (entry && entry.contentRect.width > 0) width.value = Math.round(entry.contentRect.width)
})
// Room for a time label at the axis font, so an hour never overlaps the start or the end.
const GUARD = 68
const labels = computed(() => [
  { x: 0, anchor: 'start', text: timeIn(props.timezone, props.start) },
  ...ticks.value
    .map((tick) => ({ x: (tick.x / WIDTH) * width.value, at: tick.at }))
    .filter((tick) => tick.x > GUARD && tick.x < width.value - GUARD)
    .map((tick) => ({ x: tick.x, anchor: 'middle', text: timeIn(props.timezone, tick.at) })),
  { x: width.value, anchor: 'end', text: timeIn(props.timezone, props.end) },
])
</script>

<template>
  <figure
    class="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-1.5"
    data-testid="sleep-night-chart"
  >
    <template v-for="row in lanes" :key="row.lane">
      <p class="text-xs leading-tight" :data-testid="`sleep-lane-${row.lane}`">
        <span class="block font-medium">{{ name(row.lane) }}</span>
        <span class="text-muted-foreground block tabular-nums">{{ duration(row.minutes) }}</span>
      </p>
      <div class="bg-muted h-7 rounded-md p-0.5" aria-hidden="true" data-testid="sleep-track">
        <svg
          :viewBox="`0 0 ${WIDTH} 1`"
          preserveAspectRatio="none"
          class="block size-full overflow-visible"
        >
          <defs v-if="row.lane === 'conflict'">
            <pattern :id="stripes" patternUnits="userSpaceOnUse" width="16" height="1">
              <rect width="8" height="1" class="fill-destructive" />
            </pattern>
          </defs>
          <line
            v-for="tick in ticks"
            :key="tick.at"
            :x1="tick.x"
            :x2="tick.x"
            y1="0"
            y2="1"
            class="stroke-border"
            vector-effect="non-scaling-stroke"
          />
          <rect
            v-for="part in row.parts"
            :key="part.start_at"
            :x="part.x"
            :width="part.width"
            y="0"
            height="1"
            :class="fill(row.lane)"
            :fill="row.lane === 'conflict' ? `url(#${stripes})` : undefined"
            :data-state="row.lane"
          >
            <title>
              {{ name(row.lane) }} ·
              {{ sleepInterval(part.start_at, part.end_at, timezone, today) }}
            </title>
          </rect>
        </svg>
      </div>
    </template>
    <template v-if="turns.length">
      <p class="text-xs leading-tight" data-testid="sleep-lane-turns">
        <span class="block font-medium">{{ t('personal.sleepChart.turns') }}</span>
        <span class="text-muted-foreground block tabular-nums">{{
          t('personal.sleepChart.turnCount', turns.length)
        }}</span>
      </p>
      <div class="bg-muted h-7 rounded-md p-0.5" aria-hidden="true" data-testid="sleep-track">
        <svg
          :viewBox="`0 0 ${WIDTH} 1`"
          preserveAspectRatio="none"
          class="block size-full overflow-visible"
        >
          <line
            v-for="mark in turns"
            :key="mark.at"
            :x1="mark.x"
            :x2="mark.x"
            y1="0.2"
            y2="0.8"
            class="stroke-foreground"
            stroke-width="2"
            stroke-linecap="round"
            vector-effect="non-scaling-stroke"
            data-testid="sleep-turn"
          >
            <title>{{ timeIn(timezone, mark.at) }}</title>
          </line>
        </svg>
      </div>
    </template>
    <span aria-hidden="true" />
    <div ref="axis" class="min-w-0 px-0.5" aria-hidden="true">
      <svg :viewBox="`0 0 ${width} 16`" :width="width" height="16" class="block w-full">
        <text
          v-for="label in labels"
          :key="`${label.anchor}-${label.text}`"
          :x="label.x"
          y="12"
          :text-anchor="label.anchor"
          class="fill-muted-foreground text-[0.6875rem] tabular-nums"
          data-testid="sleep-axis-label"
        >
          {{ label.text }}
        </text>
      </svg>
    </div>
  </figure>
</template>
