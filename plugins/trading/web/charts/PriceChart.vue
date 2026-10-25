<script setup lang="ts">
import { useElementSize } from '@vueuse/core'
import { computed, ref, useTemplateRef } from 'vue'
import { linear, nearestIndex, ticks, type Point } from './geometry'
import { useAxisGutter } from './gutter'

/**
 * A coin's price over time with its buys (up, below the price) and sells (down, above it), and the prices the
 * position is judged by as dashed lines. The tooltip names the moment, the price and the trades of that stretch.
 */
export interface Trade {
  x: number
  y: number
  side: 'buy' | 'sell'
  text: string
}
export interface Level {
  key: string
  value: number
  label: string
  tone: 'entry' | 'even' | 'core'
}
const props = withDefaults(
  defineProps<{
    points: Point[]
    trades: Trade[]
    levels: Level[]
    label: string
    formatX: (x: number) => string
    formatY: (y: number) => string
    formatTick: (y: number, step: number) => string
    height?: number
  }>(),
  { height: 220 },
)
const PAD = { top: 12, right: 8, bottom: 16, left: 52 }
const svg = useTemplateRef<SVGSVGElement>('svg')
const gutter = ref(PAD.left)
// The dashed line carries the level's colour; its label names it in text colour, because chart
// colours are marks (3:1), not text (4.5:1).
const TONES = {
  entry: 'stroke-chart-3',
  even: 'stroke-chart-2',
  core: 'stroke-chart-4',
} as const
const frame = useTemplateRef<HTMLElement>('frame')
const { width } = useElementSize(frame)
const layout = computed(() => {
  const w = Math.floor(width.value)
  if (w < 80 || props.points.length < 2) return null
  const prices = [...props.points.map((point) => point.y), ...props.trades.map((trade) => trade.y)]
  const [low, high] = [Math.min(...prices), Math.max(...prices)]
  // A level far off the prices (a break-even below zero) would flatten the line: it is left out.
  const near = props.levels.filter(
    (level) => level.value > low - (high - low) && level.value < high + (high - low),
  )
  const values = [...prices, ...near.map((level) => level.value)]
  const grid = ticks(Math.min(...values), Math.max(...values), 4)
  const bottom = props.height - PAD.bottom
  const y = linear([grid[0]!, grid.at(-1)!], [bottom, PAD.top])
  const xs = props.points.map((point) => point.x)
  const x = linear([Math.min(...xs), Math.max(...xs)], [gutter.value, w - PAD.right])
  const placed = props.points.map((point) => ({ ...point, left: x(point.x), top: y(point.y) }))
  const line = placed
    .map((point, index) => `${index ? 'L' : 'M'}${point.left.toFixed(1)},${point.top.toFixed(1)}`)
    .join(' ')
  return {
    w,
    bottom,
    placed,
    line,
    step: grid.length > 1 ? grid[1]! - grid[0]! : 1,
    grid: grid.map((value) => ({ value, top: y(value) })),
    levels: near.map((level) => ({ ...level, top: y(level.value) })),
    trades: props.trades.map((trade) => ({ ...trade, left: x(trade.x), top: y(trade.y) })),
    half: placed.length > 1 ? (placed[1]!.left - placed[0]!.left) / 2 : 4,
  }
})
const active = ref<number | null>(null)
const focused = computed(() =>
  active.value === null ? null : (layout.value?.placed[active.value] ?? null),
)
const shown = computed(() => props.points[active.value ?? props.points.length - 1] ?? null)
/** The trades for screen readers, formatted once rather than on every move of the pointer. */
const rows = computed(() =>
  props.trades.map((trade) => ({
    key: `${trade.x}-${trade.side}`,
    x: props.formatX(trade.x),
    text: trade.text,
  })),
)
/** The trades of the stretch pointed at. */
const near = computed(() => {
  const point = focused.value
  if (!point || !layout.value) return []
  return layout.value.trades.filter(
    (trade) => Math.abs(trade.left - point.left) <= layout.value!.half,
  )
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
      class="flex min-h-10 flex-wrap items-baseline justify-between gap-x-3 text-sm tabular-nums"
      aria-hidden="true"
    >
      <span v-if="shown" class="text-muted-foreground">
        {{
          active === null
            ? `${formatX(points[0]!.x)} – ${formatX(points.at(-1)!.x)}`
            : formatX(shown.x)
        }}
      </span>
      <span v-if="shown" class="font-medium">{{ formatY(shown.y) }}</span>
      <span class="text-muted-foreground w-full truncate text-xs">
        {{ near.map((trade) => trade.text).join(' · ') || ' ' }}
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
      <g v-for="level in layout.levels" :key="level.key">
        <line
          :x1="gutter"
          :x2="layout.w - PAD.right"
          :y1="level.top"
          :y2="level.top"
          :class="TONES[level.tone]"
          stroke-width="1.5"
          stroke-dasharray="6 4"
        />
        <text
          :x="layout.w - PAD.right - 2"
          :y="level.top - 4"
          text-anchor="end"
          class="fill-muted-foreground text-[0.6875rem] font-medium"
        >
          {{ level.label }}
        </text>
      </g>
      <path
        :d="layout.line"
        fill="none"
        stroke-width="1.75"
        stroke-linejoin="round"
        stroke-linecap="round"
        class="stroke-foreground/70"
      />
      <path
        v-for="(trade, index) in layout.trades"
        :key="index"
        :d="
          trade.side === 'buy'
            ? `M${trade.left - 5},${trade.top + 11} l5,-8 l5,8 z`
            : `M${trade.left - 5},${trade.top - 11} l5,8 l5,-8 z`
        "
        :class="trade.side === 'buy' ? 'fill-success' : 'fill-destructive'"
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
          class="fill-background stroke-foreground"
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
          <td>{{ row.text }}</td>
        </tr>
      </table>
    </div>
  </figure>
</template>
