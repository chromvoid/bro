import { relative } from 'node:path'
import type { Plugin } from 'vite'

/** Zod's own files, which import each other. */
const ZOD = /[\\/]node_modules[\\/]zod[\\/]/

/**
 * Fails the build when a module imports zod other than through `core`, the module that configures
 * it. Only that import puts the config before the importer's schemas: rolldown evaluates a module's
 * dependencies first, but not a parent's sibling imports in their written order. Type-only imports
 * leave the bundle and stay allowed.
 */
export function configuredZod(core: string): Plugin {
  let root = ''
  return {
    name: 'assistant-configured-zod',
    apply: 'build',
    configResolved(config) {
      root = config.root
    },
    buildEnd(error) {
      if (error) return
      const direct = new Set<string>()
      for (const id of this.getModuleIds()) {
        if (!ZOD.test(id)) continue
        const info = this.getModuleInfo(id)
        for (const importer of [...(info?.importers ?? []), ...(info?.dynamicImporters ?? [])]) {
          if (importer !== core && !ZOD.test(importer)) direct.add(relative(root, importer))
        }
      }
      if (!direct.size) return
      this.error(
        `Import z from ${relative(root, core)}, not from 'zod': unconfigured zod probes eval when ` +
          `it builds an object schema, and the Content-Security-Policy reports it.\n` +
          [...direct].sort().join('\n'),
      )
    },
  }
}
