import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test('a link from an empty Today card opens its action once', async ({ page }) => {
  await page.goto(`${APP}food?add=1`)
  await expect(page.getByRole('dialog', { name: 'Добавить еду' })).toBeVisible()
  // The parameter is dropped, so Back and reload do not open the sheet again.
  await expect(page).not.toHaveURL(/add=1/)

  await page.goto(`${APP}personal?weight=1`)
  await expect(page.getByLabel('Вес, кг')).toBeVisible()
  await expect(page).not.toHaveURL(/weight=1/)

  await page.goto(`${APP}tasks?add=1`)
  await expect(page.getByPlaceholder('Новая задача')).toBeFocused()
  await expect(page).not.toHaveURL(/add=1/)
})

test('quick actions record from Today without leaving it', async ({ page }) => {
  test.skip(!ISOLATED, 'adds a task and starts a timer: needs the disposable backend')
  const title = `Купить батарейки ${Date.now().toString(36)}`
  await page.goto(APP)
  const quick = page.getByTestId('today-quick')

  await quick.getByRole('button', { name: 'Задача' }).click()
  await page.getByRole('textbox', { name: 'Что сделать' }).fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  await expect(page.getByTestId('today-tasks')).toContainText(title)

  await quick.getByRole('button', { name: 'Таймер' }).click()
  await page.getByLabel('Название').fill('Чайник')
  await page.getByRole('button', { name: '5 мин', exact: true }).click()
  await expect(page.getByTestId('today-timers')).toContainText('Чайник')

  await quick.getByRole('button', { name: 'Вес' }).click()
  await expect(page.getByLabel('Вес, кг')).toBeVisible()
  await page.keyboard.press('Escape')

  await quick.getByRole('link', { name: 'Еда' }).click()
  await expect(page.getByRole('dialog', { name: 'Добавить еду' })).toBeVisible()
})
