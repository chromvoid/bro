<script setup lang="ts">
import { useElementSize } from '@vueuse/core'
import { computed, ref, useTemplateRef } from 'vue'
import { linear, nearestIndex, paths, ticks, type Point } from './geometry'
import { useAxisGutter } from './gutter'
import { percent } from '../format'

/**
 * One series over time: a line with its area on a grid of round values, marks under it (money in or out),
 * and a tooltip that follows the pointer, a finger or the arrow keys. Plain SVG in measured pixels; no
 * runtime styles, so it fits the CSP.
 */
const props = withDefaults(
  defineProps<{
    points: Point[]
    label: string
    formatX: (x: number) => string
    formatY: (y: number) => string
    /** An axis label for a value on a grid of `step`. */
    formatTick: (y: number, step: number) => string
    /** Points (by index) with money moved since the previous one, and how to tell it. */
    marks?: Array<{ index: number; tone: 'up' | 'down'; text: string }>
    /** A word on how a point came about (by index), told when it is pointed at. */
    notes?: Array<string | null>
    height?: number
  }>(),
  { marks: () => [], notes: () => [], height: 180 },
)
const PAD = { top: 10, right: 8, bottom: 16, left: 44 }
const svg = useTemplateRef<SVGSVGElement>('svg')
const gutter = ref(PAD.left)
const frame = useTemplateRef<HTMLElement>('frame')
const { width } = useElementSize(frame)
const layout = computed(() => {
  const w = Math.floor(width.value)
  if (w < 80 || !props.points.length) return null
  const ys = props.points.map((point) => point.y)
  const xs = props.points.map((point) => point.x)
  const grid = ticks(Math.min(...ys), Math.max(...ys), 3)
  const bottom = props.height - PAD.bottom
  const y = linear([grid[0]!, grid.at(-1)!], [bottom, PAD.top])
  const x = linear([Math.min(...xs), Math.max(...xs)], [gutter.value, w - PAD.right])
  const placed = props.points.map((point) => ({ ...point, left: x(point.x), top: y(point.y) }))
  return {
    w,
    bottom,
    placed,
    ...paths(placed, bottom),
    grid: grid.map((value) => ({ value, top: y(value) })),
    step: grid.length > 1 ? grid[1]! - grid[0]! : 1,
    marks: props.marks
      .filter((mark) => placed[mark.index])
      .map((mark) => ({ ...mark, left: placed[mark.index]!.left })),
  }
})
const active = ref<number | null>(null)
/** The point the tooltip tells: the one pointed at, else the last. Read from the data, so the line above the
 * chart is there before the chart is measured and its height never changes on the way. */
const shown = computed(() => props.points[active.value ?? props.points.length - 1] ?? null)
const focused = computed(() =>
  active.value === null ? null : (layout.value?.placed[active.value] ?? null),
)
const markOf = computed(() => props.marks.find((mark) => mark.index === active.value) ?? null)
/** The table for screen readers, formatted once: pointing re-renders the chart, not these hundreds of dates. */
const rows = computed(() => {
  const noted = new Map(props.marks.map((mark) => [mark.index, mark.text]))
  return props.points.map((point, index) => ({
    key: point.x,
    x: props.formatX(point.x),
    y: props.formatY(point.y) + (noted.has(index) ? ` · ${noted.get(index)}` : ''),
  }))
})
/** The change from the first point to the one shown, in percent. */
const change = computed(() => {
  const first = props.points[0]?.y
  const value = shown.value?.y
  if (first === undefined || value === undefined || first === 0) return null
  return ((value - first) / Math.abs(first)) * 100
})

function move(event: PointerEvent) {
  const svg = event.currentTarget as SVGSVGElement
  const at = event.clientX - svg.getBoundingClientRect().left
  const index = nearestIndex(layout.value?.placed.map((point) => point.left) ?? [], at)
  active.value = index < 0 ? null : index
}

function key(event: KeyboardEvent) {
  const count = props.points.length
  if (!count) return
  if (event.key === 'ArrowRight') active.value = Math.min(count - 1, (active.value ?? -1) + 1)
  else if (event.key === 'ArrowLeft') active.value = Math.max(0, (active.value ?? count) - 1)
  else if (event.key === 'Escape') active.value = null
  else return
  event.preventDefault()
}
// The value labels' widths decide the gutter (gutter.ts).
useAxisGutter(gutter, svg, () =>
  (layout.value?.grid ?? [])
    .map((tick) => props.formatTick(tick.value, layout.value!.step))
    .join('|'),
)
</script>

<template>
  <figure ref="frame" class="min-w-0 space-y-1">
    <div
      class="flex min-h-5 flex-wrap items-baseline justify-between gap-x-3 text-sm tabular-nums"
      aria-hidden="true"
    >
      <span v-if="shown" class="text-muted-foreground">
        {{
          active === null
            ? `${formatX(points[0]!.x)} – ${formatX(points.at(-1)!.x)}`
            : formatX(shown.x)
        }}<template v-if="markOf"> · {{ markOf.text }}</template
        ><template v-if="active !== null && notes[active]"> · {{ notes[active] }}</template>
      </span>
      <span v-if="shown" class="font-medium">
        {{ formatY(shown.y) }}
        <span
          v-if="change !== null"
          :class="change >= 0 ? 'text-success' : 'text-destructive'"
          class="ms-1 font-normal"
          >{{ percent(change.toFixed(4)) }}</span
        >
      </span>
    </div>
    <svg
      v-if="layout"
      ref="svg"
      :height="height"
      :viewBox="`0 0 ${layout.w} ${height}`"
      class="focus-visible:ring-ring/50 block w-full touch-pan-y rounded-lg outline-none select-none focus-visible:ring-3"
      role="img"
      tabindex="0"
      :aria-label="label"
      @pointermove="move"
      @pointerdown="move"
      @pointerleave="active = null"
      @keydown="key"
      @blur="active = null"
    >
      <g v-for="tick in layout.grid" :key="tick.value">
        <line
          :x1="gutter"
          :x2="layout.w - PAD.right"
          :y1="tick.top"
          :y2="tick.top"
          class="stroke-border"
          stroke-dasharray="2 4"
        />
        <text
          data-axis-label
          :x="gutter - 6"
          :y="tick.top"
          text-anchor="end"
          dominant-baseline="middle"
          class="fill-muted-foreground text-[0.6875rem] tabular-nums"
        >
          {{ formatTick(tick.value, layout.step) }}
        </text>
      </g>
      <path :d="layout.area" class="fill-chart-1/15" />
      <path
        :d="layout.line"
        fill="none"
        stroke-width="2"
        stroke-linejoin="round"
        stroke-linecap="round"
        class="stroke-chart-1"
      />
      <path
        v-for="mark in layout.marks"
        :key="mark.index"
        :d="
          mark.tone === 'up'
            ? `M${mark.left - 4},${layout.bottom + 12} l4,-7 l4,7 z`
            : `M${mark.left - 4},${layout.bottom + 5} l4,7 l4,-7 z`
        "
        :class="mark.tone === 'up' ? 'fill-success' : 'fill-destructive'"
      />
      <template v-if="focused">
        <line
          :x1="focused.left"
          :x2="focused.left"
          :y1="PAD.top"
          :y2="layout.bottom"
          class="stroke-muted-foreground/40"
        />
        <circle
          :cx="focused.left"
          :cy="focused.top"
          r="4"
          class="fill-background stroke-chart-1"
          stroke-width="2"
        />
      </template>
    </svg>
    <svg v-else width="100%" :height="height" aria-hidden="true" />
    <!-- A block box clips its overflow; a table keeps its content width, so the hidden rows
         of a wide chart widened the page on a phone. -->
    <div class="sr-only">
      <table>
        <caption>
          {{
            label
          }}
        </caption>
        <tr v-for="row in rows" :key="row.key">
          <th scope="row">{{ row.x }}</th>
          <td>{{ row.y }}</td>
        </tr>
      </table>
    </div>
  </figure>
</template>
