import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ImageLoader } from 'three/webgpu'
import { createEmbeddedImageResources } from './embeddedImages'

beforeEach(() => {
  // Drive decode outcomes explicitly: Happy DOM synchronously emits load when
  // ImageLoader sets a data URI, which removes its error listener before our event.
  vi.spyOn(HTMLImageElement.prototype, 'src', 'set').mockImplementation(() => undefined)
})
afterEach(() => vi.restoreAllMocks())

describe('embedded glTF image ownership', () => {
  it('revokes failed embedded images while preserving declared and data URIs', () => {
    const revoke = vi.fn()
    const resources = createEmbeddedImageResources(revoke)
    const embedded = 'blob:https://assistant.test/generated-beard-image'
    const supplied = 'blob:https://assistant.test/supplied-image'
    const data = 'data:image/png;base64,AAAA'
    resources.declareUris([{ uri: supplied }, { uri: data }])
    const loader = new ImageLoader(resources.manager)
    for (const url of [embedded, supplied, data]) {
      expect(resources.manager.resolveURL(url)).toBe(url)
      const decodeFailed = vi.fn()
      const image = loader.load(url, undefined, undefined, decodeFailed)
      image.dispatchEvent(new Event('error'))
      expect(decodeFailed).toHaveBeenCalledOnce()
    }
    resources.dispose()
    resources.dispose()
    expect(revoke.mock.calls).toEqual([[embedded]])
  })

  it('releases a pending decode on abort and immediately releases late embedded URLs', () => {
    const revoke = vi.fn()
    const resources = createEmbeddedImageResources(revoke)
    const pending = 'blob:https://assistant.test/pending-normal-image'
    const late = 'blob:https://assistant.test/late-relief-image'
    const loader = new ImageLoader(resources.manager)
    const pendingImage = loader.load(pending, undefined, undefined, vi.fn())
    resources.dispose()
    expect(resources.manager.resolveURL(late)).toBe(late)
    const lateImage = loader.load(late, undefined, undefined, vi.fn())
    pendingImage.dispatchEvent(new Event('error'))
    lateImage.dispatchEvent(new Event('error'))
    resources.manager.resolveURL(late)
    resources.dispose()
    expect(revoke.mock.calls).toEqual([[pending], [late]])
  })

  it('leaves successful URL revocation to the loader and isolates other previews', () => {
    const revoke = vi.fn()
    const owner = createEmbeddedImageResources(revoke)
    const other = createEmbeddedImageResources(revoke)
    const loaded = 'blob:https://assistant.test/loaded-diffuse-image'
    const active = 'blob:https://assistant.test/other-preview-image'
    const complete = new ImageLoader(owner.manager).load(loaded)
    complete.dispatchEvent(new Event('load'))
    new ImageLoader(other.manager).load(active)
    owner.dispose()
    expect(revoke).not.toHaveBeenCalled()
    other.dispose()
    expect(revoke.mock.calls).toEqual([[active]])
  })
})
