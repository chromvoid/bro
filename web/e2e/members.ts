import type { Cookie } from '@playwright/test'
import { freshPerson } from './backend'

/**
 * One synthetic member per browser project, created once per run on the disposable backend.
 * Tests that change account-wide state (profile, name, sharing) act as their project's member,
 * so parallel browsers never edit the same account. The development redeem creates real invited
 * people without spending the production sign-in attempt limit; their sessions pass as cookies.
 */
export interface Member {
  id: string
  login: string
  name: string
  cookies: Cookie[]
}

const SCOPES = [
  'nutrition.read',
  'nutrition.write',
  'activity.read',
  'activity.write',
  'personal.read',
  'personal.write',
  'tasks.read',
  'tasks.write',
  'timers.read',
  'challenges.read',
  'challenges.write',
]
const PROJECTS = ['chromium', 'webkit', 'phone']

async function ready(baseURL: string) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      if ((await fetch(new URL('/health', baseURL))).ok) return
    } catch {
      // Not listening yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error(`the disposable backend at ${baseURL} did not start`)
}

export default async function globalSetup(config: {
  projects: Array<{ use: { baseURL?: string } }>
}) {
  if (process.env.ASSISTANT_E2E_ISOLATED !== '1') return
  const baseURL = config.projects[0]?.use.baseURL
  if (!baseURL) throw new Error('no baseURL for the disposable backend (playwright.config.ts)')
  await ready(baseURL)
  const members = Object.fromEntries(
    await Promise.all(
      PROJECTS.map(async (project) => [
        project,
        await freshPerson(baseURL, {
          prefix: `member-${project}`,
          name: `Участник ${project}`,
          timezone: 'Europe/Belgrade',
          scopes: SCOPES,
        }),
      ]),
    ),
  )
  process.env.ASSISTANT_E2E_MEMBERS = JSON.stringify(members)
}

export function memberFor(project: string): Member {
  const members = JSON.parse(process.env.ASSISTANT_E2E_MEMBERS ?? '{}') as Record<string, Member>
  const found = members[project]
  if (!found) throw new Error(`no synthetic member for ${project}; is the run isolated?`)
  return found
}
