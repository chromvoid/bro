import { afterEach, expect, it, vi } from 'vitest'
import { Texture } from 'three/webgpu'
import { createTextureResources } from './textures'

afterEach(() => vi.unstubAllGlobals())

it('releases shared node-only maps and late parse results exactly once', () => {
  const resources = createTextureResources()
  const shared = new Texture()
  const nodeOnly = new Texture()
  const late = new Texture()
  const sharedDispose = vi.spyOn(shared, 'dispose')
  const nodeDispose = vi.spyOn(nodeOnly, 'dispose')
  const lateDispose = vi.spyOn(late, 'dispose')
  resources.track(shared)
  resources.track(shared)
  resources.track(nodeOnly)
  resources.release(shared)
  resources.dispose()
  resources.track(late)
  resources.release(late)
  resources.dispose()
  expect(sharedDispose).toHaveBeenCalledOnce()
  expect(nodeDispose).toHaveBeenCalledOnce()
  expect(lateDispose).toHaveBeenCalledOnce()
})

it('disposes texture clones separately but closes their shared bitmap once, including late clones', () => {
  class Bitmap {
    close = vi.fn()
  }
  vi.stubGlobal('ImageBitmap', Bitmap)
  const image = new Bitmap()
  const original = new Texture(image)
  const clone = original.clone()
  const late = original.clone()
  const disposals = [original, clone, late].map((texture) => vi.spyOn(texture, 'dispose'))
  const resources = createTextureResources()
  resources.track(original)
  resources.track(clone)
  resources.dispose()
  resources.track(late)
  resources.release(clone)
  resources.dispose()
  expect(image.close).toHaveBeenCalledOnce()
  for (const dispose of disposals) expect(dispose).toHaveBeenCalledOnce()
})
