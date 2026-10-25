/** Client module of the `trading` plugin: package `trading`, contribution `trading.home`. */
import { ChartCandlestick } from '@lucide/vue'
import { PLUGIN_API_VERSION, type PluginUi } from '@/plugins/api'
import { MAJORS, READS } from './contracts'

export const trading: PluginUi = {
  packageId: 'trading',
  contributionId: 'trading.home',
  pluginApi: PLUGIN_API_VERSION,
  requiredTools: Object.fromEntries(READS.map((tool) => [tool, MAJORS[tool] ?? 1])),
  icon: ChartCandlestick,
  component: () => import('./TradingView.vue'),
}
