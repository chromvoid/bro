import { describe, expect, it, vi } from 'vitest'
import { UpdateWatcher, reloadOnStaleChunks } from './update'

function setup(server: string | null = 'b1') {
  const options = {
    fetchBuild: vi.fn(async () => server),
    reload: vi.fn(),
  }
  return { watcher: new UpdateWatcher('b1', options), options }
}

describe('UpdateWatcher', () => {
  it('ignores the build it is running', async () => {
    const { watcher, options } = setup('b1')
    watcher.noticed('b1')
    await watcher.returned()
    expect(watcher.available).toBe(false)
    expect(options.reload).not.toHaveBeenCalled()
  })

  it('switches to a new build when the user returns, not while they work', async () => {
    const { watcher, options } = setup('b2')
    watcher.noticed('b2')
    expect(watcher.available).toBe(true)
    expect(options.reload).not.toHaveBeenCalled()
    await watcher.returned()
    expect(options.reload).toHaveBeenCalledOnce()
  })

  it('waits while recording or sending holds the page', async () => {
    const { watcher, options } = setup('b2')
    const release = watcher.hold()
    await watcher.returned()
    expect(options.reload).not.toHaveBeenCalled()
    release()
    await watcher.returned()
    expect(options.reload).toHaveBeenCalledOnce()
  })
})

describe('reloadOnStaleChunks', () => {
  function page() {
    const reload = vi.fn()
    const assign = vi.fn()
    const view = new EventTarget() as unknown as Window
    const storage = new Map<string, string>()
    Object.assign(view, {
      location: { reload, assign },
      sessionStorage: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => storage.set(key, value),
      },
    })
    // dispatchEvent is false once the handler takes the failure over.
    const fail = () => view.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))
    return { view, reload, assign, fail }
  }

  it('reloads once per minute when an old chunk is gone', () => {
    const { view, reload, fail } = page()
    let time = 100_000
    reloadOnStaleChunks(
      view,
      () => null,
      () => time,
    )
    expect(fail()).toBe(false)
    expect(fail()).toBe(true)
    expect(reload).toHaveBeenCalledOnce()
    time += 61_000
    fail()
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('opens the screen being opened with the new build', () => {
    const { view, reload, assign, fail } = page()
    reloadOnStaleChunks(view, () => '/tasks?task=7')
    expect(fail()).toBe(false)
    expect(assign).toHaveBeenCalledWith('/tasks?task=7')
    expect(reload).not.toHaveBeenCalled()
  })

  it('leaves code loaded ahead of need for the moment its screen opens', async () => {
    const { view, reload, assign, fail } = page()
    let target: string | null = null
    const chunks = reloadOnStaleChunks(view, () => target)
    let taken: boolean | null = null
    await chunks.preload(async () => {
      taken = !fail()
      throw new Error('offline')
    })
    expect(taken).toBe(false)
    expect(reload).not.toHaveBeenCalled()
    // A screen opened meanwhile still opens with the new build.
    target = '/food'
    await chunks.preload(async () => {
      taken = !fail()
      throw new Error('gone')
    })
    expect(taken).toBe(true)
    expect(assign).toHaveBeenCalledWith('/food')
  })
})
