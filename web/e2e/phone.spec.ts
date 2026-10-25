import type { Page } from '@playwright/test'
import { APP, expect, overflow, test } from './fixtures'
import { INSTALLATION, fakeTrading } from './trading'

const SCREENS: Array<[string, string]> = [
  ['', 'Сегодня'],
  ['food', 'Питание'],
  ['food/stats', 'Статистика'],
  ['tasks', 'Дела'],
  ['timers', 'Таймеры и напоминания'],
  ['activity', 'Активность'],
  ['personal', 'Личное'],
  ['settings', 'Настройки'],
  ['circle', 'Круг'],
  ['recordings', 'Записи'],
  ['notifications', 'Оповещения'],
]

/**
 * Elements whose right edge passes the screen: neither hidden text for screen readers, the empty
 * toast container nor the items of a row that scrolls sideways count.
 */
async function wideElements(page: Page) {
  return page.evaluate(() => {
    const width = document.documentElement.clientWidth
    const scrolls = (element: Element | null): boolean => {
      for (
        let node = element?.parentElement;
        node && node !== document.body;
        node = node.parentElement
      )
        if (['auto', 'scroll', 'hidden', 'clip'].includes(getComputedStyle(node).overflowX))
          return true
      return false
    }
    return [...document.querySelectorAll('body *')]
      .filter((element) => {
        // The toasts' own container sits off screen until a toast shows.
        if (element.closest('.sr-only, [data-sonner-toaster]')) return false
        const box = element.getBoundingClientRect()
        return box.width > 0 && box.right > width + 1 && !scrolls(element)
      })
      .slice(0, 5)
      .map((element) => `${element.tagName} ${element.className.toString().slice(0, 80)}`)
  })
}

test.describe('on a 360 px phone', () => {
  test.beforeEach(({ isMobile }) => {
    test.skip(!isMobile, 'the phone layout')
  })

  test('no screen is wider than the phone', async ({ page }) => {
    for (const [path, title] of SCREENS) {
      await page.goto(`${APP}${path}`)
      await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
      expect(await overflow(page), title).toBeLessThanOrEqual(0)
      expect(await wideElements(page), title).toEqual([])
    }
  })

  test('the trading module fits the phone on each of its screens', async ({ page }) => {
    await fakeTrading(page)
    for (const screen of ['', 'pnl', 'orders', 'trades', 'cashflow', 'sync']) {
      await page.goto(`${APP}plugins/${INSTALLATION}${screen ? `/${screen}` : ''}`)
      await expect(page.getByRole('heading', { level: 1, name: 'Торговля' })).toBeVisible()
      // The module keeps a live stream open: give its charts a moment to measure and draw.
      await page.waitForTimeout(1000)
      expect(await overflow(page), screen || 'overview').toBeLessThanOrEqual(0)
      expect(await wideElements(page), screen || 'overview').toEqual([])
    }
  })

  test('Today starts with what the day needs: quick actions, then food', async ({ page }) => {
    await page.goto(APP)
    await expect(page.locator('#boot')).toHaveCount(0)
    await expect(page.getByTestId('today-ask')).toBeHidden()
    await expect(page.getByRole('heading', { level: 2, name: 'Питание' })).toBeInViewport()
  })

  test('segmented switches keep their segments on one row', async ({ page }) => {
    for (const path of ['activity', 'tasks', 'food/stats']) {
      await page.goto(`${APP}${path}`)
      const groups = page.locator('[data-slot="segmented"]')
      await expect(groups.first()).toBeVisible()
      for (const group of await groups.all()) {
        const tops = await group
          .locator('[role="radio"], a')
          .evaluateAll((items) => items.map((item) => Math.round(item.getBoundingClientRect().top)))
        expect(new Set(tops).size, path).toBe(1)
      }
    }
  })
})
