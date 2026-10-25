<script setup lang="ts">
import { ChevronLeft } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import type { PluginHost } from '@/plugins/api'
import PriceChart, { type Level, type Trade } from '../charts/PriceChart.vue'
import SignedBars from '../charts/SignedBars.vue'
import { axis } from '../charts/geometry'
import { amount, date, dateTime, inUnit, month, percent, price } from '../format'
import { errorText, t } from '../labels'
import { CHART_RANGES, valueOf, type Trading } from '../model'
import Chips from '../parts/Chips.vue'
import CalculationIssues from '../parts/CalculationIssues.vue'
import CoreEditor from '../parts/CoreEditor.vue'
import CountSettings from '../parts/CountSettings.vue'
import Line from '../parts/Line.vue'
import ReadStatus from '../parts/ReadStatus.vue'

/**
 * One coin's position on its own page: what is held and its entry, the cycle's break-even and result, the
 * core the owner marked, and the lots, in the unit and by the method chosen for the list.
 */
const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.coin
const pnl = props.trading.pnl
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const STABLES = new Set(['USDT', 'USDC', 'FDUSD', 'USDE', 'DAI', 'TUSD'])
const summary = computed(() => {
  const value = valueOf(model.summary.value)
  return value && value.coin === model.coin.value ? value : null
})
const incomplete = computed(() => {
  const value = summary.value
  return (
    !!value &&
    [
      value.result,
      value.realized,
      value.realized_in_range,
      value.unrealized,
      value.income,
      value.income_in_range,
      value.fees,
      value.value,
    ].some((value) => value === null)
  )
})
const unit = computed(() => summary.value?.unit ?? pnl.shownUnit())
/** USD, the strategy coin, what the coin was traded against and the coin itself. */
const units = computed(() => {
  const list = valueOf(pnl.positions.value)
  return [
    ...new Set(
      [
        'USD',
        list?.strategy_coin,
        unit.value,
        summary.value?.traded_against,
        model.coin.value,
      ].filter((coin): coin is string => !!coin && !STABLES.has(coin)),
    ),
  ]
})
const negative = (value: string | null | undefined) => !!value && value.startsWith('-')
const nonZero = (value: string | null | undefined) => !!value && /[1-9]/.test(value)
const tone = (value: string | null | undefined) =>
  !nonZero(value) ? '' : negative(value) ? 'text-destructive' : 'text-success'
const money = (value: string | null | undefined, sign = false) =>
  value == null ? t('calculation_unknown') : inUnit(value, unit.value, sign)
/** A price of the coin in the unit, cents and below shown too. */
const cost = (value: string | null | undefined) =>
  value == null ? t('calculation_unknown') : price(value, unit.value)
const quantity = (value: string | null) =>
  value === null ? t('calculation_unknown') : amount(value)
const result = (value: string | null | undefined, pct?: string | null) =>
  value == null
    ? t('calculation_unknown')
    : `${money(value, true)}${pct ? ` (${percent(pct)})` : ''}`
const breakEven = computed(() => {
  const value = summary.value
  if (!value?.break_even) return t('calculation_unknown')
  return negative(value.break_even) ? t('break_even_done') : cost(value.break_even)
})
const against = computed(() => {
  const coin = summary.value?.traded_against
  return coin && !STABLES.has(coin) && coin !== unit.value ? coin : null
})
const core = computed(() => summary.value?.core ?? null)
const uncertainCore = computed(() => core.value?.qty === null || core.value?.sold_qty === null)
/** The core has fewer coins than marked: fewer were held on its day, or some left the exchange since. */
const partial = computed(
  () => core.value?.qty != null && Number(core.value.qty) < Number(core.value.asked),
)
const editingCore = ref(false)
watch(
  () => model.coin.value,
  () => (editingCore.value = false),
)
const saveCore = async (qty: string | null, since: string | null = null) => {
  if (summary.value && (await pnl.setCore(summary.value.coin, qty, since)))
    editingCore.value = false
}
const chart = computed(() => {
  const value = valueOf(model.chart.value)
  return value && value.coin === model.coin.value ? value : null
})
/** A price of the chart in the unit, rounded as prices in it are shown. */
const shown = (value: number) => cost(value.toFixed(unit.value === 'USD' ? 12 : 8))
const series = computed(() => {
  const value = chart.value
  if (!value) return null
  const coin = value.coin
  const points = value.points
    .map((point) => ({ x: Date.parse(point.time), y: Number(point.price) }))
    .filter((point) => Number.isFinite(point.x) && Number.isFinite(point.y))
  const trades: Trade[] = value.trades.map((trade) => ({
    x: Date.parse(trade.time),
    y: Number(trade.price),
    side: trade.side === 'sell' ? 'sell' : 'buy',
    text: t(
      trade.side === 'sell' ? 'chart_sold' : 'chart_bought',
      `${amount(trade.qty)} ${coin}`,
      cost(trade.price),
    ),
  }))
  const levels: Level[] = []
  const add = (key: string, raw: string | null | undefined, label: string, tone: Level['tone']) => {
    if (raw && !negative(raw)) levels.push({ key, value: Number(raw), label, tone })
  }
  add('entry', value.lines.avg_entry, t('level_entry'), 'entry')
  add('even', value.lines.break_even, t('level_even'), 'even')
  add('core', value.lines.core_entry, t('level_core'), 'core')
  return { points, trades, levels }
})
const months = computed(() =>
  (summary.value?.realized_by_month ?? []).map((item) => ({
    key: item.month,
    tick: month(item.month),
    label: month(item.month, true),
    parts: [
      {
        value: item.realized === null ? null : Number(item.realized),
        text: result(item.realized),
      },
    ],
  })),
)
const refresh = () => void model.refresh()
const back = () => props.host.navigate({ name: 'pnl', ids: {} })
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <Button variant="ghost" size="sm" class="-ms-2" @click="back">
        <ChevronLeft class="size-4" />{{ t('back_positions') }}
      </Button>
      <CountSettings :model="pnl" :units="units" />
    </div>
    <p v-if="pnl.saveError.value && !editingCore" class="text-destructive text-sm" role="alert">
      {{ errorText(pnl.saveError.value) }}
    </p>
    <ReadStatus :model="model.summary.value" :host="host" @retry="refresh" />
    <CalculationIssues v-if="summary" :issues="summary.issues" :missing="incomplete" />
    <div v-if="summary" class="grid grid-cols-1 gap-4 md:grid-cols-2" data-testid="pnl">
      <header class="flex flex-wrap items-end justify-between gap-3 md:col-span-2">
        <div>
          <h2 class="text-3xl font-semibold tracking-tight">
            {{ summary.coin }}
          </h2>
          <p class="text-muted-foreground text-sm tabular-nums">
            {{ t('held') }}: {{ amount(summary.open_qty) }} {{ summary.coin }} ·
            {{ money(summary.value) }}
          </p>
          <p v-if="against" class="text-muted-foreground text-sm">
            {{ t('traded_against', against) }} ·
            <button
              type="button"
              class="text-primary underline-offset-4 hover:underline"
              @click="pnl.count(against)"
            >
              {{ t('show_in', against) }}
            </button>
          </p>
        </div>
        <div class="text-right">
          <p class="text-muted-foreground text-sm">{{ t('coin_result') }}</p>
          <p :class="['text-2xl font-semibold tabular-nums', tone(summary.result)]">
            {{ result(summary.result) }}
          </p>
        </div>
      </header>

      <section
        class="bg-card space-y-3 rounded-3xl border p-5 md:col-span-2"
        data-testid="price-chart"
      >
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h3 class="font-semibold">{{ t('price_chart') }}</h3>
          <Chips
            :values="CHART_RANGES"
            :selected="model.range.value"
            :label="(range) => t(range.label)"
            :name="t('price_chart')"
            @select="(range) => model.show(range)"
          />
        </div>
        <p v-if="unit === summary.coin" class="text-muted-foreground text-sm">
          {{ t('chart_same_unit', summary.coin) }}
        </p>
        <template v-else>
          <ReadStatus :model="model.chart.value" :host="host" shape="chart" @retry="refresh" />
          <CalculationIssues v-if="chart" :issues="chart.issues" />
          <p v-if="series && series.points.length < 2" class="text-muted-foreground text-sm">
            {{ t('chart_empty') }}
          </p>
          <PriceChart
            v-else-if="series"
            :points="series.points"
            :trades="series.trades"
            :levels="series.levels"
            :label="t('price_chart')"
            :format-x="(x) => dateTime(new Date(x).toISOString(), zone)"
            :format-y="shown"
            :format-tick="(y, step) => axis(y, step)"
          />
          <p class="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
            <span><span class="text-success">▲</span> {{ t('legend_buy') }}</span>
            <span><span class="text-destructive">▼</span> {{ t('legend_sell') }}</span>
            <span v-for="level in series?.levels ?? []" :key="level.key">
              <span
                :class="
                  level.tone === 'entry'
                    ? 'text-chart-3'
                    : level.tone === 'even'
                      ? 'text-chart-2'
                      : 'text-chart-4'
                "
                >- - -</span
              >
              {{ level.label }} {{ shown(level.value) }}
            </span>
          </p>
        </template>
      </section>

      <section class="bg-card space-y-3 rounded-3xl border p-5">
        <div>
          <p class="text-muted-foreground text-sm">{{ t('avg_entry') }}</p>
          <p class="text-3xl font-semibold tracking-tight tabular-nums">
            {{ cost(summary.avg_entry) }}
          </p>
          <p class="text-muted-foreground text-sm">{{ t('avg_entry_hint') }}</p>
        </div>
        <dl class="divide-y">
          <Line :label="t('mark_price')" :value="cost(summary.mark)" />
          <Line :label="t('break_even')" :value="breakEven" />
          <p
            v-if="summary.cycle_started_at && summary.break_even"
            class="text-muted-foreground pb-1 text-xs"
          >
            {{ t('break_even_since', dateTime(summary.cycle_started_at, zone)) }}
          </p>
          <Line
            v-if="summary.realized_in_cycle != null"
            :label="t('realized_cycle')"
            :value="result(summary.realized_in_cycle)"
          />
          <Line :label="t('avg_buy')" :value="cost(summary.avg_buy)" />
        </dl>
      </section>

      <section class="bg-card space-y-3 rounded-3xl border p-5">
        <p class="text-muted-foreground text-sm">{{ t('unrealized') }}</p>
        <p
          :class="['text-3xl font-semibold tracking-tight tabular-nums', tone(summary.unrealized)]"
        >
          {{ result(summary.unrealized, summary.unrealized_pct) }}
        </p>
        <dl class="divide-y">
          <Line :label="t('realized_range')" :value="result(summary.realized_in_range)" />
          <Line :label="t('realized')" :value="result(summary.realized)" />
          <Line
            v-if="summary.income === null || nonZero(summary.income)"
            :label="t('income')"
            :value="result(summary.income)"
          />
          <Line :label="t('fees')" :value="money(summary.fees)" />
        </dl>
      </section>

      <section
        v-if="months.length"
        class="bg-card space-y-3 rounded-3xl border p-5 md:col-span-2"
        data-testid="coin-months"
      >
        <h3 class="font-semibold">{{ t('realized_by_month') }}</h3>
        <p class="text-muted-foreground text-sm">
          {{ t(pnl.period.value.label) }}
        </p>
        <SignedBars
          :columns="months"
          :label="t('realized_by_month')"
          :idle="t('pnl_months_hint')"
          :format-tick="(y, step) => axis(y, step)"
        />
      </section>

      <section class="bg-card space-y-2 rounded-3xl border p-5 md:col-span-2" data-testid="core">
        <template v-if="core">
          <p class="font-medium">{{ t('core_title') }}</p>
          <dl class="divide-y">
            <Line
              :label="t('core_held')"
              :value="
                t('core_line', `${quantity(core.qty)} ${summary.coin}`, date(core.since, zone))
              "
            />
            <Line :label="t('core_entry')" :value="cost(core.entry)" />
            <Line :label="t('core_unrealized')" :value="result(core.unrealized)" />
            <Line
              v-if="core.sold_qty === null || nonZero(core.sold_qty)"
              :label="
                core.sold_qty === null
                  ? t('core_sold_unknown')
                  : t(
                      'core_sold',
                      `${quantity(core.sold_qty)} ${summary.coin}`,
                      cost(core.sold_price),
                    )
              "
              :value="result(core.sold_unrealized)"
            />
          </dl>
          <p v-if="partial" class="text-warning text-xs">
            {{ t('core_partial', `${amount(core.qty)} ${summary.coin}`) }}
          </p>
        </template>
        <p v-else class="text-muted-foreground text-sm">{{ t('core_none') }}</p>
        <p v-if="uncertainCore" class="text-warning text-sm">
          {{ t('core_allocation_unknown') }}
        </p>
        <CoreEditor
          v-if="editingCore"
          :coin="summary.coin"
          :held="summary.open_qty"
          :method="pnl.shownMethod()"
          :core="core"
          :busy="pnl.saving.value"
          :error="pnl.saveError.value"
          @save="(qty, since) => saveCore(qty, since)"
          @cancel="editingCore = false"
        />
        <div v-else-if="pnl.canSetCore.value" class="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" @click="editingCore = true">
            {{ core ? t('core_edit') : t('core_mark') }}
          </Button>
          <Button
            v-if="core"
            variant="ghost"
            size="sm"
            :disabled="pnl.saving.value"
            @click="saveCore(null)"
          >
            {{ t('core_remove') }}
          </Button>
        </div>
      </section>

      <div class="text-muted-foreground space-y-1 text-sm md:col-span-2">
        <p v-if="summary.opening && nonZero(summary.opening.qty) && !negative(summary.opening.qty)">
          {{
            t(
              'opening_note',
              dateTime(summary.opening.at, zone),
              `${amount(summary.opening.qty)} ${summary.coin}`,
            )
          }}
        </p>
        <p v-if="summary.opening && negative(summary.opening.qty)" class="text-warning">
          {{ t('opening_negative', dateTime(summary.opening.at, zone)) }}
        </p>
        <p v-if="nonZero(summary.unexplained_qty)" class="text-warning">
          {{ t('unexplained_note', `${amount(summary.unexplained_qty)} ${summary.coin}`) }}
        </p>
        <p v-if="nonZero(summary.short_qty)" class="text-warning">
          {{ t('short_note', `${amount(summary.short_qty)} ${summary.coin}`) }}
        </p>
        <p v-if="summary.unpriced_events" class="text-warning">
          {{ t('unpriced_note', String(summary.unpriced_events)) }}
        </p>
      </div>
      <details
        v-if="summary.lots.length && !uncertainCore"
        class="bg-card rounded-3xl border md:col-span-2"
        data-testid="position-lots"
      >
        <summary class="cursor-pointer px-5 py-3 text-sm font-medium">
          {{ t('lots_title', String(summary.lots.length), String(summary.lots_total)) }}
        </summary>
        <ul class="text-muted-foreground space-y-1 border-t px-5 py-3 text-sm tabular-nums">
          <li v-for="(lot, index) in summary.lots" :key="index">
            {{
              t(
                lot.core ? 'core_lot' : 'lot_line',
                `${amount(lot.qty)} ${summary.coin}`,
                cost(lot.unit_cost),
              )
            }}
          </li>
        </ul>
      </details>
    </div>
  </div>
</template>
