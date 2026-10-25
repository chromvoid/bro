import { extname } from 'node:path'
import type { Plugin } from 'vite'
import served from './servedAssets.json' with { type: 'json' }

/** server/web_app.py serves hashed build files from /assets/ by this name and its media types. */
const DIR = 'assets/'
const NAME = new RegExp(`^(?:${served.name})$`)

/**
 * A chunk or asset name in the route's alphabet. Rolldown's own sanitizing turns the \0 of a virtual
 * module into '_', and the route refuses a leading '_': on 2026-10-06 the build emitted
 * `_plugin-vue_export-helper-*.js`, the server answered 404 and the app loaded blank.
 */
export function servedFileName(name: string) {
  return name.replace(/[^A-Za-z0-9._-]/g, '_').replace(/^[^A-Za-z0-9]+/, '') || 'chunk'
}

/** The files under assets/ the server would answer 404; a .br or .gz sibling goes with its file. */
export function unservedAssets(fileNames: Iterable<string>) {
  return [...fileNames].filter((fileName) => {
    if (!fileName.startsWith(DIR)) return false
    const name = fileName.slice(DIR.length).replace(/\.(?:br|gz)$/, '')
    return !NAME.test(name) || !served.types.includes(extname(name))
  })
}

/** Names the bundle's files the way the server serves them and fails the build on any it would not. */
export function servedAssets(): Plugin {
  return {
    name: 'assistant-served-assets',
    apply: 'build',
    config: () => ({
      build: { rolldownOptions: { output: { sanitizeFileName: servedFileName } } },
    }),
    configResolved(config) {
      if (`${config.build.assetsDir}/` !== DIR) {
        throw new Error(
          `The server serves hashed files from /${DIR}, not /${config.build.assetsDir}/`,
        )
      }
    },
    generateBundle: {
      // After the plugins that emit files of their own, such as the compressed siblings.
      order: 'post',
      handler(_options, bundle) {
        const unserved = unservedAssets(Object.keys(bundle))
        if (!unserved.length) return
        this.error(
          `The server would answer 404 for these files: /${DIR} serves names matching ` +
            `${served.name} with a type it knows (web/tooling/servedAssets.json, ` +
            `server/web_app.py).\n${unserved.sort().join('\n')}`,
        )
      },
    },
  }
}
