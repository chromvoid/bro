/**
 * The plugin catalog of the signed-in account and one host per installation. Hosts are created
 * when the catalog lists an installation (unfinished writes are then resolved by lookups only)
 * and shut down with the account.
 */
import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { router } from '@/router'
import { useSession } from '@/stores/session'
import { ROUTE_ID, ROUTE_KEY, type PluginRoute } from './api'
import { Catalog, matchUi, type Installation } from './catalog'
import { PlatformPluginHost } from './host'
import { MODULES } from './modules'

export const usePlugins = defineStore('plugins', () => {
  const session = useSession()
  const installations = shallowRef<Installation[]>([])
  const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = shallowRef<unknown>(null)
  const hosts = new Map<string, PlatformPluginHost>()

  /** Installations that offer a screen, with the module to show or the reason there is none. */
  const entries = computed(() =>
    installations.value
      .filter((installation) => installation.ui.some((item) => item.kind === 'native'))
      .map((installation) => ({ installation, ...matchUi(installation, MODULES) })),
  )

  async function load(): Promise<void> {
    const account = session.account
    if (!account || !session.can('session.control')) return
    state.value = 'loading'
    try {
      const catalog = Catalog.parse(await api.get('/v1/plugins'))
      if (session.account !== account) return
      installations.value = catalog.installations
      retain(new Set(catalog.installations.map((item) => item.installation_id)))
      for (const installation of catalog.installations) host(installation.installation_id)
      state.value = 'ready'
    } catch (failure) {
      // Without the catalog scope there are simply no plugins to show.
      if (failure instanceof ApiError && (failure.status === 403 || failure.status === 404)) {
        installations.value = []
        retain(new Set())
        state.value = 'ready'
      } else {
        error.value = failure
        state.value = 'error'
      }
    }
  }

  function host(installationId: string): PlatformPluginHost | null {
    const installation = installations.value.find((item) => item.installation_id === installationId)
    const account = session.account
    if (!installation || !account) return null
    const existing = hosts.get(installationId)
    if (existing) {
      existing.update(installation)
      return existing
    }
    const created = new PlatformPluginHost({
      api,
      journal: account.journal,
      installation,
      route: routeOf(installationId),
      navigate: (route) =>
        void router.replace({
          name: 'plugin',
          params: { installation: installationId, screen: route.name === 'home' ? '' : route.name },
          query: route.ids,
        }),
      close: () => void router.push({ name: 'today' }),
    })
    hosts.set(installationId, created)
    void created.restore()
    return created
  }

  /** The plugin's own route, read from the address of its screen. */
  function routeOf(installationId: string) {
    return computed<PluginRoute>(() => {
      const current = router.currentRoute.value
      if (current.name !== 'plugin' || current.params.installation !== installationId)
        return { name: 'home', ids: {} }
      const screen = current.params.screen
      const ids = Object.fromEntries(
        Object.entries(current.query).filter(
          (entry): entry is [string, string] =>
            typeof entry[1] === 'string' && ROUTE_KEY.test(entry[0]) && ROUTE_ID.test(entry[1]),
        ),
      )
      return { name: typeof screen === 'string' && screen ? screen : 'home', ids }
    })
  }

  function retain(ids: Set<string>): void {
    for (const [id, host] of hosts)
      if (!ids.has(id)) {
        host.shutdown()
        hosts.delete(id)
      }
  }

  // Another account (or none): nothing of the previous one stays.
  watch(
    () => session.account,
    () => {
      retain(new Set())
      installations.value = []
      state.value = 'idle'
    },
  )

  return { installations, entries, state, error, load, host }
})
