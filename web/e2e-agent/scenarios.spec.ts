import { test, expect, request, type APIRequestContext, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import MarkdownIt, { type Token } from 'markdown-it'
import type { Source } from '../src/sections/assistant/model'

const markdown = new MarkdownIt('commonmark', { html: false }).enable(['table', 'strikethrough'])
function visibleFragments(tokens: Token[]): string[] {
  return tokens.flatMap((token) =>
    token.children ? visibleFragments(token.children) : token.content.trim() ? [token.content] : [],
  )
}
const normalized = (text: string) => text.replace(/\s+/g, ' ').trim()

function missingFragment(text: string, fragments: string[]): string | null {
  let cursor = 0
  for (const fragment of fragments) {
    const match = text.indexOf(fragment, cursor)
    if (match < 0) return fragment
    cursor = match + fragment.length
  }
  return null
}

interface Step {
  id: string
  say: string
  reply_to?: string
  reload?: boolean
}
interface Scenario {
  id: string
  title: string
  steps: Step[]
}
interface Exchange {
  id: string
  state: string
  turns: Array<{
    state: string
    result: { reply?: string; services?: Array<{ sources?: Source[] }> }
  }>
}

const output = process.env.AGENT_E2E_OUTPUT!
const backend = JSON.parse(readFileSync(process.env.AGENT_E2E_CONNECTION!, 'utf8')) as {
  origin: string
  control: string
  token: string
}
const plan = JSON.parse(readFileSync(join(output, 'plan.json'), 'utf8')) as {
  cases: Scenario[]
}

async function control<T>(api: APIRequestContext, path: string, data: unknown = {}): Promise<T> {
  const result = await api.post(path, { data, timeout: 90_000 })
  if (!result.ok()) throw new Error(`Test controller ${path}: ${result.status()}`)
  return (await result.json()) as T
}

// A client that fails to start never shows the composer, and the first fill would wait out the
// whole test timeout. Fail within a minute and name the page errors and refused assets instead.
async function expectClientStarted(page: Page, problems: string[]): Promise<void> {
  try {
    await page.locator('textarea').waitFor({ state: 'visible', timeout: 60_000 })
  } catch (error) {
    throw new Error(`The web client did not start; browser problems: ${JSON.stringify(problems)}`, {
      cause: error,
    })
  }
}

for (const scenario of plan.cases) {
  test(`${scenario.id}: ${scenario.title}`, async ({ page }, testInfo) => {
    const api = await request.newContext({
      baseURL: backend.control,
      extraHTTPHeaders: { 'X-Agent-E2E': backend.token },
    })
    const root = `/cases/${scenario.id}`
    const browserProblems: string[] = []
    const messages = new Map<string, string>()
    let failed = true
    page.on('pageerror', (error) => browserProblems.push(error.message))
    // A build chunk the server refuses leaves the client blank without a page error: the router
    // only logs the failed import (PR 93, fixed in PR 122).
    page.on('response', (response) => {
      const { pathname } = new URL(response.url())
      if (response.status() >= 400 && pathname.startsWith('/assets/'))
        browserProblems.push(`${response.status()} ${pathname}`)
    })
    try {
      const actor = await control<{ cookie: string; reference_time: string }>(api, root + '/start')
      await page.context().addCookies([
        {
          name: 'assistant_dev',
          value: actor.cookie,
          url: backend.origin,
          httpOnly: true,
          sameSite: 'Lax',
        },
      ])
      // Freeze business date while keeping timers and assertion deadlines running.
      await page.clock.setFixedTime(new Date(actor.reference_time))
      await page.goto('/assistant')
      await expectClientStarted(page, browserProblems)
      for (const step of scenario.steps) {
        await test.step(step.id, async () => {
          await control(api, `${root}/begin/${step.id}`)
          if (step.reload) {
            await page.reload()
            await expectClientStarted(page, browserProblems)
          }
          if (step.reply_to) {
            const prior = messages.get(step.reply_to)
            if (!prior) throw new Error('No message for reply_to')
            await page
              .locator(`#message-${prior}`)
              .getByTestId('reply-menu')
              .last()
              .click({ timeout: 15_000 })
            await page
              .getByRole('menuitem', { name: 'Ответить', exact: true })
              .click({ timeout: 15_000 })
          }
          const composer = page.locator('textarea')
          await composer.fill(step.say)
          const admitted = page.waitForResponse(
            (response) =>
              new URL(response.url()).pathname === '/v1/conversation/messages' &&
              response.request().method() === 'POST',
          )
          await page.getByRole('button', { name: 'Отправить', exact: true }).click()
          const submission = await admitted
          expect(submission.status()).toBe(202)
          const { message_id: messageId } = (await submission.json()) as { message_id: string }
          messages.set(step.id, messageId)
          let exchange: Exchange | undefined
          await expect
            .poll(
              async () => {
                const response = await page.request.get('/v1/conversation/messages', {
                  params: { message_id: messageId },
                })
                if (!response.ok()) throw new Error(`History: ${response.status()}`)
                const history = (await response.json()) as { messages: Exchange[] }
                exchange = history.messages.find((message) => message.id === messageId)
                return exchange?.state
              },
              { timeout: 210_000, intervals: [250, 500, 1000] },
            )
            .toMatch(/^(completed|failed)$/)
          const article = page.locator(`#message-${messageId}`)
          await expect(article).toBeVisible()
          await expect(article.getByTestId('processing')).toHaveCount(0)
          expect(exchange?.turns.length).toBeGreaterThan(0)
          const replies = article.getByTestId('reply')
          await expect(replies).toHaveCount(exchange!.turns.length)
          // Assert the actual response is rendered, not just that a spinner stopped.
          // The user's bubble can contain the same text, so check each reply separately.
          for (const [index, turn] of (exchange?.turns ?? []).entries()) {
            if (!turn.result?.reply) continue
            // Formatting markers and list boundaries need not be literal text.
            // Every public text fragment must still be present in its own reply.
            const fragments = visibleFragments(markdown.parse(turn.result.reply, {})).map(
              normalized,
            )
            await expect
              .poll(async () =>
                missingFragment(
                  normalized((await replies.nth(index).textContent()) ?? ''),
                  fragments,
                ),
              )
              .toBeNull()
          }
          const ui = { replies: await replies.allTextContents() }
          const assessed = await control<{
            status: string
            folder: string
            checks: Array<{ id: string; status: string }>
          }>(api, `${root}/assess/${step.id}`, { message_id: messageId, ui })
          for (const name of ['evidence.json', 'verdict.json', 'judge.json']) {
            await testInfo.attach(`${step.id}-${name}`, {
              path: join(assessed.folder, name),
              contentType: 'application/json',
            })
          }
          // Keep assessing later steps, and preserve Jev's inconclusive verdict.
          // A failed assertion is not itself a browser/infrastructure error.
          const problems = assessed.checks
            .filter((check) => check.status !== 'pass')
            .map(({ id, status }) => ({ id, status }))
          expect
            .soft(
              assessed.status,
              `${JSON.stringify(problems)}; cases/${scenario.id}/${step.id}/verdict.json`,
            )
            .toBe('pass')
        })
      }
      expect(browserProblems).toEqual([])
      failed = false
    } finally {
      try {
        await control(api, root + '/finish', { browser_failed: failed })
      } finally {
        await api.dispose()
      }
    }
  })
}
