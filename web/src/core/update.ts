/**
 * A deploy replaces the app for everyone, but never under the user's hands: a newer build is
 * only noted, and the page reloads when the user returns to it, and only while nothing holds it
 * (recording, a write in flight). Moving between screens stays in the page: a full load there
 * would download the whole app again on a slow connection.
 */

export interface UpdateOptions {
  /** The server's current build from /build.json, or null when unknown. */
  fetchBuild(): Promise<string | null>
  reload(): void
}

export class UpdateWatcher {
  private newer = false
  private readonly holds = new Set<symbol>()

  constructor(
    private readonly current: string,
    private readonly options: UpdateOptions,
  ) {}

  get available(): boolean {
    return this.newer
  }

  /** Keeps the page from reloading until the returned release is called. */
  hold(): () => void {
    const token = Symbol('update hold')
    this.holds.add(token)
    return () => {
      this.holds.delete(token)
    }
  }

  /** A build named by the server, e.g. in X-Client-Build. */
  noticed(build: string): void {
    if (build !== this.current) this.newer = true
  }

  /** The page became visible again: a natural moment to switch. */
  async returned(): Promise<void> {
    try {
      const build = await this.options.fetchBuild()
      if (build) this.noticed(build)
    } catch {
      return
    }
    if (this.ready()) this.options.reload()
  }

  private ready(): boolean {
    return this.newer && this.holds.size === 0
  }
}

const STALE_KEY = 'assistant.web.stale-chunk-reload'

export interface StaleChunks {
  /** Loads code ahead of need; a failure waits for the moment the code is needed. */
  preload(load: () => Promise<unknown>): Promise<void>
}

/**
 * A lazily loaded chunk of an older build can disappear after a deploy. The screen being opened
 * ([opening], its address) then opens with a full load of the new build; any other missing chunk
 * reloads the page. A second failure within a minute surfaces instead of looping. Code loaded
 * ahead of need fails quietly: its screen loads it again when it opens.
 */
export function reloadOnStaleChunks(
  view: Window,
  opening: () => string | null,
  now: () => number = Date.now,
): StaleChunks {
  let ahead = 0
  view.addEventListener('vite:preloadError', (event) => {
    const target = opening()
    if (!target && ahead) return
    let last = 0
    try {
      last = Number(view.sessionStorage.getItem(STALE_KEY)) || 0
      if (now() - last < 60_000) return
      view.sessionStorage.setItem(STALE_KEY, String(now()))
    } catch {
      return
    }
    event.preventDefault()
    if (target) view.location.assign(target)
    else view.location.reload()
  })
  return {
    async preload(load) {
      ahead += 1
      try {
        await load()
      } catch {
        // Nobody waits for this code yet.
      } finally {
        ahead -= 1
      }
    },
  }
}
