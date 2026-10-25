// @vitest-environment node
import { execFile } from 'node:child_process'
import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { promisify } from 'node:util'
import { build, type Plugin } from 'vite'
import { afterEach, describe, expect, it } from 'vitest'
import { configuredZod } from './configuredZod'

const web = fileURLToPath(new URL('..', import.meta.url))
const core = join(web, 'src/core/zod.ts')

// The shape that broke on 2026-10-06: a store shared by the entry and a lazy screen builds its
// schema while it loads, from a chunk of its own that runs before the entry's code. Rolldown split
// the client's session store out by itself; here preserveEntrySignatures does it.
const shared = (zod: string) => ({
  'entry.js': `import { Session } from './store.js'
    Session.parse({ id: 'a' })
    globalThis.later = () => import('./lazy.ts')`,
  'store.js': `import { z } from '${zod}'
    export const Session = z.object({ id: z.string() })`,
  'lazy.ts': `import type { ZodType } from 'zod'
    import { Session } from './store.js'
    export const schema: ZodType = Session`,
})

// Counts calls of Function in a fresh Node process where it throws, as Chromium does under
// script-src 'self', while the built entry and its static chunks load.
const underCsp = `
let calls = 0
const refuse = () => {
  calls++
  throw new EvalError('Refused to evaluate a string as JavaScript')
}
globalThis.Function = new Proxy(Function, { apply: refuse, construct: refuse })
await import(process.argv[1])
console.log(calls)
`

describe('zod configured before any schema', () => {
  const dirs: string[] = []
  afterEach(async () => {
    await Promise.all(dirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
  })

  /** Builds the files as the client is built, resolving zod from web/, into a temporary dir. */
  async function bundle(files: Record<string, string>, plugins: Plugin[]) {
    const dir = await mkdtemp(join(tmpdir(), 'assistant-zod-'))
    dirs.push(dir)
    for (const [name, source] of Object.entries(files)) await writeFile(join(dir, name), source)
    await build({
      root: web,
      configFile: false,
      publicDir: false,
      logLevel: 'silent',
      plugins,
      resolve: { dedupe: ['zod'] },
      build: {
        outDir: join(dir, 'out'),
        emptyOutDir: true,
        rolldownOptions: {
          input: join(dir, 'entry.js'),
          preserveEntrySignatures: 'strict',
          output: { entryFileNames: '[name].js', chunkFileNames: '[name].js' },
        },
      },
    })
    return join(dir, 'out')
  }

  async function evalCalls(out: string) {
    const entry = pathToFileURL(join(out, 'entry.js')).href
    const run = promisify(execFile)
    const { stdout } = await run(process.execPath, ['--input-type=module', '-e', underCsp, entry])
    return Number(stdout.trim())
  }

  it('loads schemas without the eval probe when modules take z from core/zod', async () => {
    const out = await bundle(shared(core), [configuredZod(core)])
    expect(await readdir(out)).toContain('store.js')
    expect(await evalCalls(out)).toBe(0)
  })

  it('sees the probe of zod left unconfigured', async () => {
    const out = await bundle(shared('zod'), [])
    expect(await evalCalls(out)).toBe(1)
  })

  it('fails the build when a module imports zod directly', async () => {
    await expect(bundle(shared('zod'), [configuredZod(core)])).rejects.toThrow(/store\.js/)
  })
})
