import type { Page } from '@playwright/test'
import { APP, expect, largestText, overflow, test } from './fixtures'

// Every page that needs no id, with its heading.
const PAGES: Array<[string, string]> = [
  ['today', 'Сегодня'],
  ['recordings', 'Записи'],
  ['assistant', 'Bro'],
  ['food', 'Питание'],
  ['food/stats', 'Статистика'],
  ['tasks', 'Дела'],
  ['timers', 'Таймеры и напоминания'],
  ['notifications', 'Оповещения'],
  ['circle', 'Круг'],
  ['circle/friends', 'Друзья'],
  ['circle/shared', 'Открыто мне'],
  ['activity', 'Активность'],
  ['personal', 'Личное'],
  ['personal/profile', 'Данные тела'],
  ['personal/history', 'История измерений'],
  ['personal/sources', 'Источники данных'],
  ['settings', 'Настройки'],
  ['settings/speech', 'Голос'],
  ['settings/sharing', 'Доступ и обмен'],
  ['settings/devices', 'Устройства'],
  ['settings/notifications', 'Оповещения'],
  ['settings/location', 'Местоположение'],
  ['places', 'Перемещения'],
  ['settings/capabilities', 'Возможности'],
  ['admin', 'Управление'],
  ['admin/moderation', 'Жалобы и модерация'],
  ['admin/speech', 'Общие голосовые подключения'],
  ['admin/chat', 'Модели'],
  ['admin/users', 'Пользователи'],
  ['admin/research-sources', 'Источники поиска'],
  ['admin/catalog', 'Каталог продуктов'],
  ['accounts', 'Подключения'],
  ['workers', 'Исполнители'],
]

// Forms that open over a page: the page, where its button is and the button's name.
const FORMS: Array<[string, string, string]> = [
  ['today', 'today-quick', 'Задача'],
  ['today', 'today-quick', 'Вес'],
  ['activity', 'header-actions', 'Добавить занятие'],
  ['personal', 'dashboard-caliper', 'Добавить замер'],
]

/** Waits until no animation runs: buttons ease into a new size. */
async function settled(page: Page) {
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
      .every((animation) => animation.playState !== 'running'),
  )
}

/** Every field, label and button stays inside the panel, and the panel inside the screen. */
function outsidePanel(box: Element) {
  const edge = box.getBoundingClientRect()
  // A row that scrolls sideways on purpose may run past the edge.
  const strip = (element: Element) => {
    for (let node = element.parentElement; node && node !== box; node = node.parentElement) {
      const style = getComputedStyle(node)
      if (style.display.includes('flex') && ['auto', 'scroll'].includes(style.overflowX))
        return true
    }
    return false
  }
  const controls = [...box.querySelectorAll('input, select, textarea, button, label')]
  const out = controls.filter((element) => {
    const rect = element.getBoundingClientRect()
    const beyond = rect.left < edge.left - 1 || rect.right > edge.right + 1
    return rect.width > 0 && beyond && !strip(element)
  })
  if (edge.left < 0 || edge.right > document.documentElement.clientWidth) out.unshift(box)
  return out.map((node) => `${node.tagName} ${node.textContent?.trim().slice(0, 30)}`)
}

test('the keyboard skips to the content and reaches the navigation', async ({ page, isMobile }) => {
  test.skip(isMobile, 'a hardware keyboard session is checked at desktop width')
  await page.goto(APP)
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  await page.keyboard.press('Tab')
  const skip = page.getByRole('link', { name: 'Перейти к содержимому' })
  await expect(skip).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/#main$/)
  const food = page
    .getByRole('navigation', { name: 'Основная навигация' })
    .filter({ visible: true })
    .getByRole('link', { name: 'Питание' })
  for (
    let step = 0;
    step < 30 && !(await food.evaluate((el) => el === document.activeElement));
    step++
  )
    await page.keyboard.press('Shift+Tab')
  await expect(food).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1, name: 'Питание' })).toBeVisible()
})

test('back returns through the screens as the shells do', async ({ page }) => {
  await page.goto(APP)
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  await page.goto(`${APP}food`)
  await expect(page.getByRole('heading', { level: 1, name: 'Питание' })).toBeVisible()
  await page.goto(`${APP}tasks`)
  await expect(page.getByRole('heading', { level: 1, name: 'Дела' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { level: 1, name: 'Питание' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
})

test('the text follows the system halfway and stops at 125%', async ({ page, browserName }) => {
  await page.goto(`${APP}today`)
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  const root = async () =>
    parseFloat(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize))
  expect(await root()).toBe(16)
  // As the iOS shell passes Dynamic Type.
  for (const [scale, size] of [
    [1.3, 18.4],
    [2, 20],
    [3, 20],
    [1, 16],
  ]) {
    await page.evaluate(
      (value) => document.documentElement.style.setProperty('--system-text-scale', String(value)),
      scale,
    )
    expect(await root(), `system ${Math.round(scale * 100)}%`).toBeCloseTo(size, 1)
  }
  // As a browser's font size setting, or the Android shell's default font size.
  if (browserName !== 'chromium') return
  const devtools = await page.context().newCDPSession(page)
  for (const [standard, size] of [
    [20, 18],
    [32, 20],
    [16, 16],
  ]) {
    await devtools.send('Page.setFontSizes', { fontSizes: { standard } })
    expect(await root(), `default ${standard} px`).toBeCloseTo(size, 1)
  }
})

test('at the largest text every page keeps its heading and never scrolls sideways', async ({
  page,
}) => {
  test.setTimeout(180_000)
  for (const [path, heading] of PAGES) {
    await test.step(path, async () => {
      await page.goto(`${APP}${path}`)
      const title = page.getByRole('heading', { level: 1, name: heading })
      await expect(title).toBeVisible()
      await largestText(page)
      await expect(title).toBeInViewport()
      await settled(page)
      expect(await overflow(page), path).toBeLessThanOrEqual(0)
      // The header's actions keep one row of 44 px targets: a second row would come off the page.
      const header = await page.getByTestId('header-actions').evaluate((actions) => {
        const boxes = [...actions.children]
          .map((child) => child.getBoundingClientRect())
          .filter((box) => box.width > 0)
        const middles = boxes.map((box) => box.top + box.height / 2)
        return {
          spread: boxes.length ? Math.max(...middles) - Math.min(...middles) : 0,
          narrowest: boxes.length ? Math.min(...boxes.map((box) => box.width)) : 44,
        }
      })
      expect(header.spread, `${path}: header actions in one row`).toBeLessThan(12)
      expect(header.narrowest, `${path}: header action width`).toBeGreaterThanOrEqual(44)
      // The phone tab bar's microphone shrinks with its cell but stays a 44 px target.
      const microphone = page.getByTestId('voice-tab')
      if (await microphone.isVisible()) {
        await expect(microphone).toBeInViewport()
        const box = await microphone.boundingBox()
        expect(Math.min(box!.width, box!.height), path).toBeGreaterThanOrEqual(44)
      }
    })
  }
})

test('at the largest text the forms over a page fit their panel', async ({ page }) => {
  for (const [path, area, name] of FORMS) {
    await test.step(`${path}: ${name}`, async () => {
      await page.goto(`${APP}${path}`)
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
      await largestText(page)
      const section = page.getByTestId(area)
      const button = section.getByRole('button', { name })
      if (area === 'dashboard-caliper') {
        await expect(section).toBeVisible()
        if (!(await button.isVisible())) await section.getByTestId('caliper-actions-toggle').click()
      }
      await button.click()
      const panel = page.locator('[data-slot="sheet-content"], [data-slot="dialog-content"]')
      await expect(panel).toBeVisible()
      await settled(page)
      const outside = await panel.evaluate(outsidePanel)
      expect(outside, `${path}: ${name}`).toEqual([])
      expect(await overflow(page), `${path}: ${name}`).toBeLessThanOrEqual(0)
      if (area === 'dashboard-caliper') {
        // Both fold groups must fit even inside the optional section's extra padding.
        await panel.getByTestId('caliper-metadata-overrides').locator('summary').click()
        for (const gender of ['male', 'female']) {
          await panel.getByTestId('caliper-gender').selectOption(gender)
          const optional = panel.getByTestId('caliper-optional')
          if (!(await optional.evaluate((element) => (element as HTMLDetailsElement).open)))
            await optional.locator('summary').click()
          await expect(optional.locator('input').first()).toBeVisible()
          expect(await panel.evaluate(outsidePanel), `${path}: ${name}, ${gender}`).toEqual([])
          expect(await overflow(page), `${path}: ${name}, ${gender}`).toBeLessThanOrEqual(0)
        }
      }
      await page.keyboard.press('Escape')
      await expect(panel).toBeHidden()
    })
  }
})

test.describe('public Android invitations', () => {
  test.use({ signedOut: true })

  test('at the largest text valid and expired invitations fit the screen', async ({ page }) => {
    await page.route('**/v1/shell/android', (route) =>
      route.fulfill({ json: { release: { version_code: 42 } } }),
    )
    for (const [fragment, heading] of [
      [
        '#server=https%3A%2F%2Ffamily.example&kind=invite&token=synthetic-invite',
        'Приглашение в Bro',
      ],
      ['#server=https%3A%2F%2Ffamily.example&kind=reset&token=synthetic-reset', 'Сброс пароля'],
      [
        '#server=http%3A%2F%2Ffamily.example&kind=invite&token=synthetic-invite',
        'Ссылка больше не действует',
      ],
    ]) {
      await test.step(heading, async () => {
        await page.goto(`${APP}join${fragment}`)
        const title = page.getByRole('heading', { level: 1, name: heading })
        await expect(title).toBeVisible()
        if (heading !== 'Ссылка больше не действует')
          await expect(
            page.getByRole('link', { name: 'Скачать приложение для Android' }),
          ).toBeVisible()
        await largestText(page)
        await expect(title).toBeInViewport()
        await settled(page)
        expect(await overflow(page), heading).toBeLessThanOrEqual(0)
        for (const link of await page.getByRole('link').all()) await expect(link).toBeInViewport()
      })
    }
  })

  for (const kind of ['invite', 'reset'] as const) {
    test(`${kind} stays private and offers Android and browser links`, async ({
      page,
      baseURL,
    }) => {
      const token = `synthetic-private-${kind}`
      const requests: Array<{
        method: string
        url: string
        data: string | null
        headers: Record<string, string>
      }> = []
      page.on('request', (request) => {
        requests.push({
          method: request.method(),
          url: request.url(),
          data: request.postData(),
          headers: request.headers(),
        })
      })
      await page.route('https://family.example/**', (route) => route.abort())
      await page.route('**/v1/shell/android', (route) =>
        route.fulfill({ json: { release: { version_code: 42 } } }),
      )
      await page.goto(`${APP}join#server=https%3A%2F%2Ffamily.example&kind=${kind}&token=${token}`)
      await expect(
        page.getByRole('heading', {
          level: 1,
          name: kind === 'invite' ? 'Приглашение в Bro' : 'Сброс пароля',
        }),
      ).toBeVisible()
      await expect(page).toHaveURL(new URL(`${APP}join`, baseURL!).href)
      await expect(page.getByText('family.example', { exact: true })).toBeVisible()
      await expect(
        page.getByRole('link', { name: 'Скачать приложение для Android' }),
      ).toHaveAttribute('href', '/v1/shell/android/42.apk')
      await expect(page.getByRole('link', { name: 'Продолжить в браузере' })).toHaveAttribute(
        'href',
        `https://family.example/login#kind=${kind}&token=${token}`,
      )
      await page.evaluate(() => {
        document.dispatchEvent(new Event('visibilitychange'))
        window.dispatchEvent(new Event('pageshow'))
      })
      await page.waitForLoadState('networkidle')
      expect(JSON.stringify(requests)).not.toContain(token)
      expect(
        requests.filter(({ url }) => new URL(url).origin === 'https://family.example'),
      ).toEqual([])
      expect(
        requests
          .filter(({ url }) => new URL(url).pathname.startsWith('/v1/'))
          .map(({ method, url, data }) => ({ method, url, data })),
      ).toEqual([{ method: 'GET', url: new URL('/v1/shell/android', baseURL!).href, data: null }])
    })
  }

  test('without a release the invitation offers the browser link', async ({ page }) => {
    await page.route('**/v1/shell/android', (route) => route.fulfill({ json: { release: null } }))
    await page.goto(
      `${APP}join#server=https%3A%2F%2Ffamily.example&kind=invite&token=synthetic-invite`,
    )
    await expect(page.getByRole('heading', { level: 1, name: 'Приглашение в Bro' })).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('link', { name: 'Скачать приложение для Android' })).toHaveCount(0)
    await expect(page.getByRole('link', { name: 'Продолжить в браузере' })).toHaveAttribute(
      'href',
      'https://family.example/login#kind=invite&token=synthetic-invite',
    )
  })
})
