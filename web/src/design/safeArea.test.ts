// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, it } from 'vitest'

const repository = fileURLToPath(new URL('../../../', import.meta.url))

function sources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (['node_modules', 'dist', 'coverage'].includes(entry.name)) return []
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return sources(path)
    return /\.(css|vue|ts)$/.test(entry.name) && !/\.(test|d)\.ts$/.test(entry.name) ? [path] : []
  })
}

it('reads the system bars only through the --inset-* properties of styles.css', () => {
  const plugins = readdirSync(join(repository, 'plugins'), { withFileTypes: true }).flatMap(
    (entry) => {
      const directory = join(repository, 'plugins', entry.name)
      return entry.isDirectory() && readdirSync(directory).includes('web')
        ? sources(join(directory, 'web'))
        : []
    },
  )
  const violations = [...sources(join(repository, 'web/src')), ...plugins].flatMap((path) => {
    if (relative(repository, path) === 'web/src/styles.css') return []
    return readFileSync(path, 'utf8')
      .split('\n')
      .flatMap((line, index) =>
        line.includes('env(safe-area-inset')
          ? [`${relative(repository, path)}:${index + 1}: ${line.trim()}`]
          : [],
      )
  })
  expect(violations).toEqual([])
})
