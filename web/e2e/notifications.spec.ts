import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(!ISOLATED, 'changes settings: needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)')
// Each browser has its own member: the two projects never edit one person's settings at once.
test.use({ asMember: true })

test('notification settings keep quiet hours and topics, and the test reaches the person', async ({
  page,
}) => {
  await page.goto(`${APP}settings/notifications`)
  await expect(page.getByRole('heading', { level: 1, name: 'Оповещения' })).toBeVisible()
  // The disposable backend has its own VAPID key, so push is offered unless the browser refuses
  // notifications (Playwright's Chromium) or has no Push API (WebKit as an iPhone).
  await expect(
    page
      .getByTestId('push-toggle')
      .or(page.getByText('Уведомления запрещены в настройках устройства или браузера'))
      .or(page.getByText('Этот браузер не умеет получать уведомления')),
  ).toBeVisible()
  await expect(page.getByText('Сервер пока не настроен')).toBeHidden()
  // Every topic the plugins declare has a name, not its identifier («tasks.assistant_result»).
  const topics = page.getByRole('region', { name: 'Что присылать на устройства' })
  await expect(topics.getByRole('switch', { name: 'Напоминания' })).toBeVisible()
  await expect(topics.getByRole('switch', { name: /^[a-z_]+\.[a-z_]+$/ })).toHaveCount(0)
  const end = page.getByLabel('До')
  const next = (await end.inputValue()) === '07:30' ? '08:00' : '07:30'
  await end.fill(next)
  await page.getByRole('switch', { name: 'Проверка оповещений' }).click()
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText('Настройки сохранены')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('До')).toHaveValue(next)

  await page.getByTestId('push-test').click()
  await expect(page.getByText('Отправлено — проверьте устройства')).toBeVisible()
  await expect(
    page.locator('[data-sonner-toast]').filter({ hasText: 'Проверка оповещений' }),
  ).toBeVisible()
})
