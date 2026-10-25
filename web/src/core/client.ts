/** Process-wide clients shared by every screen. */
import { Api } from './http'
import { UpdateWatcher } from './update'

export const updates = new UpdateWatcher(__ASSISTANT_BUILD__, {
  async fetchBuild() {
    // The development server has no build.json; its code reloads through Vite instead.
    if (import.meta.env.DEV) return null
    const response = await fetch('/build.json', { cache: 'no-store' })
    if (!response.ok) return null
    const { build } = (await response.json()) as { build?: unknown }
    return typeof build === 'string' ? build : null
  },
  reload: () => location.reload(),
})

export const api = new Api(undefined, {
  build: import.meta.env.DEV ? undefined : (build) => updates.noticed(build),
})
