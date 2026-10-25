import { promisify } from 'node:util'
import { brotliCompress, constants, gzip } from 'node:zlib'
import type { Plugin } from 'vite'

const compressBrotli = promisify(brotliCompress)
const compressGzip = promisify(gzip)
const hashedCompressibleAsset =
  /^assets\/(?:body-[a-f0-9]{64}-[^/]+|[^/]+-[A-Za-z0-9_-]{8,})\.(?:glb|js|css|svg)$/
const minimumSize = 4 * 1024

/** Build-time HTTP representations: the browser decodes these without a JS/WASM decoder. */
export async function compressedRepresentations(fileName: string, source: Uint8Array) {
  if (!hashedCompressibleAsset.test(fileName) || source.byteLength < minimumSize) return []
  const encoded = await Promise.all([
    compressBrotli(source, {
      params: {
        [constants.BROTLI_PARAM_QUALITY]: 6,
        [constants.BROTLI_PARAM_SIZE_HINT]: source.byteLength,
      },
    }),
    compressGzip(source, { level: 9 }),
  ])
  return encoded.flatMap((bytes, index) =>
    source.byteLength - bytes.byteLength >= Math.max(512, source.byteLength * 0.1)
      ? [{ fileName: `${fileName}.${index === 0 ? 'br' : 'gz'}`, source: bytes }]
      : [],
  )
}

export function precompressedAssets(): Plugin {
  return {
    name: 'assistant-precompressed-assets',
    apply: 'build',
    generateBundle: {
      // Vite's asset plugin has emitted the imported GLBs by this point.
      order: 'post',
      async handler(_options, bundle) {
        for (const [fileName, asset] of Object.entries(bundle)) {
          const source = asset.type === 'chunk' ? asset.code : asset.source
          const bytes = typeof source === 'string' ? Buffer.from(source) : source
          // Keep peak build memory bounded when the bundle contains several large models.
          for (const representation of await compressedRepresentations(fileName, bytes)) {
            this.emitFile({ type: 'asset', ...representation })
          }
        }
      },
    },
  }
}
