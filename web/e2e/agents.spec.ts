import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(!ISOLATED, 'connects agents: needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)')
// An administrator connects agents with developer mode turned on.
test.use({ developer: true })

test('an agent is connected with chosen rights, tells the person over MCP and is switched off', async ({
  page,
  baseURL,
}) => {
  const name = `Claude ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`
  const title = `Codex закончил ${name}`
  const origin = new URL(baseURL!).origin
  await page.goto(`${APP}settings/devices`)
  await page.getByRole('button', { name: 'Ещё', exact: true }).click()
  await page.getByTestId('add-agent').click()
  await page.getByLabel('Название').fill(name)
  await page.getByRole('checkbox', { name: 'Задачи' }).click()
  await page.getByRole('button', { name: 'Создать токен' }).click()
  const claude = page.getByTestId('agent-claude')
  await expect(claude).toContainText(`${origin}/v1/mcp`)
  await expect(page.getByTestId('agent-codex')).toContainText('ASSISTANT_AGENT_TOKEN')
  const token = /Bearer (\S+)"/.exec((await claude.textContent()) ?? '')![1]!

  const call = (method: string, params: object = {}) =>
    page.request.post(`${origin}/v1/mcp`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { jsonrpc: '2.0', id: 1, method, params },
    })
  const told = await call('tools/call', {
    name: 'notify',
    arguments: { title, severity: 'important', idempotency_key: 'done-1' },
  })
  expect(told.ok(), await told.text()).toBe(true)
  // A refused tool call is still HTTP 200, with isError: the notification must have been written.
  expect((await told.json()).result, await told.text()).toMatchObject({
    isError: false,
    structuredContent: { notification_id: expect.any(String), idempotency_key: 'done-1' },
  })
  await page.getByRole('button', { name: 'Готово' }).click()
  const row = page.getByTestId('device-agent').filter({ hasText: name })
  await expect(row).toContainText('Изменяет: Задачи')
  await expect(page.locator('[data-sonner-toast]').filter({ hasText: title })).toBeVisible()

  await row.getByRole('button', { name: 'Отключить' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Отключить' }).click()
  await expect(row).toBeHidden()
  expect((await call('ping')).status()).toBe(401)
})
