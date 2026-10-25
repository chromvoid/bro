import { createHash } from 'node:crypto'
import { readFile, readdir } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'

const bodyDirectory = fileURLToPath(new URL('../src/assets/body/', import.meta.url))
const mediaTypes: Record<string, string> = {
  '.glb': 'model/gltf-binary',
  '.json': 'application/json',
  '.md': 'text/markdown; charset=utf-8',
  '.png': 'image/png',
  '.webp': 'image/webp',
}

export interface BodyAssetFile {
  path: string
  sha256: string
  bytes: number
  content_type: string
}

export interface BodyAssetsManifest {
  version: 1
  files: Record<string, BodyAssetFile>
}

/** Hashes the uncompressed bytes: native clients verify the same GLB Vite imports for the web. */
export function bodyAssetFile(name: string, source: Uint8Array): BodyAssetFile {
  const contentType = mediaTypes[extname(name)]
  if (!contentType || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)) {
    throw new Error(`Unsupported body asset: ${name}`)
  }
  const sha256 = createHash('sha256').update(source).digest('hex')
  return {
    path: `/assets/body-${sha256}-${name}`,
    sha256,
    bytes: source.byteLength,
    content_type: contentType,
  }
}

/**
 * Publishes the shared body catalog without putting it in an APK. Imported body files and the
 * native manifest use one content-addressed output; metadata and notices retain their exact bytes.
 */
export function bodyAssets(
  directory = bodyDirectory,
  notices: Record<string, string> = {},
): Plugin {
  const outputs = new Map<string, string>()
  let manifest: BodyAssetsManifest = { version: 1, files: {} }
  return {
    name: 'assistant-body-assets',
    apply: 'build',
    config: () => ({
      build: {
        rolldownOptions: {
          output: {
            assetFileNames(asset: { source: string | Uint8Array }) {
              const digest = createHash('sha256').update(asset.source).digest('hex')
              return outputs.get(digest) ?? 'assets/[name]-[hash][extname]'
            },
          },
        },
      },
    }),
    async buildStart() {
      outputs.clear()
      manifest = { version: 1, files: {} }
      const names = await readdir(directory)
      const sources = {
        ...Object.fromEntries(names.map((name) => [name, join(directory, name)])),
        ...notices,
      }
      for (const name of Object.keys(sources).sort()) {
        const source = await readFile(sources[name]!)
        const file = bodyAssetFile(name, source)
        manifest.files[name] = file
        const fileName = file.path.slice(1)
        outputs.set(file.sha256, fileName)
        this.addWatchFile(sources[name]!)
        // Named assets deduplicate with Vite's imports. An explicit fileName bypasses that
        // content deduplication and emits a second copy of every imported GLB and poster.
        this.emitFile({ type: 'asset', name, source })
      }
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'body-assets.json',
        source: `${JSON.stringify(manifest)}\n`,
      })
    },
  }
}
