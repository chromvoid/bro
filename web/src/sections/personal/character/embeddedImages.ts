import { LoadingManager } from 'three/webgpu'

/** Own only Blob URLs generated while this preview decodes embedded glTF resources. */
export function createEmbeddedImageResources(revoke = (url: string) => URL.revokeObjectURL(url)) {
  const declaredUris = new Set<string>()
  const pending = new Set<string>()
  const revoked = new Set<string>()
  let disposed = false
  const release = (url: string) => {
    if (!pending.delete(url) || revoked.has(url)) return
    revoked.add(url)
    revoke(url)
  }
  const manager = new LoadingManager(
    undefined,
    // The loader revokes successful embedded images itself. ImageLoader calls
    // itemError before itemEnd on decode failure, so failures reach release first.
    (url) => pending.delete(url),
    release,
  ).setURLModifier((url) => {
    if (url.startsWith('blob:') && !declaredUris.has(url) && !revoked.has(url)) {
      pending.add(url)
      if (disposed) release(url)
    }
    return url
  })
  return {
    manager,
    declareUris(definitions: { uri?: string }[] | undefined) {
      for (const definition of definitions ?? [])
        if (typeof definition.uri === 'string') declaredUris.add(definition.uri.normalize('NFC'))
    },
    dispose() {
      disposed = true
      for (const url of pending) release(url)
    },
  }
}
