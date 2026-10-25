import { APP, expect, test } from './fixtures'

test('the shell navigates between sections under the strict CSP', async ({ page, isMobile }) => {
  await page.goto(APP)
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Основная навигация' }).filter({ visible: true })
  await nav.getByRole('link', { name: 'Bro' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Bro' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Bro' })).toHaveAttribute('aria-current', 'page')
  if (isMobile) {
    // Secondary sections live in the profile sheet on phones.
    await page.getByTestId('profile-menu').filter({ visible: true }).click()
    await page.getByTestId('profile-sheet').getByRole('link', { name: 'Настройки' }).click()
    await expect(page.getByTestId('profile-sheet')).toBeHidden()
  } else {
    await nav.getByRole('link', { name: 'Настройки' }).click()
  }
  await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()
  await page.goto(`${APP}missing-page`)
  await expect(page.getByRole('heading', { level: 1, name: 'Такой страницы нет' })).toBeVisible()
})

test('the theme can be chosen and survives a reload', async ({ page }) => {
  await page.goto(`${APP}_ui`)
  await page.getByLabel('Тёмное', { exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.getByLabel('Как в системе', { exact: true }).click()
  await expect(page.locator('html')).not.toHaveAttribute('data-theme')
})

test('overlays and notifications render without CSP violations', async ({ page }) => {
  await page.goto(`${APP}_ui`)
  for (const trigger of ['Диалог', 'Нижняя панель', 'Боковая панель', 'Меню', 'Подсказка']) {
    await page.getByRole('button', { name: trigger, exact: true }).click()
    await expect(
      page.locator('[role="dialog"], [role="menu"], [data-slot="popover-content"]').first(),
    ).toBeVisible()
    await page.keyboard.press('Escape')
  }
  await page.getByRole('button', { name: 'Уведомление' }).click()
  await expect(page.getByText('Записано: омлет 100 г')).toBeVisible()
})

test('in a browser there are no devices and no glasses entry', async ({ page }) => {
  await page.goto(APP)
  await page.getByTestId('profile-menu').filter({ visible: true }).click()
  await expect(page.getByTestId('logout').filter({ visible: true })).toBeVisible()
  await expect(page.getByTestId('shell-glasses')).toHaveCount(0)
  await expect(page.getByTestId('devices-control')).toHaveCount(0)
})
