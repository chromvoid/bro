<script setup lang="ts">
import { ArrowDownLeft, ArrowUpRight } from '@lucide/vue'
import { computed, ref } from 'vue'
import type { PluginHost } from '@/plugins/api'
import SignedBars from '../charts/SignedBars.vue'
import { axis } from '../charts/geometry'
import type { FlowItem, Treatment } from '../contracts'
import { amount, dateTime, month, usd, usdShort } from '../format'
import {
  flowKindLabel,
  operationLabel,
  placeLabel,
  t,
  treatmentLabel,
  type StringKey,
} from '../labels'
import { FLOW_FILTERS, valueOf, type Trading } from '../model'
import Chips from '../parts/Chips.vue'
import Line from '../parts/Line.vue'
import MarkEditor from '../parts/MarkEditor.vue'
import MoreButton from '../parts/MoreButton.vue'
import ReadStatus from '../parts/ReadStatus.vue'

/**
 * Every movement of money outside trading, across the Unified, Funding and Earn accounts: what came in and
 * went out, month by month, and each movement under its month; deposits and withdrawals can be marked.
 */
const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.cashflow
const list = model.list
const page = computed(() => valueOf(list.page.value))
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const refresh = () => void model.refresh()

/** «Funding → P2P»: where the coins went from and to. */
const route = (item: FlowItem) => {
  const here = placeLabel(item.account)
  const there = placeLabel(item.counterparty)
  return item.direction === 'in' ? t('flow_route', there, here) : t('flow_route', here, there)
}
const signedAmount = (item: FlowItem) =>
  `${item.direction === 'in' ? '+' : '−'}${amount(item.amount)} ${item.coin}`
const nonZero = (value: string) => /[1-9]/.test(value)
/** «+1 500 $», «−500 $», or both directions when money went both ways. */
const both = (came: string, went: string, format: (value: string) => string) =>
  nonZero(came) && nonZero(went)
    ? `+${format(came)} / −${format(went)}`
    : nonZero(went)
      ? `−${format(went)}`
      : `+${format(came)}`
const editing = ref<string | null>(null)
const marked = (item: FlowItem) =>
  item.treatment === 'auto'
    ? null
    : [
        treatmentLabel(item.treatment, item.direction === 'in'),
        item.unit_cost_usd ? t('treatment_cost', usd(item.unit_cost_usd)) : null,
      ]
        .filter(Boolean)
        .join(' ')
const save = async (item: FlowItem, treatment: Treatment, unitCost: string | null) => {
  if (await model.mark(item.id, treatment, unitCost)) editing.value = null
}
const coinLine = (row: { in: string; out: string; net: string }) =>
  both(row.in, row.out, amount) +
  (nonZero(row.in) && nonZero(row.out) ? ` · ${t('net')} ${amount(row.net)}` : '')
const months = computed(() =>
  (page.value?.totals.by_month ?? []).map((item) => ({
    key: item.month,
    tick: month(item.month),
    label: month(item.month, true),
    parts: [
      { value: Number(item.usd_in), text: t('flow_came', usd(item.usd_in)) },
      { value: -Number(item.usd_out), text: t('flow_went', usd(item.usd_out)) },
    ],
  })),
)
/** The movements under their calendar month, newest first. */
const groups = computed(() => {
  const found: Array<{ key: string; label: string; items: FlowItem[] }> = []
  for (const item of page.value?.items ?? []) {
    const shown = dateTime(item.occurred_at, zone)
    const key = `${shown.slice(6, 10)}-${shown.slice(3, 5)}`
    if (found.at(-1)?.key !== key) found.push({ key, label: month(key, true), items: [] })
    found.at(-1)!.items.push(item)
  }
  return found
})
</script>

<template>
  <div class="space-y-4">
    <Chips
      :values="FLOW_FILTERS"
      :selected="model.filter.value"
      :label="(filter) => t(filter.label as StringKey)"
      :name="t('tab_cashflow')"
      @select="(filter) => model.select(filter)"
    />
    <ReadStatus :model="list.page.value" :host="host" shape="list" @retry="refresh" />
    <template v-if="page">
      <section class="bg-card space-y-4 rounded-3xl border p-5" data-testid="cashflow-totals">
        <dl v-if="page.totals.usd" class="grid grid-cols-3 gap-2">
          <div class="bg-muted/50 rounded-2xl p-3">
            <dt class="text-muted-foreground text-xs">{{ t('came_in') }}</dt>
            <dd class="text-success font-medium tabular-nums">
              {{ usdShort(page.totals.usd.in, true) }}
            </dd>
          </div>
          <div class="bg-muted/50 rounded-2xl p-3">
            <dt class="text-muted-foreground text-xs">{{ t('went_out') }}</dt>
            <dd class="text-destructive font-medium tabular-nums">
              {{ nonZero(page.totals.usd.out) ? `−${usdShort(page.totals.usd.out)}` : usd('0') }}
            </dd>
          </div>
          <div class="bg-muted/50 rounded-2xl p-3">
            <dt class="text-muted-foreground text-xs">{{ t('net') }}</dt>
            <dd class="font-medium tabular-nums">{{ usdShort(page.totals.usd.net, true) }}</dd>
          </div>
        </dl>
        <SignedBars
          v-if="months.length > 1"
          :columns="months"
          :label="t('flows_by_month')"
          :idle="t('flows_by_month')"
          :format-tick="(y, step) => `${axis(y, step)} $`"
          data-testid="flows-by-month"
        />
        <details class="text-sm">
          <summary class="cursor-pointer font-medium">{{ t('flows_breakdown') }}</summary>
          <dl class="mt-2 divide-y">
            <Line
              v-for="row in page.totals.by_kind"
              :key="`kind-${row.kind}`"
              :label="flowKindLabel(row.kind)"
              :value="both(row.usd_in, row.usd_out, usd)"
            />
            <Line
              v-for="coin in page.totals.by_coin"
              :key="coin.coin"
              :label="coin.coin"
              :value="coinLine(coin)"
            />
          </dl>
        </details>
        <p v-if="page.totals.usd?.unpriced_count" class="text-muted-foreground text-sm">
          {{ t('unpriced', String(page.totals.usd.unpriced_count)) }}
        </p>
        <p class="text-muted-foreground text-sm">{{ t('flows_hint') }}</p>
      </section>
      <p v-if="!page.items.length" class="text-muted-foreground text-sm">{{ t('no_cashflow') }}</p>
      <div v-else class="space-y-3" data-testid="cashflow">
        <section v-for="group in groups" :key="group.key" class="space-y-2">
          <h3 class="text-muted-foreground px-1 text-sm font-medium">{{ group.label }}</h3>
          <ul class="bg-card divide-y rounded-3xl border">
            <li v-for="item in group.items" :key="item.id" class="px-4 py-3">
              <div class="flex items-start gap-3">
                <span
                  :class="[
                    'mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full',
                    item.direction === 'in'
                      ? 'bg-success-surface text-success'
                      : 'bg-destructive/10 text-destructive',
                  ]"
                  aria-hidden="true"
                >
                  <ArrowDownLeft v-if="item.direction === 'in'" class="size-4" />
                  <ArrowUpRight v-else class="size-4" />
                </span>
                <div class="min-w-0 flex-1">
                  <p class="font-medium">{{ operationLabel(item.operation, item.note) }}</p>
                  <p class="text-muted-foreground text-xs">
                    {{
                      [route(item), dateTime(item.occurred_at, zone), marked(item)]
                        .filter(Boolean)
                        .join(' · ')
                    }}
                  </p>
                </div>
                <div class="shrink-0 text-right">
                  <p
                    :class="[
                      'font-medium tabular-nums',
                      item.direction === 'in' ? 'text-success' : 'text-destructive',
                    ]"
                  >
                    {{ signedAmount(item) }}
                  </p>
                  <p
                    v-if="item.usd_value_at_event"
                    class="text-muted-foreground text-xs tabular-nums"
                  >
                    {{ usd(item.usd_value_at_event) }}
                  </p>
                </div>
              </div>
              <template v-if="item.markable && model.canMark.value">
                <MarkEditor
                  v-if="editing === item.id"
                  :item="item"
                  :busy="model.saving.value === item.id"
                  :error="
                    model.markError.value?.id === item.id ? model.markError.value.error : null
                  "
                  @save="(treatment, cost) => save(item, treatment, cost)"
                  @cancel="editing = null"
                />
                <button
                  v-else
                  type="button"
                  class="text-primary mt-1 ms-11 text-sm underline-offset-4 hover:underline"
                  @click="editing = item.id"
                >
                  {{ t('mark_change') }}
                </button>
              </template>
            </li>
          </ul>
        </section>
      </div>
      <MoreButton
        v-if="page.next_cursor"
        :busy="list.loadingMore.value"
        :error="list.moreError.value"
        @more="list.more()"
      />
    </template>
  </div>
</template>
