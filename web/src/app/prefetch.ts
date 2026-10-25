import type { QueryClient } from '@tanstack/vue-query'
import { loadRouteLocation, type Router } from 'vue-router'
import { yieldToMain } from '@/core/scheduling'

/** Resolves once no query is loading: the screen in view has its data. */
export function settled(client: QueryClient): Promise<void> {
  return new Promise((resolve) => {
    if (!client.isFetching()) return resolve()
    const stop = client.getQueryCache().subscribe(() => {
      if (client.isFetching()) return
      stop()
      resolve()
    })
  })
}

/**
 * Loads the code of these sections once the screen in view has its data, one at a time between
 * other work, so on a slow connection a tab opens without waiting for the network.
 */
export async function prefetchSections(
  router: Router,
  names: string[],
  preload: (load: () => Promise<unknown>) => Promise<void>,
  client: QueryClient,
): Promise<void> {
  await settled(client)
  for (const name of names) {
    await yieldToMain()
    await preload(() => loadRouteLocation(router.resolve({ name })))
  }
}
