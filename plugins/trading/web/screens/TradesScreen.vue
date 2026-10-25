<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PluginHost } from '@/plugins/api'
import type { Execution } from '../contracts'
import { amount, dateTime } from '../format'
import { t } from '../labels'
import { valueOf, type Trading } from '../model'
import MoreButton from '../parts/MoreButton.vue'
import ReadStatus from '../parts/ReadStatus.vue'
import SideBadge from '../parts/SideBadge.vue'

/** Every fill, newest first, under its day. */
const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.trades
const page = computed(() => valueOf(model.page.value))
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const refresh = () => void model.refresh()
const now = ref(Date.now())
/** «Сегодня», «Вчера» or the date, in the account's zone. */
const dayLabel = (day: string) => {
  const today = dateTime(new Date(now.value).toISOString(), zone).slice(0, 10)
  const yesterday = dateTime(new Date(now.value - 86_400_000).toISOString(), zone).slice(0, 10)
  return day === today ? t('today') : day === yesterday ? t('yesterday') : day
}
const days = computed(() => {
  const found: Array<{ day: string; items: Execution[] }> = []
  for (const trade of page.value?.items ?? []) {
    const day = dateTime(trade.exec_time, zone).slice(0, 10)
    if (found.at(-1)?.day !== day) found.push({ day, items: [] })
    found.at(-1)!.items.push(trade)
  }
  return found
})
const coin = (value: string | null | undefined, name: string | null | undefined) =>
  `${amount(value)} ${name ?? ''}`.trim()
</script>

<template>
  <div class="space-y-4">
    <ReadStatus :model="model.page.value" :host="host" shape="list" @retry="refresh" />
    <template v-if="page">
      <p v-if="!page.items.length" class="text-muted-foreground text-sm">{{ t('no_trades') }}</p>
      <div v-else class="space-y-3" data-testid="trades">
        <section v-for="group in days" :key="group.day" class="space-y-2">
          <h3 class="text-muted-foreground px-1 text-sm font-medium">{{ dayLabel(group.day) }}</h3>
          <ul class="bg-card divide-y rounded-3xl border">
            <li
              v-for="trade in group.items"
              :key="`${trade.exec_id}-${trade.order_id}`"
              class="flex items-start gap-3 px-4 py-3"
            >
              <SideBadge :side="trade.side" class="mt-0.5" />
              <div class="min-w-0 flex-1">
                <p class="font-medium tabular-nums">
                  {{ coin(trade.qty, trade.base_coin) }}
                  <span class="text-muted-foreground font-normal">
                    · {{ coin(trade.price, trade.quote_coin) }}</span
                  >
                </p>
                <p class="text-muted-foreground text-xs tabular-nums">
                  {{
                    t(
                      'trade_detail',
                      coin(trade.value, trade.quote_coin),
                      coin(trade.fee, trade.fee_coin),
                      trade.is_maker ? t('maker') : t('taker'),
                    )
                  }}
                </p>
              </div>
              <div class="shrink-0 text-right">
                <p class="text-sm">{{ trade.symbol }}</p>
                <p class="text-muted-foreground text-xs tabular-nums">
                  {{ dateTime(trade.exec_time, zone).slice(11) }}
                </p>
              </div>
            </li>
          </ul>
        </section>
      </div>
      <MoreButton
        v-if="page.next_cursor"
        :busy="model.loadingMore.value"
        :error="model.moreError.value"
        @more="model.more()"
      />
    </template>
  </div>
</template>
