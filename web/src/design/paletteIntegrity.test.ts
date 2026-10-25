// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, it } from 'vitest'

const repository = fileURLToPath(new URL('../../../', import.meta.url))

function sources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (['node_modules', 'dist', 'coverage', '__tests__', '__fixtures__'].includes(entry.name))
      return []
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return sources(path)
    return /\.(css|vue|ts)$/.test(entry.name) && !/\.(test|d)\.ts$/.test(entry.name) ? [path] : []
  })
}

function pluginSources(): string[] {
  return readdirSync(join(repository, 'plugins'), { withFileTypes: true }).flatMap((entry) => {
    if (!entry.isDirectory()) return []
    const directory = join(repository, 'plugins', entry.name)
    return readdirSync(directory).includes('web') ? sources(join(directory, 'web')) : []
  })
}

it('keeps authored runtime palette values in the shared CSS tokens', () => {
  const violations = [...sources(join(repository, 'web/src')), ...pluginSources()].flatMap(
    (path) => {
      if (relative(repository, path) === 'web/src/design/tokens.css') return []
      return readFileSync(path, 'utf8')
        .split('\n')
        .flatMap((line, index) => {
          // Transparency/currentColor and transformations of tokens are inherited/computed styling.
          const literal =
            /#[\da-f]{3,8}\b|(?:rgba?|hsla?|hwb|oklch|oklab|lab|lch)\(\s*[\d.+-]|color\(\s*(?:srgb|display-p3)\s+[\d.+-]|\b(?:bg|text|border|fill|stroke|ring)-(?:black|white|(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d+)\b/i
          return literal.test(line)
            ? [`${relative(repository, path)}:${index + 1}: ${line.trim()}`]
            : []
        })
    },
  )
  expect(violations).toEqual([])
})

it('writes the colours of pages outside the client in OKLCH too', () => {
  // The InBody preview is served apart from the app with its own palette; its colours are OKLCH
  // like the app's (AGENTS.md).
  const directories = ['tools/inbody_character_preview']
  const pages = directories.flatMap((directory) => {
    const stylesheets = readdirSync(join(repository, directory))
      .filter((name) => name.endsWith('.css'))
      .map((name) => join(repository, directory, name))
    expect(stylesheets.length).toBeGreaterThan(0)
    return stylesheets
  })
  const violations = pages.flatMap((path) =>
    readFileSync(path, 'utf8')
      .split('\n')
      .flatMap((line, index) =>
        /#[\da-f]{3,8}\b|(?:rgba?|hsla?|hwb)\(|color\(\s*(?:srgb|display-p3)|:\s*(?:white|black)\b/i.test(
          line,
        )
          ? [`${relative(repository, path)}:${index + 1}: ${line.trim()}`]
          : [],
      ),
  )
  expect(violations).toEqual([])
})

it('keeps text colours opaque, so their contrast is the one the token tests check', () => {
  // A text colour at partial opacity («text-foreground/60») lands below 4.5:1 unseen by
  // tokens.test.ts; a quieter text uses muted-foreground instead (palette review, 8 October).
  const violations = [...sources(join(repository, 'web/src')), ...pluginSources()].flatMap((path) =>
    readFileSync(path, 'utf8')
      .split('\n')
      .flatMap((line, index) =>
        /(?:^|[\s'":])text-[a-z-]+\/\d+\b/.test(line)
          ? [`${relative(repository, path)}:${index + 1}: ${line.trim()}`]
          : [],
      ),
  )
  expect(violations).toEqual([])
})
