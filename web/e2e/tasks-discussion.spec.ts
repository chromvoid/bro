import { request, type Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'
import { memberFor } from './members'

test.skip(!ISOLATED, 'writes synthetic task discussions: requires the disposable backend')

async function setup(page: Page, baseURL: string, projectName: string) {
  const origin = new URL(baseURL).origin
  await page.goto(`${APP}tasks?scope=all`)
  const { token } = await (await page.request.get(`${origin}/v1/auth/csrf`)).json()
  const headers = { Origin: origin, 'X-CSRF-Token': token }
  const post = async (path: string, body: object) => {
    const response = await page.request.post(`${origin}${path}`, {
      data: body,
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  const space = await post('/v1/spaces', { name: `Discussion ${crypto.randomUUID()}` })
  const otherMember = memberFor(projectName)
  await post(`/v1/spaces/${space.id}/invitations`, { user_id: otherMember.id })
  const other = await request.newContext({
    baseURL: origin,
    storageState: { cookies: otherMember.cookies, origins: [] },
    extraHTTPHeaders: { Origin: origin },
  })
  const otherToken = (await (await other.get('/v1/auth/csrf')).json()).token
  const joined = await other.post(`/v1/spaces/${space.id}/join`, {
    data: {},
    headers: { 'X-CSRF-Token': otherToken },
  })
  expect(joined.ok(), await joined.text()).toBe(true)
  const created = await post('/v1/tasks/operations', {
    kind: 'list.create',
    payload: { name: 'Discussion project', list_kind: 'project', space_id: space.id },
  })
  const list = created.lists[0].id as string
  const receipt = await post('/v1/tasks/operations', {
    kind: 'task.create',
    payload: { title: 'Discussion task' },
    target_resource_id: list,
  })
  const task = receipt.tasks[0] as { id: string; revision: number }
  const channelResponse = await page.request.get(`${origin}/v1/tasks/items/${task.id}/discussion`)
  expect(channelResponse.ok()).toBe(true)
  const channel = (await channelResponse.json()).channel_id as string
  const sendOther = async (text: string) => {
    const response = await other.post(`/v1/channels/${channel}/messages`, {
      data: { text },
      headers: { 'X-CSRF-Token': otherToken, 'Idempotency-Key': crypto.randomUUID() },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return (await response.json()).message_id as string
  }
  const sendOwn = (text: string) => post(`/v1/channels/${channel}/messages`, { text })
  const readTask = async () =>
    await (await page.request.get(`${origin}/v1/tasks/items/${task.id}`)).json()
  return { list, task, channel, other, sendOwn, sendOther, readTask }
}

test('incoming messages refresh unchanged cards, badge opens discussion, and visible reading clears unread', async ({
  page,
  baseURL,
}, testInfo) => {
  const fixture = await setup(page, baseURL!, testInfo.project.name)
  try {
    await page.goto(`${APP}tasks?list=${fixture.list}`)
    const card = page.getByTestId('task-card').filter({ hasText: 'Discussion task' })
    await expect(card.locator('[data-task-discussion]')).toHaveCount(0)
    await fixture.sendOwn('Own message')
    const badge = card.locator('[data-task-discussion]')
    await expect(badge).toHaveAccessibleName('Обсуждение. Сообщений: 1')
    await fixture.sendOther('Incoming message')
    await expect(badge).toHaveAccessibleName('Обсуждение. Сообщений: 2, непрочитанных: 1')
    expect((await fixture.readTask()).revision).toBe(fixture.task.revision)
    await card.locator(`[data-task="${fixture.task.id}"]`).click()
    const panel = page.getByTestId('task-panel')
    await expect(panel.getByRole('tab', { name: 'Задача', exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect((await fixture.readTask()).discussion.unread).toBe(1)
    await panel.getByRole('button', { name: 'Закрыть', exact: true }).click()
    await badge.click()
    await expect(page).toHaveURL(/task_tab=discussion/)
    await expect(panel.getByRole('tab', { name: 'Обсуждение', exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await panel.getByTestId('task-discussion-read-marker').scrollIntoViewIfNeeded()
    await expect(badge).toHaveAttribute('aria-label', 'Обсуждение. Сообщений: 2')
    await expect(
      panel.getByRole('button', { name: 'Позвать ассистента', exact: true }),
    ).toBeVisible()
    await panel.getByRole('button', { name: 'Закрыть', exact: true }).click()
    await expect(page).not.toHaveURL(/task_tab=/)
    await page.goto(`${APP}tasks?list=${fixture.list}&layout=list`)
    await page.getByTestId('tasks').locator('[data-task-discussion]').click()
    await expect(panel.getByRole('tab', { name: 'Обсуждение', exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  } finally {
    await fixture.other.dispose()
  }
})

test('older history and hidden details never acknowledge the latest message before its marker is shown', async ({
  page,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000)
  const fixture = await setup(page, baseURL!, testInfo.project.name)
  try {
    for (let index = 0; index < 61; index++) await fixture.sendOwn(`History ${index}`)
    const latest = await fixture.sendOther('Newest incoming')
    const reads: string[] = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === `/v1/channels/${fixture.channel}/read`)
        reads.push(request.postDataJSON().through_message_id)
    })
    await page.goto(`${APP}tasks?list=${fixture.list}&task=${fixture.task.id}&task_tab=discussion`)
    const panel = page.getByTestId('task-panel')
    await expect(panel.locator('article')).toHaveCount(60)
    expect(reads).toEqual([])
    await panel.getByRole('button', { name: 'Более ранние сообщения', exact: true }).click()
    await expect(panel.locator('article')).toHaveCount(62)
    expect(reads).toEqual([])
    await panel.getByTestId('task-discussion-read-marker').scrollIntoViewIfNeeded()
    await expect.poll(() => reads).toEqual([latest])
    await panel.getByRole('tab', { name: 'Задача', exact: true }).click()
    const hidden = await fixture.sendOther('Arrived while details are shown')
    const badge = page.getByTestId('task-card').locator('[data-task-discussion]')
    await expect(badge).toHaveAttribute('aria-label', 'Обсуждение. Сообщений: 63, непрочитанных: 1')
    expect(reads).toEqual([latest])
    await panel.getByRole('tab', { name: 'Обсуждение', exact: true }).click()
    await expect(panel.getByText('Arrived while details are shown', { exact: true })).toBeVisible()
    await panel.getByTestId('task-discussion-read-marker').scrollIntoViewIfNeeded()
    await expect.poll(() => reads.at(-1)).toBe(hidden)
    await expect(badge).toHaveAttribute('aria-label', 'Обсуждение. Сообщений: 63')
  } finally {
    await fixture.other.dispose()
  }
})
