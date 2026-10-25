<script setup lang="ts">
import { useResizeObserver } from '@vueuse/core'
import { computed, ref, useTemplateRef } from 'vue'
import { line, nearest, scale, ticks, trend as smooth, type Point } from './scale'

/**
 * A single-series chart in plain SVG (no runtime styles, so it fits the CSP). The drawing has
 * the real width of its box, so dots stay round; values get a few labelled grid lines. With
 * `trend` the line is a smoothed trend and the measurements are dots, which keeps day-to-day
 * noise from looking like change; a `target` is a dashed line; `gapDays` breaks the line where
 * nothing was measured for longer.
 */
const props = withDefaults(
  defineProps<{
    points: Point[]
    label: string
    formatY: (value: number) => string
    formatX: (value: number) => string
    /** Grid labels: short, without the unit the heading already names; formatY when absent. */
    formatTick?: (value: number) => string
    height?: number
    target?: number | null
    targetLabel?: string
    trend?: boolean
    /** Independent measurements; never connect observations across an unknown signal gap. */
    pointsOnly?: boolean
    gapDays?: number
  }>(),
  {
    height: 180,
    target: null,
    targetLabel: '',
    trend: false,
    pointsOnly: false,
    gapDays: undefined,
  },
)
const DAY = 86_400_000
const frame = useTemplateRef<HTMLElement>('frame')
const width = ref(600)
useResizeObserver(frame, ([entry]) => {
  if (entry && entry.contentRect.width > 0) width.value = Math.round(entry.contentRect.width)
})

const box = computed(() => ({ width: width.value, height: props.height, padding: 12, left: 48 }))
const values = computed(() => props.points.map((point) => point.y))
// Round grid values that also hold the goal; the scale ends on the outer ones.
const grid = computed(() =>
  props.points.length
    ? ticks(
        Math.min(...values.value, props.target ?? Infinity),
        Math.max(...values.value, props.target ?? -Infinity),
      )
    : [],
)
const scaled = computed(() =>
  scale(props.points, box.value, { min: grid.value[0], max: grid.value.at(-1) }),
)
const gap = computed(() => (props.gapDays ? props.gapDays * DAY : Infinity))
const path = computed(() => {
  if (!scaled.value) return ''
  const series = props.trend ? smooth(props.points, 7 * DAY) : props.points
  return line(series, scaled.value.x, scaled.value.y, gap.value)
})

const active = ref<number | null>(null)
const focused = computed(() =>
  active.value === null ? null : (scaled.value?.points[active.value] ?? null),
)

function move(event: PointerEvent) {
  const svg = event.currentTarget as SVGSVGElement
  const bounds = svg.getBoundingClientRect()
  const left = ((event.clientX - bounds.left) / bounds.width) * width.value
  const point = scaled.value ? nearest(scaled.value.points, left) : null
  active.value = point ? scaled.value!.points.indexOf(point) : null
}

function key(event: KeyboardEvent) {
  const count = scaled.value?.points.length ?? 0
  if (!count) return
  if (event.key === 'ArrowRight') active.value = Math.min(count - 1, (active.value ?? -1) + 1)
  else if (event.key === 'ArrowLeft') active.value = Math.max(0, (active.value ?? count) - 1)
  else return
  event.preventDefault()
}
</script>

<template>
  <figure ref="frame" class="space-y-2">
    <div
      class="text-muted-foreground flex min-h-5 flex-wrap items-baseline justify-between text-sm tabular-nums"
      aria-hidden="true"
    >
      <template v-if="focused">
        <span>{{ formatX(focused.x) }}</span>
        <span class="text-foreground font-medium">{{ formatY(focused.y) }}</span>
      </template>
    </div>
    <svg
      v-if="scaled"
      :viewBox="`0 0 ${width} ${height}`"
      :width="width"
      :height="height"
      class="block w-full touch-none overflow-visible rounded-xl outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      role="img"
      tabindex="0"
      :aria-label="label"
      @pointermove="move"
      @pointerdown="move"
      @pointerleave="active = null"
      @keydown="key"
      @blur="active = null"
    >
      <g class="text-[0.6875rem]" aria-hidden="true">
        <template v-for="value in grid" :key="value">
          <line
            :x1="box.left"
            :x2="width - box.padding"
            :y1="scaled.y(value)"
            :y2="scaled.y(value)"
            class="stroke-border"
          />
          <text
            :x="box.left - 8"
            :y="scaled.y(value)"
            text-anchor="end"
            dominant-baseline="middle"
            class="fill-muted-foreground tabular-nums"
          >
            {{ (formatTick ?? formatY)(value) }}
          </text>
        </template>
        <template v-if="target !== null">
          <line
            :x1="box.left"
            :x2="width - box.padding"
            :y1="scaled.y(target)"
            :y2="scaled.y(target)"
            stroke-dasharray="6 4"
            stroke-width="1.5"
            class="stroke-chart-2"
            data-testid="chart-target"
          />
          <text
            v-if="targetLabel"
            :x="width - box.padding"
            :y="scaled.y(target) - 6"
            text-anchor="end"
            class="fill-muted-foreground"
          >
            {{ targetLabel }}
          </text>
        </template>
      </g>
      <path
        v-if="!pointsOnly"
        :d="path"
        fill="none"
        stroke-width="2.5"
        stroke-linejoin="round"
        stroke-linecap="round"
        class="stroke-chart-1"
      />
      <circle
        v-for="(point, index) in scaled.points"
        :key="`${point.x}:${index}`"
        :cx="point.left"
        :cy="point.top"
        :r="trend ? 3 : 2.5"
        :class="trend ? 'fill-chart-1/45' : 'fill-chart-1'"
      />
      <template v-if="focused">
        <line
          :x1="focused.left"
          :x2="focused.left"
          :y1="box.padding"
          :y2="height - box.padding"
          class="stroke-border"
        />
        <circle
          :cx="focused.left"
          :cy="focused.top"
          r="5"
          class="fill-background stroke-chart-1"
          stroke-width="2.5"
        />
      </template>
    </svg>
    <figcaption class="text-muted-foreground flex flex-wrap justify-between text-xs tabular-nums">
      <span>{{ points.length ? formatX(points[0]!.x) : '' }}</span>
      <span>{{ points.length > 1 ? formatX(points.at(-1)!.x) : '' }}</span>
    </figcaption>
    <!-- A block box clips its overflow; a table keeps its content width, so the hidden rows
         of a wide chart widened the page on a phone. -->
    <div class="sr-only">
      <table>
        <caption>
          {{
            label
          }}
        </caption>
        <tr v-for="(point, index) in points" :key="`${point.x}:${index}`">
          <th scope="row">{{ formatX(point.x) }}</th>
          <td>{{ formatY(point.y) }}</td>
        </tr>
      </table>
    </div>
  </figure>
</template>
