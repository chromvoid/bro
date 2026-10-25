// @vitest-environment node
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { brotliDecompressSync } from 'node:zlib'
import { build } from 'vite'
import { describe, expect, it } from 'vitest'
import { bodyAssetFile, bodyAssets, type BodyAssetsManifest } from './bodyAssets'
import { precompressedAssets } from './precompressedAssets'
import { servedAssets } from './servedAssets'

describe('shared web and native body assets', () => {
  it('publishes exact bytes, shared imported URLs, notices and stable hashes without a second GLB', async () => {
    const root = await mkdtemp(join(tmpdir(), 'assistant-body-assets-'))
    const body = join(root, 'body')
    const model = Buffer.from(`glTF${'synthetic vertex and animation data '.repeat(1024)}`)
    const notice = 'Synthetic animation notice: exact source bytes.\n'
    try {
      await mkdir(body)
      await writeFile(
        join(root, 'entry.js'),
        "globalThis.body = new URL('./body/male.glb', import.meta.url).href",
      )
      await writeFile(join(body, 'male.glb'), model)
      await writeFile(join(body, 'male.webp'), 'synthetic poster')
      await writeFile(join(body, 'manifest.json'), '{"version":"synthetic"}\n')
      await writeFile(join(root, 'NOTICE.md'), notice)
      const bundle = async () => {
        const result = await build({
          root,
          configFile: false,
          publicDir: false,
          logLevel: 'silent',
          plugins: [
            bodyAssets(body, { 'ROKOKO_ANIMATION_NOTICE.md': join(root, 'NOTICE.md') }),
            precompressedAssets(),
            servedAssets(),
          ],
          build: {
            write: false,
            assetsInlineLimit: 0,
            rolldownOptions: { input: join(root, 'entry.js') },
          },
        })
        const output = Array.isArray(result) ? result[0] : result
        if (!output || !('output' in output)) throw new Error('Expected a completed bundle')
        const manifestFile = output.output.find((file) => file.fileName === 'body-assets.json')
        if (!manifestFile || manifestFile.type !== 'asset') throw new Error('Missing body manifest')
        return {
          files: output.output,
          manifest: JSON.parse(String(manifestFile.source)) as BodyAssetsManifest,
        }
      }
      const first = await bundle()
      expect(first.manifest.version).toBe(1)
      expect(Object.keys(first.manifest.files)).toEqual([
        'ROKOKO_ANIMATION_NOTICE.md',
        'male.glb',
        'male.webp',
        'manifest.json',
      ])
      expect(first.files.filter((file) => file.fileName.endsWith('.glb'))).toHaveLength(1)
      const modelPath = first.manifest.files['male.glb']!.path
      expect(
        first.files.some((file) => file.type === 'chunk' && file.code.includes(modelPath)),
      ).toBe(true)
      for (const asset of Object.values(first.manifest.files)) {
        const file = first.files.find((file) => `/${file.fileName}` === asset.path)
        if (!file || file.type !== 'asset') throw new Error(`Missing ${asset.path}`)
        const bytes = typeof file.source === 'string' ? Buffer.from(file.source) : file.source
        expect(createHash('sha256').update(bytes).digest('hex')).toBe(asset.sha256)
        expect(bytes.byteLength).toBe(asset.bytes)
        expect(asset.path).toContain(`/assets/body-${asset.sha256}-`)
      }
      const compressed = first.files.find((file) => `/${file.fileName}` === `${modelPath}.br`)
      if (!compressed || compressed.type !== 'asset') throw new Error('Missing compressed GLB')
      expect(brotliDecompressSync(compressed.source)).toEqual(model)
      const noticeFile = first.files.find(
        (file) => `/${file.fileName}` === first.manifest.files['ROKOKO_ANIMATION_NOTICE.md']!.path,
      )
      expect(noticeFile?.type === 'asset' && String(noticeFile.source)).toBe(notice)

      // Rebuilding changes only the URL whose bytes changed; the model remains cacheable.
      await writeFile(join(body, 'male.webp'), 'updated synthetic poster')
      const second = await bundle()
      expect(second.manifest.files['male.glb']).toEqual(first.manifest.files['male.glb'])
      expect(second.manifest.files['male.webp']!.path).not.toBe(
        first.manifest.files['male.webp']!.path,
      )
      expect(second.manifest.files['ROKOKO_ANIMATION_NOTICE.md']).toEqual(
        first.manifest.files['ROKOKO_ANIMATION_NOTICE.md'],
      )
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  })

  it('fails a build rather than listing a file the server cannot serve', () => {
    expect(() => bodyAssetFile('unserved.bin', Buffer.from('synthetic'))).toThrow(
      /Unsupported body asset/,
    )
    expect(() => bodyAssetFile('../male.glb', Buffer.from('synthetic'))).toThrow(
      /Unsupported body asset/,
    )
  })
})
