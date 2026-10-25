<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { computed, useId } from 'vue'
import DataNotes from '@/components/DataNotes.vue'
import type { PluginHost } from '@/plugins/api'
import AreaChart from '../charts/AreaChart.vue'
import ShareBar from '../charts/ShareBar.vue'
import { axis } from '../charts/geometry'
import { ACCOUNTS, type Balance, type Change } from '../contracts'
import { MISSING, amount, dateTime, inUnit, percent, price, usd, usdShort } from '../format'
import { earnLabel, placeLabel, t } from '../labels'
import { PERIODS, samples, valueOf, type Trading } from '../model'
import Chips from '../parts/Chips.vue'
import CalculationIssues from '../parts/CalculationIssues.vue'
import Figure from '../parts/Figure.vue'
import ReadStatus from '../parts/ReadStatus.vue'

/**
 * The overview: what the portfolio is worth and how it moved without the money put in or taken out, its value
 * over time with the moments money moved, the trading result, and what it is made of.
 */
const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.portfolio
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const overview = computed(() => valueOf(model.overview.value))
const history = computed(() => valueOf(model.history.value))
const result = computed(() => valueOf(model.result.value))
const tradingMoney = (value: string | null) =>
  value === null ? null : inUnit(value, result.value?.unit ?? 'USD', true)
const incomplete = computed(() => {
  const totals = result.value?.totals
  return (
    !!totals &&
    [totals.result, totals.realized, totals.unrealized, totals.income].some(
      (value) => value === null,
    )
  )
})
const points = computed(() => (history.value ? samples(history.value) : []))
/** Money moved between points, when it is more than noise next to the value. */
const marks = computed(() =>
  points.value.flatMap((point, index) => {
    if (!index || point.flow === null) return []
    if (Math.abs(point.flow) < Math.max(10, point.y * 0.005)) return []
    const text = t(point.flow > 0 ? 'flow_in' : 'flow_out', usd(Math.abs(point.flow).toFixed(2)))
    return [{ index, tone: point.flow > 0 ? ('up' as const) : ('down' as const), text }]
  }),
)
const sign = (value: string | null | undefined) =>
  !value || !/[1-9]/.test(value) ? '' : value.startsWith('-') ? 'text-destructive' : 'text-success'
/** The market result when the money moved in or out is known; else the plain change of value. */
const moved = (value: Change | null | undefined) =>
  value ? (value.market_usd != null ? value.market_usd : value.usd_delta) : null
const movedPct = (value: Change | null | undefined) =>
  value ? (value.market_usd != null ? value.market_pct : value.pct) : null
const windows = [
  { key: 'day', label: 'change_day' },
  { key: 'week', label: 'change_week' },
  { key: 'month', label: 'change_month' },
] as const
/** «Единый 100 · Funding 150» when a coin is not only on the Unified account. */
const spread = (balance: Balance) => {
  const held = ACCOUNTS.filter((account) => balance.accounts?.[account])
  if (held.length < 2 && (held[0] ?? 'unified') === 'unified') return null
  return held
    .map((account) => `${placeLabel(account)} ${amount(balance.accounts?.[account])}`)
    .join(' · ')
}
const shares = computed(() => {
  const balances = overview.value?.balances ?? []
  if (balances.some((balance) => balance.usd_value == null)) return null
  return balances.map((balance) => ({
    key: balance.coin,
    label: balance.coin,
    value: Number(balance.usd_value),
  }))
})
/** Realized and open parts of the result, as shares of a bar (their sizes, colored by sign). */
const split = computed(() => {
  const totals = result.value?.totals
  if (!totals || totals.realized === null || totals.unrealized === null) return null
  const realized = Number(totals.realized)
  const open = Number(totals.unrealized)
  const whole = Math.abs(realized) + Math.abs(open)
  if (!whole) return null
  return { realized, open, cut: (Math.abs(realized) / whole) * 100 }
})
// A losing part of the split is striped as well as red, so its sign reads without colour.
const losses = `result-loss-${useId()}`
const refresh = () => void model.refresh()
const openPositions = () => props.host.navigate({ name: 'pnl', ids: {} })
</script>

<template>
  <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
    <ReadStatus :model="model.overview.value" :host="host" class="md:col-span-2" @retry="refresh" />
    <section
      v-if="overview"
      class="bg-card space-y-4 rounded-3xl border p-5"
      data-testid="portfolio"
      :aria-label="t('total')"
    >
      <div>
        <p class="text-muted-foreground text-sm">{{ t('total') }}</p>
        <p class="text-4xl font-semibold tracking-tight tabular-nums">
          <Figure :text="overview.total_usd == null ? null : usd(overview.total_usd)" />
        </p>
        <p
          v-if="Object.keys(overview.by_account).length > 1"
          class="text-muted-foreground mt-1 text-xs"
        >
          {{
            ACCOUNTS.filter((name) => overview!.by_account[name] != null)
              .map((name) => `${placeLabel(name)} ${usd(overview!.by_account[name])}`)
              .join(' · ')
          }}
        </p>
      </div>
      <!-- Three tiles where they fit; on a phone rows, so a sum never breaks in the middle. -->
      <dl class="@container grid grid-cols-1 gap-2 @sm:grid-cols-3">
        <div
          v-for="window in windows"
          :key="window.key"
          class="bg-muted/50 flex flex-wrap items-baseline gap-x-3 rounded-2xl px-3 py-2 @sm:block @sm:p-3"
        >
          <dt class="text-muted-foreground me-auto text-xs">{{ t(window.label) }}</dt>
          <dd
            :class="[
              'font-medium whitespace-nowrap tabular-nums',
              sign(moved(overview.change[window.key])),
            ]"
          >
            {{
              moved(overview.change[window.key]) != null
                ? usdShort(moved(overview.change[window.key]), true)
                : MISSING
            }}
          </dd>
          <dd class="text-muted-foreground text-xs whitespace-nowrap tabular-nums">
            {{ percent(movedPct(overview.change[window.key])) }}
          </dd>
        </div>
      </dl>
      <p class="text-muted-foreground text-xs">{{ t('market_note') }}</p>
    </section>

    <section
      v-if="overview"
      class="bg-card flex flex-col gap-3 rounded-3xl border p-5"
      data-testid="trading-result"
      :aria-label="t('result_total')"
    >
      <div class="flex items-start justify-between gap-3">
        <p class="text-muted-foreground text-sm">{{ t('result_total') }}</p>
        <button
          type="button"
          class="text-primary inline-flex items-center text-sm underline-offset-4 hover:underline"
          @click="openPositions"
        >
          {{ t('all_positions') }}<ChevronRight class="size-4" />
        </button>
      </div>
      <template v-if="result">
        <p
          :class="[
            'text-3xl font-semibold tracking-tight tabular-nums',
            sign(result.totals.result),
          ]"
        >
          <Figure :text="tradingMoney(result.totals.result)" />
        </p>
        <CalculationIssues :issues="result.totals.issues" :missing="incomplete" />
        <svg
          v-if="split"
          viewBox="0 0 100 6"
          preserveAspectRatio="none"
          class="h-2 w-full rounded-full"
          role="img"
          :aria-label="
            t(
              'result_split',
              tradingMoney(result.totals.realized) ?? MISSING,
              tradingMoney(result.totals.unrealized) ?? MISSING,
            )
          "
        >
          <defs>
            <pattern :id="losses" patternUnits="userSpaceOnUse" width="2" height="6">
              <rect width="1" height="6" class="fill-destructive" />
            </pattern>
          </defs>
          <rect
            x="0"
            y="0"
            :width="split.cut"
            height="6"
            :class="split.realized >= 0 ? 'fill-success' : ''"
            :fill="split.realized < 0 ? `url(#${losses})` : undefined"
            data-testid="split-realized"
          />
          <rect
            :x="split.cut"
            y="0"
            :width="100 - split.cut"
            height="6"
            :class="split.open >= 0 ? 'fill-success/50' : 'opacity-60'"
            :fill="split.open < 0 ? `url(#${losses})` : undefined"
            data-testid="split-open"
          />
        </svg>
        <dl class="grid grid-cols-3 gap-2 text-sm">
          <div>
            <dt class="text-muted-foreground text-xs">{{ t('realized_short') }}</dt>
            <dd :class="['tabular-nums', sign(result.totals.realized)]">
              <Figure :text="tradingMoney(result.totals.realized)" />
            </dd>
          </div>
          <div>
            <dt class="text-muted-foreground text-xs">{{ t('open_short') }}</dt>
            <dd :class="['tabular-nums', sign(result.totals.unrealized)]">
              <Figure :text="tradingMoney(result.totals.unrealized)" />
            </dd>
          </div>
          <div>
            <dt class="text-muted-foreground text-xs">{{ t('income_short') }}</dt>
            <dd class="tabular-nums"><Figure :text="tradingMoney(result.totals.income)" /></dd>
          </div>
        </dl>
      </template>
      <ReadStatus v-else :model="model.result.value" :host="host" @retry="refresh" />
    </section>

    <section class="bg-card space-y-3 rounded-3xl border p-5 md:col-span-2">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 class="font-semibold">{{ t('history_title') }}</h2>
        <Chips
          :values="PERIODS"
          :selected="model.period.value"
          :label="(period) => t(period.label)"
          :name="t('history_title')"
          @select="(period) => model.select(period)"
        />
      </div>
      <ReadStatus
        v-if="model.history.value.kind !== 'not_connected'"
        :model="model.history.value"
        :host="host"
        shape="chart"
        @retry="refresh"
      />
      <template v-if="history">
        <p v-if="!points.length" class="text-muted-foreground text-sm">{{ t('history_empty') }}</p>
        <AreaChart
          v-else
          :points="points"
          :marks="marks"
          :notes="points.map((point) => (point.rebuilt ? t('point_rebuilt') : null))"
          :label="t('history_title')"
          :format-x="(x) => dateTime(new Date(x).toISOString(), zone)"
          :format-y="(y) => usd(y.toFixed(2))"
          :format-tick="(y, step) => `${axis(y, step)} $`"
        />
        <DataNotes :notes="[points.some((point) => point.rebuilt) && t('history_rebuilt')]" />
      </template>
    </section>

    <section v-if="overview" class="bg-card space-y-4 rounded-3xl border p-5 md:col-span-2">
      <h2 class="font-semibold">{{ t('holdings') }}</h2>
      <ShareBar
        v-if="shares"
        :items="shares"
        :label="t('holdings_share')"
        :others-label="t('others')"
      />
      <p v-else class="text-warning text-sm">{{ t('portfolio_shares_unknown') }}</p>
      <p v-if="!overview.balances.length" class="text-muted-foreground text-sm">
        {{ t('empty_balances') }}
      </p>
      <ul v-else class="divide-y" data-testid="balances">
        <li
          v-for="balance in overview.balances"
          :key="balance.coin"
          class="flex items-center gap-3 py-2.5"
        >
          <span
            class="bg-muted text-muted-foreground flex size-9 shrink-0 items-center justify-center rounded-full text-xs font-semibold"
            aria-hidden="true"
            >{{ balance.coin.slice(0, 3) }}</span
          >
          <div class="min-w-0 flex-1">
            <p class="font-medium">{{ balance.coin }}</p>
            <p class="text-muted-foreground truncate text-xs tabular-nums">
              {{ amount(balance.quantity) }} · {{ price(balance.price_usd, 'USD') }}
              <template v-if="spread(balance)"> · {{ spread(balance) }}</template>
            </p>
          </div>
          <div class="text-right">
            <p class="font-medium tabular-nums">{{ usd(balance.usd_value) }}</p>
            <p :class="['text-xs tabular-nums', sign(balance.day.pct) || 'text-muted-foreground']">
              {{ percent(balance.day.pct) }}
            </p>
          </div>
        </li>
      </ul>
    </section>

    <section
      v-if="overview?.earn.length"
      class="bg-card space-y-2 rounded-3xl border p-5 md:col-span-2"
    >
      <h2 class="font-semibold">{{ t('earn_title') }}</h2>
      <ul class="divide-y" data-testid="earn">
        <li
          v-for="position in overview.earn"
          :key="`${position.category}-${position.product_id}-${position.coin}`"
          class="py-2.5"
        >
          <p class="font-medium">
            {{
              t('earn_line', amount(position.amount), position.coin, earnLabel(position.category))
            }}
          </p>
          <p v-if="position.pnl" class="text-muted-foreground text-sm">
            {{ t('earn_pnl', amount(position.pnl), position.coin) }}
          </p>
        </li>
      </ul>
    </section>
  </div>
</template>
