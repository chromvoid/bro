import { administrator, ISOLATED, uniqueLogin } from './backend'
import { APP, expect, test } from './fixtures'

test.use({ signedOut: true })

test.describe('sign-in', () => {
  test.skip(!ISOLATED, 'needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)')

  test('invitation → account → first run; then wrong password keeps the login', async ({
    page,
    baseURL,
  }) => {
    const admin = await administrator(baseURL!)
    const { token } = (await admin.post('/v1/admin/tokens', {
      scopes: ['nutrition.read', 'nutrition.write', 'tasks.read', 'tasks.write'],
    })) as { token: string }
    await admin.api.dispose()

    const login = uniqueLogin('maria')
    const password = 'Синтетический-пароль-1'
    await page.goto(`${APP}login#kind=invite&token=${token}`)
    // The one-time token leaves the address bar immediately.
    await expect(page).not.toHaveURL(/token=/)
    await expect(page.getByRole('heading', { name: 'Добро пожаловать' })).toBeVisible()
    await page.getByLabel('Как вас называть').fill('Мария')
    await page.getByLabel('Логин', { exact: true }).fill(login)
    await page.getByLabel('Пароль', { exact: true }).fill('short')
    await page.getByLabel('Повторите пароль').fill('other')
    await page.getByRole('button', { name: 'Принять приглашение' }).click()
    await expect(page.getByText('Пароли не совпадают.')).toBeVisible()
    await page.getByLabel('Пароль', { exact: true }).fill(password)
    await page.getByLabel('Повторите пароль').fill(password)
    await page.getByRole('button', { name: 'Принять приглашение' }).click()
    await expect(page.getByRole('heading', { name: 'Здравствуйте, Мария' })).toBeVisible()
    await expect(page.getByRole('link', { name: /Записать, что съели/ })).toBeVisible()

    // Sign out removes the session; the used invitation no longer works.
    await page.getByTestId('profile-menu').filter({ visible: true }).click()
    await page.getByTestId('logout').click()
    await expect(page.getByRole('heading', { name: 'С возвращением' })).toBeVisible()
    await page.goto(`${APP}login#kind=invite&token=${token}`)
    await page.getByLabel('Как вас называть').fill('Мария')
    await page.getByLabel('Логин', { exact: true }).fill(uniqueLogin('maria'))
    await page.getByLabel('Пароль', { exact: true }).fill(password)
    await page.getByLabel('Повторите пароль').fill(password)
    await page.getByRole('button', { name: 'Принять приглашение' }).click()
    await expect(page.getByRole('heading', { name: 'Ссылка больше не действует' })).toBeVisible()
    await page.getByRole('button', { name: 'Ко входу' }).click()

    await page.getByLabel('Логин', { exact: true }).fill(login)
    await page.getByLabel('Пароль', { exact: true }).fill('wrong-password-123')
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page.getByText('Проверьте данные')).toBeVisible()
    await expect(page.getByLabel('Логин', { exact: true })).toHaveValue(login)
    await expect(page.getByLabel('Пароль', { exact: true })).toHaveValue('')
    await page.getByLabel('Пароль', { exact: true }).fill(password)
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()

    // The sign-in is named after the browser and platform, not just «Браузер».
    await page.goto(`${APP}settings/devices`)
    await expect(
      page.getByText(/^(Chrome|Safari) · (Windows|Mac|Linux|iPhone)$/).first(),
    ).toBeVisible()
  })

  test('a reset link sets a new password and returns to sign-in', async ({ page, baseURL }) => {
    const admin = await administrator(baseURL!)
    const invite = (await admin.post('/v1/admin/tokens', { scopes: [] })) as { token: string }
    const login = uniqueLogin('ivan')
    const response = await admin.api.post('/v1/auth/redeem', {
      data: {
        token: invite.token,
        password: 'Первый-пароль-12',
        login,
        name: 'Иван',
        timezone: 'Europe/Belgrade',
      },
    })
    expect(response.ok()).toBe(true)
    const users = (await (await admin.api.get('/v1/admin/users')).json()) as {
      users: Array<{ id: string; login: string }>
    }
    const user = users.users.find((entry) => entry.login === login)!
    const reset = (await admin.post('/v1/admin/tokens', { user_id: user.id })) as { token: string }
    await admin.api.dispose()

    await page.goto(`${APP}login#kind=reset&token=${reset.token}`)
    await expect(page.getByRole('heading', { name: 'Новый пароль' })).toBeVisible()
    await page.getByLabel('Пароль', { exact: true }).fill('Второй-пароль-12')
    await page.getByLabel('Повторите пароль').fill('Второй-пароль-12')
    await page.getByRole('button', { name: 'Сохранить пароль' }).click()
    await expect(page.getByText('Пароль сохранён')).toBeVisible()
    await page.getByLabel('Логин', { exact: true }).fill(login)
    await page.getByLabel('Пароль', { exact: true }).fill('Второй-пароль-12')
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  })
})

test('an unauthenticated visit asks to sign in and remembers the page', async ({
  page,
  context,
}) => {
  test.skip(!ISOLATED, 'the development backend signs in automatically')
  await context.clearCookies()
  await page.goto(`${APP}tasks`)
  await expect(page.getByRole('heading', { name: 'С возвращением' })).toBeVisible()
  await expect(page).toHaveURL(/login\?next=(%2F|\/)tasks$/)
})
