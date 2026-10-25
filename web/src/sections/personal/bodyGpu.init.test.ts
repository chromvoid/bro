import { afterEach, expect, it, vi } from 'vitest'
import { WebGPURenderer } from 'three/webgpu'
import { createBodyRenderer } from './bodyGpu'
import { neutralBodyVisual } from './bodyVisual'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('cleans up real Three after unavailable WebGL without an unhandled init retry', async () => {
  // Intentionally use the actual installed Three module, not the renderer mock.
  vi.stubGlobal('navigator', {})
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Synthetic asset unavailable')))
  const canvas = document.createElement('canvas')
  vi.spyOn(canvas, 'getContext').mockReturnValue(null)
  const add = vi.spyOn(canvas, 'addEventListener')
  const remove = vi.spyOn(canvas, 'removeEventListener')
  const unsafeDispose = vi.spyOn(WebGPURenderer.prototype, 'dispose')
  const failure = vi.fn()
  const renderer = await createBodyRenderer(canvas, {
    visual: neutralBodyVisual(),
    layer: 'composition',
    motion: false,
    active: true,
    signal: new AbortController().signal,
    onFailure: failure,
  })
  expect(renderer).toBeNull()
  expect(failure).toHaveBeenCalledOnce()
  expect(unsafeDispose).not.toHaveBeenCalled()
  const lost = add.mock.calls.find(([name]) => name === 'webglcontextlost')
  expect(lost).toBeDefined()
  expect(remove).toHaveBeenCalledWith('webglcontextlost', lost![1])
  expect(vi.mocked(fetch).mock.calls.every(([, request]) => request?.signal?.aborted)).toBe(true)
  // Vitest also rejects this test run on an unhandled promise rejection.
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
})
