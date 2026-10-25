import { afterEach, describe, expect, it, vi } from 'vitest'
import { yieldToMain } from './scheduling'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('cooperative main-thread scheduling', () => {
  it('uses the native scheduler receiver and waits for its continuation', async () => {
    let resume!: () => void
    const continuation = new Promise<void>((resolve) => {
      resume = resolve
    })
    const receivers: unknown[] = []
    const scheduler = {
      yield() {
        receivers.push(this)
        return continuation
      },
    }
    vi.stubGlobal('scheduler', scheduler)
    const complete = vi.fn()
    const pending = yieldToMain().then(complete)
    expect(receivers).toEqual([scheduler])
    await Promise.resolve()
    expect(complete).not.toHaveBeenCalled()
    resume()
    await pending
    expect(complete).toHaveBeenCalledOnce()
  })

  it('continues in a timer task when the scheduling API is unavailable', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('scheduler', undefined)
    const complete = vi.fn()
    const pending = yieldToMain().then(complete)
    await Promise.resolve()
    expect(complete).not.toHaveBeenCalled()
    await vi.runAllTimersAsync()
    await pending
    expect(complete).toHaveBeenCalledOnce()
  })

  it('does not queue work for an already canceled operation', async () => {
    vi.useFakeTimers()
    const nativeYield = vi.fn(async () => {})
    vi.stubGlobal('scheduler', { yield: nativeYield })
    const controller = new AbortController()
    const reason = new Error('Superseded operation')
    controller.abort(reason)
    await expect(yieldToMain(controller.signal)).rejects.toBe(reason)
    expect(nativeYield).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not resume canceled work after a native yield', async () => {
    let resume!: () => void
    vi.stubGlobal('scheduler', {
      yield: () =>
        new Promise<void>((resolve) => {
          resume = resolve
        }),
    })
    const controller = new AbortController()
    const reason = new Error('Superseded operation')
    const pending = yieldToMain(controller.signal)
    const rejected = expect(pending).rejects.toBe(reason)
    controller.abort(reason)
    resume()
    await rejected
  })

  it('does not resume canceled work after the timer fallback', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('scheduler', {})
    const controller = new AbortController()
    const pending = yieldToMain(controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await vi.runAllTimersAsync()
    await rejected
  })

  it('propagates native scheduling failures', async () => {
    const reason = new Error('Scheduler unavailable')
    vi.stubGlobal('scheduler', { yield: () => Promise.reject(reason) })
    await expect(yieldToMain()).rejects.toBe(reason)
  })
})
