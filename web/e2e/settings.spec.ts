import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'
import { memberFor } from './members'

test.describe('as the owner', () => {
  test('limits, theme and capabilities are personal settings', async ({ page }) => {
    await page.goto(`${APP}settings`)
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()
    // Without a limit set, usage counters are a technical detail.
    const limits = page.getByTestId('limits')
    await expect(limits).toHaveCount(0)

    // The theme is chosen in the profile, not here.
    await expect(page.getByRole('radio', { name: 'Тёмная' })).toHaveCount(0)

    await page.getByRole('link', { name: /Возможности приложения/ }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Возможности' })).toBeVisible()
    await expect(page.getByTestId('features')).toContainText('Питание')
    // Models and providers of the installation are technical details.
    await expect(page.getByTestId('inventory-modules')).toHaveCount(0)
    await page.goto(`${APP}settings/devices`)
    await expect(page.getByRole('heading', { level: 1, name: 'Устройства' })).toBeVisible()
    await expect(page.getByTestId('add-agent')).toHaveCount(0)

    await page.goto(`${APP}settings`)
    await page.getByTestId('developer').getByRole('switch').click()
    await expect(limits).toContainText('Обращения к ассистенту')
    await expect(limits).toContainText('Диктовка')
    await expect(limits).toContainText(
      /Счётчики обнулятся (сегодня|завтра) в \d\d:\d\d по вашему времени\.$/,
    )
    await page.getByRole('link', { name: /Возможности приложения/ }).click()
    await expect(page.getByTestId('inventory-modules')).toContainText('Разговор и темы')
    // A provider configured at start is not claimed to be working.
    await expect(page.getByTestId('inventory-modules')).not.toContainText('Работает')
  })

  test('old addresses lead to the new screens', async ({ page }) => {
    await page.goto(`${APP}modules`)
    await expect(page).toHaveURL(/\/settings\/capabilities$/)
    // The beta mount under /app/ redirects to the root and keeps the query.
    await page.goto('/app/settings/access?from=beta')
    await expect(page).toHaveURL(/:\d+\/settings\/sharing\?from=beta$/)
    await expect(page.getByRole('heading', { name: 'Доступ и обмен' }).first()).toBeVisible()
    // What others opened to me moved to Circle.
    await page.goto(`${APP}settings/shared`)
    await expect(page).toHaveURL(/\/circle\/shared$/)
    await expect(page.getByRole('heading', { name: 'Открыто мне' }).first()).toBeVisible()
  })

  test('signing out from settings returns to sign-in', async ({ page }) => {
    test.skip(!ISOLATED, 'the development backend signs in automatically')
    await page.goto(`${APP}settings`)
    await page.getByRole('button', { name: 'Выйти из аккаунта' }).click()
    await expect(page.getByRole('heading', { name: 'С возвращением' })).toBeVisible()
  })

  test('signing out from the profile menu warns about a write that is not settled', async ({
    page,
  }) => {
    test.skip(!ISOLATED, 'the development backend signs in automatically')
    await page.goto(`${APP}tasks`)
    // The write leaves the device but its outcome never comes back.
    await page.route('**/v1/tasks/operations', (route) => route.abort('failed'))
    const lost = page.waitForEvent('requestfailed', (request) =>
      request.url().includes('/v1/tasks/operations'),
    )
    await page.getByPlaceholder('Новая задача').fill('Задача без ответа сервера')
    await page.getByRole('button', { name: 'Добавить' }).click()
    await lost
    // On a phone the error toast covers the header; close it as a person would.
    const failure = page.locator('[data-sonner-toast]').filter({ hasText: 'Нет соединения' })
    await failure.getByRole('button', { name: 'Закрыть' }).click()
    await expect(failure).toBeHidden()

    const menu = page.getByTestId('profile-menu').filter({ visible: true })
    const dialog = page.getByTestId('logout-dialog')
    await menu.click()
    await page.getByTestId('logout').click()
    await expect(dialog).toContainText('Выйти из аккаунта?')
    await dialog.getByRole('button', { name: 'Остаться' }).click()
    await expect(dialog).toBeHidden()
    await expect(page).toHaveURL(/\/tasks/)

    await menu.click()
    await page.getByTestId('logout').click()
    await dialog.getByRole('button', { name: 'Всё равно выйти' }).click()
    await expect(page.getByRole('heading', { name: 'С возвращением' })).toBeVisible()
  })
})

test.describe('as a member', () => {
  test.skip(!ISOLATED, 'changes an account: needs the disposable backend')
  test.use({ asMember: true })

  test('the account name, language and zone are saved together', async ({ page }, info) => {
    const member = memberFor(info.project.name)
    await page.goto(`${APP}settings`)
    await page.getByTestId('account-card').click()
    await expect(page.getByLabel('Язык')).toHaveValue('ru')
    await page.getByLabel('Часовой пояс').fill('Mars/Olympus')
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByText(/Выберите часовой пояс из списка/)).toBeVisible()
    await page.getByLabel('Часовой пояс').fill('Europe/Belgrade')
    await page.getByLabel('Имя').fill(`${member.name} (новое имя)`)
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByText('Сохранено')).toBeVisible()
    await expect(page.getByTestId('account-card')).toContainText(`${member.name} (новое имя)`)
    // Leave the name as it was for the other tests of this run.
    await page.getByTestId('account-card').click()
    await page.getByLabel('Имя').fill(member.name)
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByTestId('account-card')).toContainText(member.name)
  })

  test('a summary is opened after a check, read by the other person, then revoked', async ({
    page,
    browser,
    baseURL,
  }, info) => {
    const member = memberFor(info.project.name)
    await page.goto(`${APP}settings/sharing`)
    const nutrition = page.getByTestId('access-nutrition')
    await nutrition.getByRole('button', { name: 'Открыть просмотр человеку' }).click()
    await page.getByLabel('Логин получателя').fill('nobody-with-this-login')
    await page.getByRole('button', { name: 'Продолжить' }).click()
    await expect(page.getByText('Пользователь с таким логином не найден.')).toBeVisible()
    await page.getByLabel('Логин получателя').fill('@e2e-owner')
    await page.getByRole('button', { name: 'Продолжить' }).click()
    // S05: what exactly the person will see, before anything is granted.
    const check = page.getByTestId('access-check')
    await expect(check).toContainText('Тестовый владелец (@e2e-owner) увидит питание:')
    await expect(check).toContainText('Только калории за день')
    await check.getByRole('button', { name: 'Открыть доступ' }).click()
    await expect(page.getByText('Доступ открыт: Тестовый владелец')).toBeVisible()
    await expect(nutrition).toContainText('Тестовый владелец')
    await expect(nutrition).toContainText('Сводка')

    // The owner of the disposable backend reads it in their own session.
    const origin = new URL(baseURL!).origin
    const other = await browser.newContext({ baseURL: origin })
    try {
      await other.request.post('/v1/auth/development', { data: {}, headers: { Origin: origin } })
      const reader = await other.newPage()
      await reader.goto(`${APP}circle/shared`)
      const row = reader
        .getByTestId('shared-list')
        .getByRole('listitem')
        .filter({
          hasText: `@${member.login}`,
        })
      await expect(row).toContainText('Сводка · только просмотр')
      await row.getByRole('button').click()
      await expect(reader.getByTestId('shared-view')).toContainText('Записано за день')

      await nutrition.getByRole('button', { name: 'Отозвать доступ' }).click()
      await page.getByRole('dialog').getByRole('button', { name: 'Отозвать доступ' }).click()
      await expect(page.getByText('Доступ отозван: Тестовый владелец')).toBeVisible()
      await expect(nutrition).toContainText('Пока никому не открыто.')

      await reader.reload()
      await expect(reader.getByTestId('shared-revoked')).toContainText('Доступ изменился')
      await expect(reader.getByTestId('shared-view')).toHaveCount(0)
    } finally {
      await other.close()
    }
  })
})
