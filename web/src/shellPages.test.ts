import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { router } from './router'

/**
 * The shells answer the bridge, keep links inside the app and accept notification links only on
 * the first path segments they list. A screen missing there loses every native feature: on
 * /recordings the update request went unanswered until it timed out (2026-10-08).
 */
// The router needs the page environment, where import.meta.url is not a file: vitest runs in web/.
const repository = join(process.cwd(), '..')
const LISTS = {
  ios: 'shell/ios/AssistantShell/ShellOrigin.swift',
  'ios notifications': 'shell/ios/Shared/PushCrypto.swift',
}

function listed(file: string): Set<string> {
  const source = readFileSync(join(repository, file), 'utf8')
  const body = /PAGES = setOf\(([^)]*)\)|pages: Set<String> = \[([^\]]*)\]/.exec(source)
  expect(body, file).not.toBeNull()
  return new Set([...(body![1] ?? body![2]!).matchAll(/"([^"]+)"/g)].map((match) => match[1]!))
}

describe('shell page lists', () => {
  const screens = new Set(
    router
      .getRoutes()
      // The public download bridge uses no native features and leaves the app through plain links.
      .filter((route) => route.name !== 'join')
      .map((route) => route.path.split('/')[1] ?? '')
      .filter((segment) => segment && !segment.startsWith(':')),
  )

  for (const [shell, file] of Object.entries(LISTS)) {
    it(`let the ${shell} shell serve every screen of the web client`, () => {
      const pages = listed(file)
      expect([...screens].filter((screen) => !pages.has(screen))).toEqual([])
    })
  }
})
