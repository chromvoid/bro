import type { Page } from '@playwright/test'
import { fakeConversation } from './conversation'
import { APP, expect, overflow, test } from './fixtures'

const requestId = '22222222-2222-4222-8222-222222222222'
const generation = '33333333-3333-4333-8333-333333333333'
const queuedId = '55555555-5555-4555-8555-555555555555'

/** All API routes are synthetic: this scenario needs no backend or family data. */
async function session(page: Page) {
  await page.route('**/v1/**', (route) =>
    route.fulfill({ status: 503, json: { error: 'unmocked_clarification_test_route' } }),
  )
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: {
        id: '00000000-0000-4000-8000-000000000001',
        login: 'clarification-test',
        name: 'Синтетический пользователь',
        locale: 'ru',
        timezone: 'UTC',
        system_admin: false,
        revision: 1,
        scopes: ['session.control'],
      },
    }),
  )
  await page.route('**/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'synthetic-csrf' } }),
  )
  const fake = await fakeConversation(page)
  fake.seed({
    id: requestId,
    state: 'processing',
    text: 'Запиши яблоко 400 г и создай задачу',
    turns: [],
    run_generation: generation,
    intent_version: 2,
    intent_revision: 0,
    can_clarify: true,
  })
  return fake
}

function receipt(key: string, source: string, state = 'accepted') {
  return {
    id: '44444444-4444-4444-8444-444444444444',
    submission_key: key,
    message_id: requestId,
    generation,
    source_message_id: source,
    source_revision: 1,
    intent_revision: 1,
    state,
    reason: null,
    question: null,
    admitted_at: '2026-10-05T09:00:00Z',
    delivered_at: null,
    applied_at: null,
  }
}

const TEXT = 'Нет, яблоко 200 г'

/** The clarification endpoint moves the named queued message into the running request. */
async function moving(page: Page, fake: Awaited<ReturnType<typeof session>>) {
  const keys: string[] = []
  await page.route('**/v1/conversation/clarifications', async (route) => {
    const key = (await route.request().headerValue('Idempotency-Key'))!
    keys.push(key)
    const body = route.request().postDataJSON() as Record<string, string>
    expect(body).toEqual({
      message_id: requestId,
      generation,
      queued_message_id: expect.any(String),
    })
    const value = receipt(key, body.queued_message_id!)
    const moved = fake.messages.find((message) => message.id === body.queued_message_id)!
    moved.state = 'clarification'
    moved.clarification_receipt = value
    await route.fulfill({ status: 202, json: value })
  })
  return keys
}

for (const theme of ['light', 'dark']) {
  for (const textSize of ['100%', '125%']) {
    test(`a follow-up waits in the queue and «Отправить сейчас» moves it: 320px ${theme} ${textSize}`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 320, height: 800 })
      await page.addInitScript((value) => localStorage.setItem('assistant.web.theme', value), theme)
      const fake = await session(page)
      const keys = await moving(page, fake)
      await page.goto(`${APP}assistant`)
      await page.evaluate((size) => (document.documentElement.style.fontSize = size), textSize)
      // No choice of how to send: what is written while Bro answers waits its turn.
      await expect(page.getByTestId('composer')).toBeVisible()
      await expect(page.getByTestId('follow-up-mode')).toHaveCount(0)
      const field = page.getByLabel('Сообщение ассистенту')
      await field.fill(TEXT)
      fake.next('queue')
      await page.getByTestId('composer-send').click()
      await expect.poll(() => fake.posts.length).toBe(1)
      expect(fake.posts[0]!.body.text).toBe(TEXT)
      await expect(field).toHaveValue('')
      const queued = page.locator('[data-queued]')
      await expect(queued).toContainText(TEXT)
      await expect(queued).toContainText('В очереди')
      const actions = page.getByTestId('queued-actions')
      await expect(actions.getByRole('button')).toHaveCount(3)
      for (const button of await actions.getByRole('button').all()) {
        const box = (await button.boundingBox())!
        expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(44)
        expect(box.x + box.width).toBeLessThanOrEqual(320)
      }
      expect(await overflow(page)).toBeLessThanOrEqual(0)
      await actions.getByRole('button', { name: 'Отправить сейчас' }).click()
      await expect(page.getByTestId('clarification-status')).toContainText('Принято')
      await expect(page.locator('[data-queued]')).toHaveCount(0)
      await expect(page.getByTestId('queued-actions')).toHaveCount(0)
      expect(keys).toHaveLength(1)
      expect(fake.posts).toHaveLength(1)
    })
  }
}

test('the running request stops from the composer, where the send button is', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 })
  const fake = await session(page)
  const stops: unknown[] = []
  let accepted: Record<string, unknown> | null = null
  await page.route('**/v1/conversation/controls', async (route) => {
    stops.push(route.request().postDataJSON())
    fake.messages.find((message) => message.id === requestId)!.control_state = 'accepted'
    accepted = {
      id: '77777777-7777-4777-8777-777777777777',
      submission_key: (await route.request().headerValue('Idempotency-Key'))!,
      message_id: requestId,
      generation,
      intent_revision: 0,
      action: 'stop',
      state: 'accepted',
      reason: null,
      admitted_at: '2026-10-05T09:00:00Z',
      settled_at: null,
      execution: null,
    }
    await route.fulfill({ status: 202, json: accepted })
  })
  await page.route('**/v1/conversation/controls/by-key/*', (route) =>
    route.fulfill({ json: accepted }),
  )
  await page.goto(`${APP}assistant`)
  const composer = page.getByTestId('composer')
  const stop = composer.getByRole('button', { name: 'Остановить' })
  await expect(stop).toBeVisible()
  const message = page.getByTestId('exchange')
  await expect(message.getByRole('button', { name: 'Остановить' })).toHaveCount(0)
  const box = (await stop.boundingBox())!
  expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(44)
  expect(box.x + box.width).toBeLessThanOrEqual(320)

  // Written text waits its turn: the same place sends it.
  const field = page.getByLabel('Сообщение ассистенту')
  await field.fill(TEXT)
  await expect(stop).toBeHidden()
  await expect(page.getByTestId('composer-send')).toBeEnabled()
  await field.fill('')
  await stop.click()
  await expect.poll(() => stops).toEqual([{ message_id: requestId, generation, action: 'stop' }])
  await expect(composer.getByRole('button', { name: 'Останавливается…' })).toBeDisabled()
  expect(fake.posts).toHaveLength(0)
})

test('a lost answer repeats the same move, and a refusal leaves the message in the queue', async ({
  page,
}) => {
  const fake = await session(page)
  fake.seed({
    id: queuedId,
    state: 'queued',
    text: TEXT,
    turns: [],
    run_generation: '66666666-6666-4666-8666-666666666666',
  })
  const keys: string[] = []
  let answer: 'lose' | 'refuse' | 'move' = 'lose'
  await page.route('**/v1/conversation/clarifications', async (route) => {
    const key = (await route.request().headerValue('Idempotency-Key'))!
    keys.push(key)
    if (answer === 'lose') return route.abort('failed')
    if (answer === 'refuse')
      return route.fulfill({
        status: 409,
        json: {
          error: 'conflict',
          message: 'Запрос уже закончился, сообщение отправится следующим',
        },
      })
    const value = receipt(key, queuedId)
    const moved = fake.messages.find((message) => message.id === queuedId)!
    moved.state = 'clarification'
    moved.clarification_receipt = value
    return route.fulfill({ status: 202, json: value })
  })
  await page.goto(`${APP}assistant`)
  const now = page.getByRole('button', { name: 'Отправить сейчас' })
  await now.click()
  await expect.poll(() => keys.length).toBe(1)
  await expect(page.locator('[data-queued]')).toContainText(TEXT)
  // Without an answer the move may have happened: trying again repeats the same request.
  answer = 'refuse'
  await expect(now).toBeEnabled()
  await now.click()
  await expect(
    page.getByText('Запрос уже закончился, сообщение отправится следующим'),
  ).toBeVisible()
  expect(keys[1]).toBe(keys[0])
  await expect(page.locator('[data-queued]')).toContainText(TEXT)
  // A refusal changed nothing, so the next try is a new request.
  answer = 'move'
  await now.click()
  await expect(page.getByTestId('clarification-status')).toContainText('Принято')
  expect(keys).toHaveLength(3)
  expect(keys[2]).not.toBe(keys[0])
  expect(fake.posts).toHaveLength(0)
})

test('a queued reply waits its turn and can still be edited or removed', async ({ page }) => {
  const fake = await session(page)
  fake.seed({
    id: queuedId,
    state: 'queued',
    text: 'Это ответ на другое сообщение',
    reply_to_message_id: requestId,
    turns: [],
    run_generation: '66666666-6666-4666-8666-666666666666',
  })
  let posts = 0
  await page.route('**/v1/conversation/clarifications', (route) => {
    posts++
    return route.fulfill({ status: 503, json: { error: 'no_move_should_be_sent' } })
  })
  await page.goto(`${APP}assistant`)
  const actions = page.getByTestId('queued-actions')
  await expect(actions.getByRole('button', { name: 'Изменить' })).toBeVisible()
  await expect(actions.getByRole('button', { name: 'Убрать из очереди' })).toBeVisible()
  await expect(page.getByTestId('send-now')).toHaveCount(0)
  expect(posts).toBe(0)
})

test('a history-only clarification checks its original key', async ({ page }) => {
  const fake = await session(page)
  const key = 'history-only-key'
  const value = receipt(key, queuedId, 'accepted')
  fake.seed({
    id: queuedId,
    state: null,
    text: 'Нет, яблоко 200 г',
    turns: [],
    clarification_receipt: value,
  })
  let reads = 0
  await page.route('**/v1/conversation/clarifications/by-key/*', async (route) => {
    reads++
    expect(new URL(route.request().url()).pathname.endsWith(key)).toBe(true)
    const applied = { ...value, state: 'applied', applied_at: '2026-10-05T09:00:02Z' }
    fake.messages.find((message) => message.id === queuedId)!.clarification_receipt = applied
    await route.fulfill({ json: applied })
  })
  await page.goto(`${APP}assistant`)
  await expect(page.getByTestId('clarification-status')).toContainText('Принято')
  await page.getByRole('button', { name: 'Проверить уточнение' }).click()
  await expect(page.getByTestId('clarification-status')).toContainText('Учтено')
  await page.getByRole('button', { name: 'Открыть исходный запрос' }).click()
  await expect(page).toHaveURL(new RegExp(`message=${requestId}`))
  expect(reads).toBe(1)
  expect(fake.posts).toHaveLength(0)
})
