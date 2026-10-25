import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { brotliDecompressSync, gunzipSync } from 'node:zlib'
import { build } from 'vite'
import { describe, expect, it } from 'vitest'
import { compressedRepresentations, precompressedAssets } from './precompressedAssets'

describe('precompressed build assets', () => {
  const model = Buffer.from(`glTF${'synthetic vertex and animation data '.repeat(1024)}`)

  it('emits deterministic Brotli and gzip representations that restore the exact GLB', async () => {
    const first = await compressedRepresentations('assets/body-abc12345.glb', model)
    const second = await compressedRepresentations('assets/body-abc12345.glb', model)
    expect(first).toEqual(second)
    expect(first.map((asset) => asset.fileName)).toEqual([
      'assets/body-abc12345.glb.br',
      'assets/body-abc12345.glb.gz',
    ])
    expect(brotliDecompressSync(first[0]!.source)).toEqual(model)
    expect(gunzipSync(first[1]!.source)).toEqual(model)
  })

  it('compresses large hashed scripts and styles, retaining their original names', async () => {
    for (const extension of ['js', 'css', 'svg']) {
      const assets = await compressedRepresentations(`assets/client-abc12345.${extension}`, model)
      expect(assets).toHaveLength(2)
      expect(assets[0]!.fileName).toBe(`assets/client-abc12345.${extension}.br`)
    }
  })

  it('skips small files, already compressed media, public files and compression siblings', async () => {
    for (const name of [
      'build.json',
      'sw.js',
      'body.glb',
      'assets/body.glb',
      'assets/body-abc12345.png',
      'assets/body-abc12345.woff2',
      'assets/body-abc12345.glb.br',
    ]) {
      expect(await compressedRepresentations(name, model), name).toEqual([])
    }
    expect(
      await compressedRepresentations('assets/body-abc12345.glb', Buffer.from('glTF')),
    ).toEqual([])
  })

  it('does not grow downloads or emit siblings when compression has no meaningful savings', async () => {
    const noise = new Uint8Array(32768)
    let state = 0x12345678
    for (let index = 0; index < noise.length; index++) {
      state ^= state << 13
      state ^= state >>> 17
      state ^= state << 5
      noise[index] = state & 0xff
    }
    expect(await compressedRepresentations('assets/body-abc12345.glb', noise)).toEqual([])
  })

  it('emits siblings beside Vite-imported hashed GLBs in the actual output bundle', async () => {
    const root = await mkdtemp(join(tmpdir(), 'assistant-precompression-'))
    try {
      await writeFile(
        join(root, 'entry.js'),
        "import model from './body.glb?url'; console.log(model)",
      )
      await writeFile(join(root, 'body.glb'), model)
      const result = await build({
        root,
        configFile: false,
        logLevel: 'silent',
        plugins: [precompressedAssets()],
        build: {
          write: false,
          assetsInlineLimit: 0,
          rolldownOptions: { input: join(root, 'entry.js') },
        },
      })
      const output = Array.isArray(result) ? result[0] : result
      if (!output || !('output' in output)) throw new Error('Expected a completed bundle')
      const original = output.output.find((asset) => asset.fileName.endsWith('.glb'))
      const br = output.output.find((asset) => asset.fileName === `${original?.fileName}.br`)
      const gz = output.output.find((asset) => asset.fileName === `${original?.fileName}.gz`)
      if (!br || br.type !== 'asset' || !gz || gz.type !== 'asset') {
        throw new Error('Expected Brotli and gzip siblings of the emitted model')
      }
      const bytes = (source: string | Uint8Array) =>
        typeof source === 'string' ? Buffer.from(source) : source
      expect(brotliDecompressSync(bytes(br.source))).toEqual(model)
      expect(gunzipSync(bytes(gz.source))).toEqual(model)
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  })
})
