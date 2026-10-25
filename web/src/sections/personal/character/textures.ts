import { type Texture } from 'three/webgpu'

/** Node-only maps and late asynchronous loads share the same exactly-once ownership. */
export function createTextureResources() {
  const textures = new Set<Texture>()
  const released = new WeakSet<Texture>()
  const releasedBitmaps = new WeakSet<ImageBitmap>()
  let disposed = false
  const release = (texture: Texture) => {
    textures.delete(texture)
    if (released.has(texture)) return
    released.add(texture)
    texture.dispose()
    const source: unknown = texture.source?.data
    if (
      typeof ImageBitmap !== 'undefined' &&
      source instanceof ImageBitmap &&
      !releasedBitmaps.has(source)
    ) {
      releasedBitmaps.add(source)
      source.close()
    }
  }
  return {
    track(texture: Texture) {
      if (disposed) release(texture)
      else textures.add(texture)
    },
    release,
    dispose() {
      disposed = true
      for (const texture of textures) release(texture)
    },
  }
}
