<script setup lang="ts">
import { computed } from 'vue'
import type { Order } from '../contracts'
import { amount, dateTime } from '../format'
import { orderStatusLabel, orderTypeLabel, t } from '../labels'
import SideBadge from './SideBadge.vue'

const props = defineProps<{ order: Order }>()
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const filled = (value: string | null | undefined) => !!value && /[1-9]/.test(value)

/**
 * A Market buy of an amount counts `qty` in the quote coin (Bybit's marketUnit): it shows what was
 * spent of that amount and what it bought; other orders count the base coin.
 */
const detail = computed(() => {
  const order = props.order
  const base = order.base_coin ?? ''
  const quote = order.quote_coin ?? ''
  const parts =
    order.qty_unit === 'quote'
      ? [
          t('order_spent', amount(order.filled_value), amount(order.qty), quote),
          filled(order.filled_qty) ? t('order_got', amount(order.filled_qty), base) : null,
        ]
      : [
          t('order_filled', amount(order.filled_qty), amount(order.qty), base),
          filled(order.filled_value) ? t('order_worth', amount(order.filled_value), quote) : null,
        ]
  parts.push(
    order.avg_price
      ? t('order_avg', amount(order.avg_price), quote)
      : order.price
        ? t('order_price', amount(order.price), quote)
        : null,
  )
  return parts.filter(Boolean).join(' · ')
})
/** Filled orders read as done, live ones plainly, closed ones quietly. */
const tone = computed(() => {
  const status = props.order.status
  if (status === 'Filled') return 'text-success'
  return ['New', 'PartiallyFilled', 'Untriggered', 'Triggered'].includes(status)
    ? 'text-foreground'
    : 'text-muted-foreground'
})
</script>

<template>
  <li class="flex items-start gap-3 px-4 py-3">
    <SideBadge :side="order.side" class="mt-0.5" />
    <div class="min-w-0 flex-1">
      <p class="font-medium">
        {{ order.symbol }}
        <span class="text-muted-foreground font-normal">· {{ orderTypeLabel(order.type) }}</span>
      </p>
      <p class="text-muted-foreground text-xs tabular-nums">{{ detail }}</p>
    </div>
    <div class="shrink-0 text-right">
      <p :class="['text-sm', tone]">{{ orderStatusLabel(order.status) }}</p>
      <p class="text-muted-foreground text-xs tabular-nums">
        {{ t('order_created', dateTime(order.created_at, zone)) }}
      </p>
    </div>
  </li>
</template>
