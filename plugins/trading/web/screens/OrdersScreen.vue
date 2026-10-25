<script setup lang="ts">
import type { PluginHost } from '@/plugins/api'
import { t } from '../labels'
import { valueOf, type Trading } from '../model'
import MoreButton from '../parts/MoreButton.vue'
import OrderRow from '../parts/OrderRow.vue'
import ReadStatus from '../parts/ReadStatus.vue'
import Section from '../parts/Section.vue'

const props = defineProps<{ host: PluginHost; trading: Trading }>()
const { open, history } = props.trading.orders
const refresh = () => void props.trading.orders.refresh()
</script>

<template>
  <Section :title="t('open_orders')">
    <ReadStatus :model="open.page.value" :host="host" shape="list" @retry="refresh" />
    <template v-if="valueOf(open.page.value)">
      <p v-if="!valueOf(open.page.value)!.items.length" class="text-muted-foreground text-sm">
        {{ t('no_open_orders') }}
      </p>
      <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="open-orders">
        <OrderRow
          v-for="order in valueOf(open.page.value)!.items"
          :key="order.order_id"
          :order="order"
        />
      </ul>
      <MoreButton
        v-if="valueOf(open.page.value)!.next_cursor"
        :busy="open.loadingMore.value"
        :error="open.moreError.value"
        @more="open.more()"
      />
    </template>
  </Section>

  <section class="space-y-3">
    <h2 class="text-base font-semibold">{{ t('orders_history') }}</h2>
    <ReadStatus
      v-if="history.page.value.kind !== 'not_connected'"
      :model="history.page.value"
      :host="host"
      shape="list"
      @retry="refresh"
    />
    <template v-if="valueOf(history.page.value)">
      <p v-if="!valueOf(history.page.value)!.items.length" class="text-muted-foreground text-sm">
        {{ t('no_orders') }}
      </p>
      <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="order-history">
        <OrderRow
          v-for="order in valueOf(history.page.value)!.items"
          :key="order.order_id"
          :order="order"
        />
      </ul>
      <MoreButton
        v-if="valueOf(history.page.value)!.next_cursor"
        :busy="history.loadingMore.value"
        :error="history.moreError.value"
        @more="history.more()"
      />
    </template>
  </section>
</template>
