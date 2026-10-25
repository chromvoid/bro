import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.describe('chat model settings', () => {
  test.skip(!ISOLATED, 'settings writes require the disposable backend')
  // Installation settings are shared by all browser projects.
  test('an administrator saves Codex settings, reloads them and restores the defaults', async ({
    page,
  }, info) => {
    test.skip(info.project.name !== 'chromium', 'one writer for installation settings')
    await page.goto(`${APP}settings`)
    await page.getByRole('link', { name: /Управление установкой/ }).click()
    await page.getByRole('link', { name: /^Модели/ }).click()
    await expect(page.getByRole('heading', { name: 'Модели', exact: true })).toBeVisible()
    // The chat comes first; the calls plugins declare (research, food search) have their own.
    const chat = page.getByRole('group', { name: 'Чат', exact: true })
    const model = chat.getByLabel('Модель', { exact: true })
    const reasoning = chat.getByLabel('Уровень рассуждений', { exact: true })
    await expect(model).toHaveValue('gpt-6.1-sol')
    await expect(reasoning).toHaveValue('low')
    await expect(page.getByLabel(/Claude/)).toHaveCount(0)
    await model.selectOption('gpt-6-luna')
    await reasoning.selectOption('medium')
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByText('Настройки моделей сохранены')).toBeVisible()
    await page.reload()
    await expect(model).toHaveValue('gpt-6-luna')
    await expect(reasoning).toHaveValue('medium')
    await page.getByRole('button', { name: 'Вернуть значения по умолчанию' }).click()
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByText('Настройки моделей сохранены')).toBeVisible()
    await page.reload()
    await expect(model).toHaveValue('gpt-6.1-sol')
    await expect(reasoning).toHaveValue('low')
  })

  test.describe('as a member', () => {
    test.use({ asMember: true })
    test('installation model settings require an administrator', async ({ page }) => {
      await page.goto(`${APP}admin/chat`)
      await expect(page).toHaveURL(/\/no-access$/)
      expect((await page.request.get('/v1/admin/chat-settings')).status()).toBe(403)
    })
  })

  test('the model controls fit a mobile screen', async ({ page }, info) => {
    test.skip(info.project.name !== 'webkit', 'mobile viewport')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`${APP}admin/chat`)
    const chat = page.getByRole('group', { name: 'Чат', exact: true })
    await expect(chat.getByLabel('Модель', { exact: true })).toBeVisible()
    await expect(chat.getByLabel('Уровень рассуждений', { exact: true })).toBeVisible()
    await expect(page.getByLabel(/Claude/)).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Сохранить', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Вернуть значения по умолчанию' })).toBeVisible()
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true)
  })
})
