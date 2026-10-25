<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { computed, ref } from 'vue'
import type { PluginHost } from '@/plugins/api'
import RankBars from '../charts/RankBars.vue'
import SignedBars from '../charts/SignedBars.vue'
import { axis } from '../charts/geometry'
import type { PositionItem } from '../contracts'
import { inUnit, month, percent, price } from '../format'
import { errorText, t } from '../labels'
import { PERIODS, valueOf, type Trading } from '../model'
import Chips from '../parts/Chips.vue'
import CalculationIssues from '../parts/CalculationIssues.vue'
import CountSettings from '../parts/CountSettings.vue'
import ReadStatus from '../parts/ReadStatus.vue'

/**
 * Positions per coin on all accounts, counted in USD or a coin (the strategy coin by default), a sale closing
 * the latest or the oldest purchases: realized results for the chosen period, then current positions and
 * lifetime results, each coin opening its own page.
 */
const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.pnl
const STABLES = new Set(['USDT', 'USDC', 'FDUSD', 'USDE', 'DAI', 'TUSD'])
const list = computed(() => valueOf(model.positions.value))
const incomplete = computed(() => {
  const totals = list.value?.totals
  return (
    !!totals &&
    [
      totals.result,
      totals.realized,
      totals.realized_in_range,
      totals.unrealized,
      totals.income,
      totals.income_in_range,
      totals.fees,
      totals.value,
    ].some((value) => value === null)
  )
})
const unit = computed(() => model.shownUnit())
/** USD, the strategy coin, the coins traded against, then the coins held. */
const units = computed(() => {
  const found = ['USD']
  const add = (coin: string | null | undefined) => {
    if (coin && !STABLES.has(coin) && !found.includes(coin)) found.push(coin)
  }
  add(list.value?.strategy_coin)
  add(unit.value)
  for (const item of list.value?.items ?? []) add(item.traded_against)
  for (const item of list.value?.items ?? []) if (!item.closed) add(item.coin)
  return found.slice(0, 8)
})
/** What the open positions are sorted by, largest first. */
type Sort = 'value' | 'unrealized' | 'result'
const COLUMNS = ['value', 'entry', 'unrealized', 'result'] as const
const sort = ref<Sort>('value')
const num = (value: string | null | undefined) => (value ? Number(value) : Number.NEGATIVE_INFINITY)
const open = computed(() =>
  (list.value?.items ?? [])
    .filter((item) => !item.closed && !STABLES.has(item.coin))
    .sort((a, b) => num(b[sort.value]) - num(a[sort.value])),
)
const closed = computed(() =>
  (list.value?.items ?? [])
    .filter((item) => item.closed || STABLES.has(item.coin))
    .sort((a, b) => num(b.result) - num(a.result)),
)
const money = (value: string | null | undefined, sign = false) =>
  value == null ? t('calculation_unknown') : inUnit(value, unit.value, sign)
const cost = (value: string | null | undefined) =>
  value == null ? t('calculation_unknown') : price(value, unit.value)
const result = (value: string | null | undefined, pct?: string | null) =>
  value == null
    ? t('calculation_unknown')
    : `${money(value, true)}${pct ? ` (${percent(pct)})` : ''}`
/** Every coin with a realized result in the chosen period, largest gains first. */
const ranked = computed(() =>
  (list.value?.items ?? [])
    .filter((item) => item.realized_in_range === null || /[1-9]/.test(item.realized_in_range))
    .map((item) => ({
      key: item.coin,
      label: item.coin,
      value: item.realized_in_range === null ? null : Number(item.realized_in_range),
      text: money(item.realized_in_range, true),
    }))
    .sort((a, b) => (b.value ?? Number.NEGATIVE_INFINITY) - (a.value ?? Number.NEGATIVE_INFINITY))
    .slice(0, 10),
)
const tone = (value: string | null | undefined) =>
  !value || !/[1-9]/.test(value) ? '' : value.startsWith('-') ? 'text-destructive' : 'text-success'
const go = (coin: string) => props.host.navigate({ name: 'position', ids: { coin } })
const refresh = () => void model.refresh()
const columnLabel = (column: (typeof COLUMNS)[number]) =>
  t(
    column === 'value'
      ? 'column_value'
      : column === 'entry'
        ? 'column_entry'
        : column === 'unrealized'
          ? 'column_open'
          : 'pnl_column_result_all',
  )
const entry = (item: PositionItem) =>
  item.avg_entry ? t('entry_to_mark', cost(item.avg_entry), cost(item.mark)) : cost(item.mark)
const months = computed(() =>
  (list.value?.totals.realized_by_month ?? []).map((item) => ({
    key: item.month,
    tick: month(item.month),
    label: month(item.month, true),
    parts: [
      { value: item.realized === null ? null : Number(item.realized), text: result(item.realized) },
    ],
  })),
)
const lifetimeTotals = computed(() => {
  const value = list.value?.totals
  if (!value) return []
  return [
    { label: 'unrealized', value: value.unrealized },
    { label: 'realized', value: value.realized },
    { label: 'income', value: value.income },
  ] as const
})
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <CountSettings :model="model" :units="units" />
      <Chips
        :values="PERIODS"
        :selected="model.period.value"
        :label="(period) => t(period.label)"
        :name="t('pnl_period')"
        @select="(period) => model.select(period)"
      />
    </div>
    <p v-if="model.saveError.value" class="text-destructive text-sm" role="alert">
      {{ errorText(model.saveError.value) }}
    </p>
    <ReadStatus :model="model.positions.value" :host="host" @retry="refresh" />
    <CalculationIssues v-if="list" :issues="list.totals.issues" :missing="incomplete" />
    <div v-if="list" class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <section class="bg-card space-y-3 rounded-3xl border p-5" data-testid="pnl-totals">
        <h2 class="font-semibold">{{ t('realized_range') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t(model.period.value.label) }}</p>
        <p
          :class="[
            'text-3xl font-semibold tracking-tight tabular-nums',
            tone(list.totals.realized_in_range),
          ]"
          data-testid="pnl-period-result"
        >
          {{ result(list.totals.realized_in_range) }}
        </p>
        <p class="text-muted-foreground text-sm">{{ t('pnl_period_hint') }}</p>
        <dl class="bg-muted/50 rounded-2xl p-3">
          <dt class="text-muted-foreground text-xs">{{ t('income_range') }}</dt>
          <dd :class="['font-medium tabular-nums', tone(list.totals.income_in_range)]">
            {{ result(list.totals.income_in_range) }}
          </dd>
        </dl>
      </section>

      <section class="bg-card space-y-3 rounded-3xl border p-5">
        <h2 class="font-semibold">{{ t('pnl_realized_by_coin') }}</h2>
        <p v-if="!ranked.length" class="text-muted-foreground text-sm">
          {{ t('pnl_no_realized') }}
        </p>
        <RankBars
          v-else
          :items="ranked"
          :label="t('pnl_realized_by_coin')"
          clickable
          data-testid="results-by-coin"
          @select="go"
        />
      </section>

      <section
        v-if="months.length"
        class="bg-card space-y-3 rounded-3xl border p-5 md:col-span-2"
        data-testid="months"
      >
        <h2 class="font-semibold">{{ t('realized_by_month') }}</h2>
        <SignedBars
          :columns="months"
          :label="t('realized_by_month')"
          :idle="t('pnl_months_hint')"
          :format-tick="(y, step) => axis(y, step)"
        />
      </section>

      <section class="space-y-4 md:col-span-2" data-testid="pnl-current">
        <div class="space-y-1">
          <h2 class="font-semibold">{{ t('pnl_current_title') }}</h2>
          <p class="text-muted-foreground text-sm">{{ t('pnl_current_hint') }}</p>
        </div>
        <section class="bg-card space-y-3 rounded-3xl border p-5" data-testid="pnl-lifetime">
          <h3 class="font-semibold">{{ t('pnl_history_total') }}</h3>
          <p :class="['text-2xl font-semibold tabular-nums', tone(list.totals.result)]">
            {{ result(list.totals.result) }}
          </p>
          <p class="text-muted-foreground text-sm">{{ t('result_hint') }}</p>
          <dl class="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <div
              v-for="line in lifetimeTotals"
              :key="line.label"
              class="bg-muted/50 rounded-2xl p-3"
            >
              <dt class="text-muted-foreground text-xs">{{ t(line.label) }}</dt>
              <dd :class="['font-medium tabular-nums', tone(line.value)]">
                {{ result(line.value) }}
              </dd>
            </div>
          </dl>
          <p class="text-muted-foreground text-xs">
            {{ t('fees') }}: {{ money(list.totals.fees) }}
          </p>
        </section>
        <section class="bg-card rounded-3xl border">
          <h3 class="px-5 pt-5 font-semibold">{{ t('pnl_open_positions') }}</h3>
          <p v-if="!open.length" class="text-muted-foreground p-5 text-sm">
            {{ t('no_positions') }}
          </p>
          <template v-else>
            <div
              class="text-muted-foreground hidden grid-cols-[minmax(0,1.2fr)_1fr_1.4fr_1fr_1fr_1.5rem] gap-3 border-b px-5 py-3 text-xs md:grid"
            >
              <span>{{ t('column_coin') }}</span>
              <template v-for="column in COLUMNS" :key="column">
                <span v-if="column === 'entry'" class="text-right">{{ columnLabel(column) }}</span>
                <button
                  v-else
                  type="button"
                  :aria-pressed="column === sort"
                  :class="[
                    'hover:text-foreground text-right',
                    column === sort ? 'text-foreground font-medium' : '',
                  ]"
                  :aria-label="t('sort_by', columnLabel(column))"
                  @click="sort = column"
                >
                  {{ columnLabel(column) }}
                </button>
              </template>
              <span />
            </div>
            <ul class="divide-y" data-testid="positions">
              <li v-for="item in open" :key="item.coin">
                <button
                  type="button"
                  class="hover:bg-muted/50 grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-5 py-3 text-left md:grid-cols-[minmax(0,1.2fr)_1fr_1.4fr_1fr_1fr_1.5rem]"
                  @click="go(item.coin)"
                >
                  <span class="min-w-0">
                    <span class="block font-medium">{{ item.coin }}</span>
                    <span
                      class="text-muted-foreground block truncate text-xs tabular-nums md:hidden"
                    >
                      {{ entry(item) }}
                    </span>
                  </span>
                  <span class="text-right md:hidden">
                    <span class="block font-medium tabular-nums">{{ money(item.value) }}</span>
                    <span :class="['block text-xs tabular-nums', tone(item.unrealized)]">
                      {{ result(item.unrealized, item.unrealized_pct) }}
                    </span>
                  </span>
                  <span class="hidden text-right tabular-nums md:block">{{
                    money(item.value)
                  }}</span>
                  <span
                    class="text-muted-foreground hidden text-right text-sm tabular-nums md:block"
                  >
                    {{ cost(item.avg_entry) }} → {{ cost(item.mark) }}
                  </span>
                  <span :class="['hidden text-right tabular-nums md:block', tone(item.unrealized)]">
                    {{ result(item.unrealized) }}
                    <span class="block text-xs">{{ percent(item.unrealized_pct) }}</span>
                  </span>
                  <span :class="['hidden text-right tabular-nums md:block', tone(item.result)]">
                    {{ result(item.result) }}
                  </span>
                  <ChevronRight class="text-muted-foreground hidden size-4 md:block" />
                </button>
              </li>
            </ul>
          </template>
        </section>

        <details v-if="closed.length" class="bg-card rounded-3xl border">
          <summary class="cursor-pointer px-5 py-3 text-sm font-medium">
            {{ t('closed_positions', String(closed.length)) }}
          </summary>
          <ul class="divide-y border-t">
            <li v-for="item in closed" :key="item.coin">
              <button
                type="button"
                class="hover:bg-muted/50 flex w-full items-center justify-between gap-3 px-5 py-2.5 text-left text-sm"
                @click="go(item.coin)"
              >
                <span class="font-medium">{{ item.coin }}</span>
                <span :class="['tabular-nums', tone(item.result)]">{{ result(item.result) }}</span>
              </button>
            </li>
          </ul>
        </details>
      </section>
    </div>
  </div>
</template>
