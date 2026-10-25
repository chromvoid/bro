/** Client modules compiled into this build; the catalog decides which of them may be shown. */
import { trading } from '../../../plugins/trading/web'
import type { PluginUi } from './api'

export const MODULES: readonly PluginUi[] = [trading]
