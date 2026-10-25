<script setup lang="ts">
import { Settings } from '@lucide/vue'
import { useNow } from '@vueuse/core'
import { computed, onBeforeUnmount, watch } from 'vue'
import ScrollStrip from '@/components/ScrollStrip.vue'
import { SEGMENT_CHOSEN } from '@/components/segmented'
import { Button } from '@/components/ui/button'
import type { PluginHost } from '@/plugins/api'
import { t, type StringKey } from './labels'
import { TABS, TAB_OF, routeOf, screenOf, tradingFor, type Screen, type Tab } from './model'
import Chips from './parts/Chips.vue'
import FreshnessBadge from './parts/FreshnessBadge.vue'
import CashflowScreen from './screens/CashflowScreen.vue'
import CoinScreen from './screens/CoinScreen.vue'
import ConnectScreen from './screens/ConnectScreen.vue'
import OrdersScreen from './screens/OrdersScreen.vue'
import PnlScreen from './screens/PnlScreen.vue'
import PortfolioScreen from './screens/PortfolioScreen.vue'
import SyncScreen from './screens/SyncScreen.vue'
import TradesScreen from './screens/TradesScreen.vue'

/**
 * The trading UI: four tabs and settings, two of them holding a pair of screens; one line tells how fresh
 * the shown data is. The host owns the page around it.
 */
const props = defineProps<{ host: PluginHost }>()
const trading = tradingFor(props.host)
onBeforeUnmount(() => trading.close())

const screen = computed(() => screenOf(props.host.route.value.name))
watch(
  () => [screen.value, props.host.route.value.ids.coin] as const,
  ([value, coin]) => {
    // Links from before the coin page named the coin on the positions screen.
    if (value === 'pnl' && coin) return props.host.navigate({ name: 'position', ids: { coin } })
    if (value === 'position') {
      if (!coin) return props.host.navigate({ name: 'pnl', ids: {} })
      trading.coin.select(coin)
    }
    trading.show(value)
  },
  { immediate: true },
)
trading.start() // after the route chose the screen: a link to one reads that one first
const liveOff = computed(
  () =>
    !trading.live.value &&
    props.host.capabilities.value.events.length > 0 &&
    screen.value !== 'connect',
)
const views = {
  portfolio: PortfolioScreen,
  orders: OrdersScreen,
  trades: TradesScreen,
  pnl: PnlScreen,
  position: CoinScreen,
  cashflow: CashflowScreen,
  sync: SyncScreen,
  connect: ConnectScreen,
}
/** Screens that share a tab, switched under it. */
const PAIRS: Partial<Record<Tab, readonly Screen[]>> = {
  orders: ['orders', 'trades'],
  settings: ['sync', 'connect'],
}
const tab = computed(() => TAB_OF[screen.value])
const pair = computed(() => PAIRS[tab.value] ?? null)
const current = computed(() => trading.model(screen.value))
const now = useNow({ interval: 30_000 })
const go = (target: Screen) => props.host.navigate({ name: routeOf(target), ids: {} })
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center gap-2">
      <ScrollStrip
        as="nav"
        class="-ml-4 min-w-0 flex-1 pl-4 md:ml-0 md:pl-0"
        :aria-label="t('sections')"
      >
        <div class="bg-muted inline-flex w-max shrink-0 gap-1 rounded-xl p-1">
          <button
            v-for="item in TABS"
            :key="item"
            type="button"
            :aria-current="item === tab ? 'page' : undefined"
            :class="[
              'inline-flex h-9 items-center rounded-lg border border-transparent px-3 text-sm font-medium whitespace-nowrap transition-colors pointer-coarse:h-11',
              item === tab ? SEGMENT_CHOSEN : 'text-muted-foreground hover:text-foreground',
            ]"
            @click="go(item)"
          >
            {{ t(`tab_${item}` as StringKey) }}
          </button>
        </div>
      </ScrollStrip>
      <Button
        variant="ghost"
        size="icon"
        :aria-label="t('tab_settings')"
        :aria-current="tab === 'settings' ? 'page' : undefined"
        :class="['shrink-0', tab === 'settings' ? 'bg-muted' : '']"
        @click="go('sync')"
      >
        <Settings class="size-5" />
      </Button>
    </div>
    <div class="flex min-h-8 flex-wrap items-center justify-between gap-2">
      <Chips
        v-if="pair"
        :values="pair"
        :selected="screen"
        :label="(item) => t(`tab_${item}` as StringKey)"
        :name="t(`tab_${tab}` as StringKey)"
        @select="go"
      />
      <FreshnessBadge
        v-if="screen !== 'connect'"
        class="ms-auto"
        :freshness="current.freshness()"
        :live="trading.live.value"
        :busy="current.busy.value"
        :now="now.getTime()"
        @refresh="trading.refresh()"
      />
    </div>
    <p v-if="liveOff" class="text-muted-foreground text-sm">{{ t('live_off') }}</p>
    <component :is="views[screen]" :host="host" :trading="trading" />
  </div>
</template>
