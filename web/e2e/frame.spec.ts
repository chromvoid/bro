import type { Page } from '@playwright/test'
import { APP, expect, overflow, photograph, test } from './fixtures'

// What a phone shell running the page edge to edge reports for its bars (styles.css --inset-*).
const TOP = 32
const BOTTOM = 24

async function systemBars(page: Page) {
  await page.addInitScript(
    ([top, bottom]) => {
      // The script runs before the parser creates <html>: set the bars as soon as it exists.
      const apply = () => {
        document.documentElement.style.setProperty('--inset-top', `${top}px`)
        document.documentElement.style.setProperty('--inset-bottom', `${bottom}px`)
      }
      if (document.documentElement) return apply()
      new MutationObserver((_, observer) => {
        if (!document.documentElement) return
        observer.disconnect()
        apply()
      }).observe(document, { childList: true })
    },
    [TOP, BOTTOM],
  )
}

test.describe('under the system bars of a phone', () => {
  test.beforeEach(async ({ page, isMobile }) => {
    test.skip(!isMobile, 'a native shell runs on phones')
    await systemBars(page)
  })

  test('nothing scrolls under the status bar', async ({ page }) => {
    await page.goto(APP)
    const heading = page.getByRole('heading', { level: 1, name: 'Сегодня' })
    await expect(heading).toBeVisible()
    // The launch screen (#boot) lies over the app until it has faded out.
    await expect(page.locator('#boot')).toHaveCount(0)
    expect((await heading.boundingBox())!.y).toBeGreaterThanOrEqual(TOP)
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    // The strip under the clock belongs to the opaque top region, scrolled or not.
    const covered = await page.evaluate(
      (top) =>
        !!document
          .elementFromPoint(window.innerWidth / 2, top / 2)
          ?.closest('[data-testid="app-top"]'),
      TOP,
    )
    expect(covered).toBe(true)
  })

  test('a sheet from the top starts below the status bar and closes', async ({ page }) => {
    await page.goto(`${APP}assistant`)
    await page.getByRole('button', { name: 'Найти в истории' }).click()
    const sheet = page.getByRole('dialog')
    await expect(sheet).toBeVisible()
    const close = sheet.getByRole('button', { name: 'Закрыть' })
    // Measured once the sheet has slid in.
    await expect.poll(async () => (await close.boundingBox())!.y).toBeGreaterThanOrEqual(TOP)
    await close.click()
    await expect(sheet).toBeHidden()
  })

  test('a bottom panel ends above the gesture bar', async ({ page }) => {
    await page.goto(`${APP}food`)
    await page.getByTestId('add-food').click()
    const panel = page.getByRole('dialog')
    await expect(panel).toBeVisible()
    const box = (await panel.boundingBox())!
    expect(box.y).toBeGreaterThanOrEqual(TOP)
    const padding = await panel.evaluate((element) =>
      Number.parseFloat(getComputedStyle(element).paddingBottom),
    )
    expect(padding).toBeGreaterThanOrEqual(BOTTOM)
  })

  test('a menu opens between the bars', async ({ page }) => {
    await page.goto(`${APP}food`)
    await page.getByRole('button', { name: 'Ещё', exact: true }).click()
    const menu = page.getByRole('menu')
    await expect(menu).toBeVisible()
    const box = (await menu.boundingBox())!
    const height = page.viewportSize()!.height
    expect(box.y).toBeGreaterThanOrEqual(TOP)
    expect(box.y + box.height).toBeLessThanOrEqual(height - BOTTOM)
  })

  test('the profile sheet scrolls to «Выйти» above the gesture bar', async ({ page }) => {
    await page.goto(APP)
    await page.getByTestId('profile-menu').filter({ visible: true }).click()
    const sheet = page.getByTestId('profile-sheet')
    await expect(sheet.getByRole('link', { name: 'Настройки' })).toBeVisible()
    const logout = sheet.getByTestId('logout')
    await logout.scrollIntoViewIfNeeded()
    const box = (await logout.boundingBox())!
    expect(box.y + box.height).toBeLessThanOrEqual(page.viewportSize()!.height - BOTTOM)
    expect((await sheet.boundingBox())!.y).toBeGreaterThanOrEqual(TOP)
  })

  test('the main screens fit the width', async ({ page }) => {
    for (const [path, title] of [
      ['', 'Сегодня'],
      ['food', 'Питание'],
      ['tasks', 'Дела'],
      ['settings', 'Настройки'],
    ]) {
      await page.goto(`${APP}${path}`)
      await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
      expect(await overflow(page), path || 'today').toBeLessThanOrEqual(0)
    }
  })

  test('the header keeps the title and its buttons in one row', async ({ page }) => {
    for (const [path, title] of [
      ['', 'Сегодня'],
      ['food', 'Питание'],
      ['tasks', 'Дела'],
      ['activity', 'Активность'],
      ['recordings', 'Записи'],
      ['settings', 'Настройки'],
      ['timers', 'Таймеры и напоминания'],
      ['notifications', 'Оповещения'],
    ]) {
      await page.goto(`${APP}${path}`)
      const heading = page.getByRole('heading', { level: 1, name: title })
      await expect(heading).toBeVisible()
      const row = (await heading.boundingBox())!
      const middle = row.y + row.height / 2
      for (const action of await page.getByTestId('header-actions').locator(':scope > *').all()) {
        const box = await action.boundingBox()
        if (!box) continue
        expect(Math.abs(box.y + box.height / 2 - middle), `${title}: header row`).toBeLessThan(12)
      }
    }
  })

  test.describe('with technical details', () => {
    test.use({ developer: true })

    test('the screen card shows what the page sees of the bars', async ({ page }) => {
      await page.goto(`${APP}settings/capabilities`)
      await expect(page.getByTestId('screen-insets')).toHaveText(`${TOP} · 0 · ${BOTTOM} · 0`)
    })
  })
})

test('on a wide screen the frame takes no room of its own', async ({ page, isMobile }) => {
  test.skip(isMobile, 'the desktop layout')
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(APP)
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  expect((await page.getByTestId('app-top').boundingBox())!.height).toBe(0)
  expect(await overflow(page)).toBeLessThanOrEqual(0)
  await photograph(page, 'frame-desktop.png')
})

test('the launch screen never takes a tap meant for the app', async ({ page }) => {
  // The first view waits for the account, so the launch screen surely stays until it is released.
  let release!: () => void
  const held = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/v1/me', async (route) => {
    await held
    await route.continue()
  })
  await page.goto(APP)
  const boot = page.locator('#boot')
  await expect(boot).toHaveCSS('pointer-events', 'none')
  release()
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  await expect(boot).toHaveCount(0)
})
