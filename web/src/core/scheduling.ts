type BrowserScheduler = { yield?: () => Promise<void> }

/** Continue in a new task so pending input and rendering can run between work chunks. */
export async function yieldToMain(signal?: AbortSignal): Promise<void> {
  signal?.throwIfAborted()
  const scheduler = (globalThis as typeof globalThis & { scheduler?: BrowserScheduler }).scheduler
  if (typeof scheduler?.yield === 'function') await scheduler.yield()
  else await new Promise<void>((resolve) => setTimeout(resolve, 0))
  signal?.throwIfAborted()
}
