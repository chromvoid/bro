import { test as base, expect } from '@playwright/test'
import { memberFor } from './members'

/** Where the app is mounted: the root, on the backend and on the Vite dev server alike. */
export const APP = process.env.ASSISTANT_E2E_APP ?? '/'

const ISOLATED = process.env.ASSISTANT_E2E_ISOLATED === '1'

/**
 * Fails a test on any Content-Security-Policy violation or uncaught page error. On the disposable
 * backend pages start signed out, so tests sign in as the owner unless they set `signedOut`.
 */
export const test = base.extend<{
  problems: string[]
  signedOut: boolean
  asMember: boolean
  developer: boolean
  session: void
}>({
  signedOut: [false, { option: true }],
  /** Act as this project's synthetic member instead of the owner (e2e/members.ts). */
  asMember: [false, { option: true }],
  /** Show technical details on this device (settings: «Для разработчика»; administrators only). */
  developer: [false, { option: true }],
  session: [
    async ({ page, baseURL, signedOut, asMember, developer }, use, testInfo) => {
      if (developer)
        await page.addInitScript(() => localStorage.setItem('assistant.web.developer', '1'))
      if (ISOLATED && !signedOut && asMember) {
        await page.context().addCookies(memberFor(testInfo.project.name).cookies)
      } else if (ISOLATED && !signedOut) {
        const origin = new URL(baseURL!).origin
        const response = await page.request.post(`${origin}/v1/auth/development`, {
          data: {},
          headers: { Origin: origin },
        })
        if (!response.ok()) throw new Error(`development login failed: ${response.status()}`)
      }
      await use()
    },
    { auto: true },
  ],
  problems: [
    async ({ page }, use) => {
      const problems: string[] = []
      const started = Date.now()
      page.on('crash', () => {
        problems.push(`page crashed at ${new Date().toISOString()} (+${Date.now() - started} ms)`)
      })
      await page.exposeFunction('reportViolation', (detail: string) => problems.push(detail))
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (event) => {
          const report = (window as unknown as { reportViolation(detail: string): void })
            .reportViolation
          report(`CSP ${event.violatedDirective}: ${event.blockedURI}`)
        })
      })
      page.on('pageerror', (error) => {
        // WebKit reports a same-origin fetch cut off by a page load or teardown this way; the app
        // is same-origin, and a real policy problem arrives as a CSP violation above.
        if (/ due to access control checks\.$/.test(error.message)) return
        problems.push(`page error: ${error.message}`)
      })
      await use(problems)
      expect(problems).toEqual([])
    },
    { auto: true },
  ],
})

/**
 * Takes a change back from its confirmation. Tests of one account run in parallel, and an alert of
 * another test (its timer going off) can come on top of the confirmation and cover its button.
 * Pointing at the confirmation spreads the toasts, even when the pointer lands on the alert. Over
 * a modal panel toasts take no pointer and do not spread: the alert on top is closed first, as a
 * person would do it. Whether the confirmation is on top and closing the toast that is are one
 * step in the page, or a toast running out in between brings the confirmation to the front and
 * the close takes it; an alert landing on top before the click fails the click, and the round
 * starts again (2026-10-08).
 */
export async function undo(page: import('@playwright/test').Page, message: string | RegExp) {
  // The newest toast comes first; an older one with the same words is another change.
  const toast = page.locator('[data-sonner-toast]').filter({ hasText: message }).first()
  await toast.hover({ force: true })
  await expect(async () => {
    await expect
      .poll(() =>
        toast.evaluate((item) => {
          if (item.matches('[data-front="true"], [data-expanded="true"]')) return true
          const front = item.parentElement?.querySelector('[data-sonner-toast][data-front="true"]')
          front?.querySelector<HTMLButtonElement>('button[data-close-button]')?.click()
          return false
        }),
      )
      .toBe(true)
    await toast.getByRole('button', { name: 'Отменить', exact: true }).click({ timeout: 2000 })
  }).toPass()
}

/**
 * Saves a picture of the whole page for a person to look at, except in WebKit: Playwright prepares
 * a WebKit screenshot by appending `<style>body {}</style>` to the page, which the strict CSP
 * blocks and the check above reports as `style-src-elem: inline`.
 */
export async function photograph(page: import('@playwright/test').Page, name: string) {
  if (page.context().browser()?.browserType().name() === 'webkit') return
  await page.screenshot({ path: test.info().outputPath(name), fullPage: true })
}

/**
 * How far the page reaches past the screen's right edge, against `clientWidth`: Chromium's phone
 * emulation widens `innerWidth` to whatever sticks out, so against it nothing ever would.
 */
export async function overflow(page: import('@playwright/test').Page): Promise<number> {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
}

/**
 * The largest text the app allows: a system text size well past the 125% cap (web/src/styles.css),
 * passed as the iOS shell passes Dynamic Type. Resolves once the root stands at the cap, 20 px.
 */
export async function largestText(page: import('@playwright/test').Page) {
  await page.evaluate(() => document.documentElement.style.setProperty('--system-text-scale', '3'))
  await expect
    .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).fontSize))
    .toBe('20px')
}

/**
 * Presses a caliper action on Personal. A phone folds them under the «Калипер» row (def7cba3), and
 * the dashboard folds them again when it is rebuilt or the window narrows to a phone.
 */
export async function caliperAction(
  page: import('@playwright/test').Page,
  action: 'caliper-add' | 'caliper-history-open',
) {
  const caliper = page.getByTestId('dashboard-caliper')
  const button = caliper.getByTestId(action)
  await expect(caliper).toBeVisible()
  if (!(await button.isVisible())) await caliper.getByTestId('caliper-actions-toggle').click()
  await button.click()
}

/** The saved change-stream position of the signed-in account, read from IndexedDB. */
export async function streamCursor(page: import('@playwright/test').Page): Promise<unknown> {
  return page.evaluate(async () => {
    const names = (await indexedDB.databases()).map((database) => database.name ?? '')
    const name = names.find((value) => value.startsWith('assistant-web/'))
    if (!name) return undefined
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(name)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    try {
      return await new Promise((resolve) => {
        const request = db.transaction('meta').objectStore('meta').get('events.cursor')
        request.onsuccess = () => resolve(request.result)
      })
    } finally {
      db.close()
    }
  })
}

export { expect }
