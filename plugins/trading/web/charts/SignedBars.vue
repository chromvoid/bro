<script setup lang="ts">
import { useElementSize } from '@vueuse/core'
import { computed, ref, useTemplateRef } from 'vue'
import { linear, ticks } from './geometry'
import { useAxisGutter } from './gutter'

/**
 * Columns around zero, one per period: gains up, losses (or money out) down, parts of a column stacked.
 * The tooltip names the period and its parts; the arrow keys move through the columns.
 */
export interface Column {
  key: string
  /** Under the axis, short («сент»). */
  tick: string
  /** In the tooltip and the table, whole («сентябрь 2025»). */
  label: string
  /** Signed parts: positive ones stack up from zero, negative ones down. */
  parts: Array<{ value: number | null; text: string }>
}
const props = withDefaults(
  defineProps<{
    columns: Column[]
    label: string
    /** What the line above the columns says while none is pointed at. */
    idle?: string
    /** An axis label for a value on a grid of `step`. */
    formatTick: (y: number, step: number) => string
    height?: number
  }>(),
  { height: 140, idle: '' },
)
const PAD = { top: 8, right: 4, bottom: 18, left: 44 }
const svg = useTemplateRef<SVGSVGElement>('svg')
const gutter = ref(PAD.left)
const frame = useTemplateRef<HTMLElement>('frame')
const { width } = useElementSize(frame)
const unknown = computed(() =>
  props.columns.filter((column) => column.parts.some((part) => part.value === null)),
)
const layout = computed(() => {
  const w = Math.floor(width.value)
  const values = props.columns.map((column) =>
    column.parts.flatMap((part) => (part.value === null ? [] : [part.value])),
  )
  if (w < 80 || !values.some((parts) => parts.length)) return null
  const up = values.map((parts) => parts.reduce((sum, value) => sum + Math.max(value, 0), 0))
  const down = values.map((parts) => parts.reduce((sum, value) => sum + Math.min(value, 0), 0))
  const grid = ticks(Math.min(0, ...down), Math.max(0, ...up), 3)
  const y = linear([grid[0]!, grid.at(-1)!], [props.height - PAD.bottom, PAD.top])
  const slot = (w - gutter.value - PAD.right) / props.columns.length
  const bar = Math.max(2, Math.min(28, slot * 0.66))
  const bars = props.columns.map((column, index) => {
    const left = gutter.value + slot * index + (slot - bar) / 2
    let above = 0
    let below = 0
    const rects = column.parts
      .filter(
        (part): part is { value: number; text: string } => part.value !== null && part.value !== 0,
      )
      .map((part) => {
        const from = part.value > 0 ? above : below
        const to = from + part.value
        if (part.value > 0) above = to
        else below = to
        const [top, bottom] = [y(Math.max(from, to)), y(Math.min(from, to))]
        return { top, height: Math.max(1, bottom - top), up: part.value > 0 }
      })
    return { key: column.key, left, rects, center: left + bar / 2 }
  })
  return {
    w,
    bar,
    slot,
    bars,
    zero: y(0),
    grid: grid.map((value) => ({ value, top: y(value) })),
    step: grid.length > 1 ? grid[1]! - grid[0]! : 1,
  }
})
const active = ref<number | null>(null)
const focused = computed(() => (active.value === null ? null : props.columns[active.value]))
/** Which column labels fit under the axis: every one, or every n-th. */
const every = computed(() => (layout.value ? Math.ceil(36 / layout.value.slot) : 1))

function move(event: PointerEvent) {
  if (!layout.value) return
  const svg = event.currentTarget as SVGSVGElement
  const at = event.clientX - svg.getBoundingClientRect().left - gutter.value
  const index = Math.floor(at / layout.value.slot)
  active.value = index >= 0 && index < props.columns.length ? index : null
}

function key(event: KeyboardEvent) {
  const count = props.columns.length
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
      class="text-muted-foreground flex min-h-5 flex-wrap items-baseline gap-x-3 text-sm tabular-nums"
      aria-hidden="true"
    >
      <template v-if="focused">
        <span>{{ focused.label }}</span>
        <span v-for="(part, index) in focused.parts" :key="index" class="text-foreground">
          {{ part.text }}
        </span>
      </template>
      <span v-else>{{ idle || '\u00a0' }}</span>
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
          :class="tick.value === 0 ? 'stroke-muted-foreground/50' : 'stroke-border'"
          :stroke-dasharray="tick.value === 0 ? undefined : '2 4'"
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
      <g v-for="(bar, index) in layout.bars" :key="bar.key">
        <rect
          v-if="active === index"
          :x="gutter + layout.slot * index"
          :y="PAD.top"
          :width="layout.slot"
          :height="height - PAD.top - PAD.bottom"
          class="fill-muted"
        />
        <rect
          v-for="(rect, part) in bar.rects"
          :key="part"
          :x="bar.left"
          :y="rect.top"
          :width="layout.bar"
          :height="rect.height"
          rx="2"
          :class="rect.up ? 'fill-success' : 'fill-destructive'"
        />
        <text
          v-if="index % every === 0"
          :x="bar.center"
          :y="height - 4"
          text-anchor="middle"
          class="fill-muted-foreground text-[0.6875rem]"
        >
          {{ columns[index]!.tick }}
        </text>
      </g>
    </svg>
    <svg v-else width="100%" :height="height" aria-hidden="true" />
    <p v-if="unknown.length" class="text-warning text-xs" data-testid="chart-unknown">
      {{
        unknown
          .map(
            (column) =>
              `${column.label}: ${column.parts
                .filter((part) => part.value === null)
                .map((part) => part.text)
                .join(', ')}`,
          )
          .join(' · ')
      }}
    </p>
    <!-- A block box clips its overflow; a table keeps its content width, so the hidden rows
         of a wide chart widened the page on a phone. -->
    <div class="sr-only">
      <table>
        <caption>
          {{
            label
          }}
        </caption>
        <tr v-for="column in columns" :key="column.key">
          <th scope="row">{{ column.label }}</th>
          <td>{{ column.parts.map((part) => part.text).join(', ') }}</td>
        </tr>
      </table>
    </div>
  </figure>
</template>
