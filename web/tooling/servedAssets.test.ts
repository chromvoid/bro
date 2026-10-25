// @vitest-environment node
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import vue from '@vitejs/plugin-vue'
import { build, type Plugin } from 'vite'
import { afterEach, describe, expect, it } from 'vitest'
import { servedAssets, servedFileName, unservedAssets } from './servedAssets'

// Two lazy screens with scoped styles share Vue's export helper, a virtual module: the shape that
// rolldown emitted as `_plugin-vue_export-helper-<hash>.js` on 2026-10-06.
const screens = {
  'entry.js': `globalThis.screens = [() => import('./One.vue'), () => import('./Two.vue')]`,
  'One.vue': `<template><p>one</p></template><style scoped>p { color: red }</style>`,
  'Two.vue': `<template><p>two</p></template><style scoped>p { color: blue }</style>`,
}

describe('build files the server serves', () => {
  const dirs: string[] = []
  afterEach(async () => {
    await Promise.all(dirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
  })

  /** The emitted file names of a Vite build of the files, as the client is built. */
  async function bundle(files: Record<string, string>, plugins: Plugin[], assetsDir = 'assets') {
    const dir = await mkdtemp(join(tmpdir(), 'assistant-served-'))
    dirs.push(dir)
    for (const [name, source] of Object.entries(files)) await writeFile(join(dir, name), source)
    const result = await build({
      root: dir,
      configFile: false,
      publicDir: false,
      logLevel: 'silent',
      plugins: [vue(), ...plugins],
      build: {
        write: false,
        assetsDir,
        rolldownOptions: { input: join(dir, 'entry.js'), external: ['vue'] },
      },
    })
    const output = Array.isArray(result) ? result[0] : result
    if (!output || !('output' in output)) throw new Error('Expected a completed bundle')
    return output.output.map((file) => file.fileName)
  }

  it('names a virtual module in the route alphabet and leaves ordinary names alone', () => {
    expect(servedFileName('\0plugin-vue:export-helper')).toBe('plugin-vue_export-helper')
    for (const name of ['AppShell', 'index', 'inter-cyrillic-wght-normal.woff2', 'body.glb']) {
      expect(servedFileName(name)).toBe(name)
    }
    expect(servedFileName('\0?*')).toBe('chunk')
  })

  it('finds the files under assets/ the server would answer 404', () => {
    const served = [
      'assets/index-BDNMzG2s.js',
      'assets/index-BDNMzG2s.js.br',
      'assets/body-a_b-c123.glb.gz',
      'assets/inter-latin-wght-normal-Dx4kXJAl.woff2',
      'index.html',
      'build.json',
    ]
    const refused = [
      'assets/_plugin-vue_export-helper-BDNMzG2s.js',
      'assets/_plugin-vue_export-helper-BDNMzG2s.js.br',
      'assets/.hidden.js',
      'assets/nested/index-abc123.js',
      'assets/index-abc123.js.map',
      'assets/font-abc123.woff',
    ]
    expect(unservedAssets([...served, ...refused])).toEqual(refused)
  })

  it('emits the shared export helper under a name the server serves', async () => {
    const names = await bundle(screens, [servedAssets()])
    expect(names.some((name) => /^assets\/plugin-vue_export-helper-[\w-]+\.js$/.test(name))).toBe(
      true,
    )
    expect(unservedAssets(names)).toEqual([])
  })

  it('fails the build when a file under assets/ would not be served', async () => {
    const notes: Plugin = {
      name: 'notes',
      generateBundle() {
        this.emitFile({ type: 'asset', fileName: 'assets/notes-abc123.txt', source: 'notes' })
      },
    }
    await expect(bundle(screens, [notes, servedAssets()])).rejects.toThrow(/notes-abc123\.txt/)
  })

  it('refuses a build directory other than the route', async () => {
    await expect(bundle(screens, [servedAssets()], 'static')).rejects.toThrow(/\/assets\//)
  })
})
