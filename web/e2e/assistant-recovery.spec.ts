import type { Page } from '@playwright/test'
import { fakeConversation } from './conversation'
import { APP, expect, test } from './fixtures'

const today = new Date()
today.setUTCHours(9, 0, 0, 0)
const oldTime = new Date(today.getTime() - 86_400_000).toISOString()
const oldMessage = 'old-message'
const oldKey = 'old-submission'

function seedHistory(fake: Awaited<ReturnType<typeof fakeConversation>>) {
  fake.seed({
    id: oldMessage,
    created_at: oldTime,
    state: 'completed',
    text: 'Синтетический обед',
    turns: [],
  })
  // The completed original is outside the current server page, exactly as in a long-lived chat.
  for (let i = 0; i < 50; i++)
    fake.seed({
      id: `recent-${i}`,
      created_at: new Date(today.getTime() + i * 60_000).toISOString(),
      state: 'completed',
      text: `Новое сообщение ${i}`,
      turns: [],
    })
}

async function saveAccepted(page: Page) {
  await page.evaluate(
    async ({ createdAt, key, messageId }) => {
      const name = (await indexedDB.databases()).find((database) =>
        database.name?.startsWith('assistant-web/'),
      )?.name
      if (!name) throw new Error('Account store not ready')
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open(name)
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      })
      try {
        await new Promise<void>((resolve, reject) => {
          const tx = db.transaction('submissions', 'readwrite')
          tx.objectStore('submissions').put({
            key,
            method: 'POST',
            endpoint: '/v1/conversation/messages',
            lookup: `/v1/conversation/submissions/by-key/${key}`,
            found: 'accepted',
            body: JSON.stringify({ text: 'Синтетический обед' }),
            createdAt,
            state: 'accepted',
            response: { message_id: messageId, state: 'queued' },
            failure: null,
            context: {},
          })
          tx.oncomplete = () => resolve()
          tx.onerror = () => reject(tx.error)
        })
      } finally {
        db.close()
      }
    },
    { createdAt: Date.parse(oldTime), key: oldKey, messageId: oldMessage },
  )
}

async function hasSubmission(page: Page, key: string): Promise<boolean> {
  return page.evaluate(async (key) => {
    const name = (await indexedDB.databases()).find((database) =>
      database.name?.startsWith('assistant-web/'),
    )?.name
    if (!name) return false
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(name)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    try {
      return await new Promise<boolean>((resolve, reject) => {
        const request = db.transaction('submissions').objectStore('submissions').get(key)
        request.onsuccess = () => resolve(!!request.result)
        request.onerror = () => reject(request.error)
      })
    } finally {
      db.close()
    }
  }, key)
}

test('an old delivered journal entry is settled outside the first history page without resending', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  seedHistory(fake)
  let exactReads = 0
  page.on('request', (request) => {
    if (
      request.method() === 'GET' &&
      new URL(request.url()).searchParams.get('message_id') === oldMessage
    )
      exactReads++
  })
  await page.goto(`${APP}assistant`)
  await expect(page.getByText('Новое сообщение 49', { exact: true })).toBeVisible()
  await saveAccepted(page)
  await page.reload()
  await expect.poll(() => exactReads).toBeGreaterThan(0)
  await expect.poll(() => hasSubmission(page, oldKey)).toBe(false)
  await expect(page.getByTestId('pending-send')).toHaveCount(0)
  await expect(page.getByText('Синтетический обед', { exact: true })).toHaveCount(0)
  expect(fake.posts).toHaveLength(0)
})

test('an unavailable old message stays delivered on its own date and can be opened without resending', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  seedHistory(fake)
  fake.answer('recent-49', {
    reply: 'Записал сегодняшний завтрак.',
    services: [
      { service: 'nutrition', status: 'completed', receipt_text: 'Творог 120 г — 110 ккал' },
    ],
  })
  // Keep the last page compact for a visual regression of the two separate calendar days.
  await page.route(/\/v1\/conversation\/messages$/, (route) =>
    route.request().method() === 'GET'
      ? route.fulfill({ json: { messages: [fake.messages.at(-1)], before: 'recent-49' } })
      : route.fallback(),
  )
  const oldUrl = '**/v1/conversation/messages?message_id=old-message'
  await page.route(oldUrl, (route) =>
    route.fulfill({ status: 503, json: { error: 'unavailable' } }),
  )
  await page.goto(`${APP}assistant`)
  await expect(page.getByText('Новое сообщение 49', { exact: true })).toBeVisible()
  await saveAccepted(page)
  await page.reload()
  await expect(page.getByTestId('reply')).toContainText('Записал сегодняшний завтрак.')
  await expect(page.getByTestId('recorded-result')).toHaveCount(0)
  await expect(page.getByTestId('reply')).not.toContainText('Творог 120 г — 110 ккал')
  const card = page.getByTestId('pending-send')
  await expect(card).toHaveAttribute('data-status', 'delivered')
  await expect(card).toContainText('Сообщение доставлено')
  await expect(card.locator('time')).toHaveAttribute('datetime', oldTime)
  await expect(card.locator('.animate-spin')).toHaveCount(0)
  await expect(card.getByRole('button', { name: 'Отправить ещё раз' })).toHaveCount(0)
  expect(
    await page
      .locator('[data-day]')
      .evaluateAll((elements) => elements.map((element) => element.getAttribute('data-day'))),
  ).toEqual([oldTime.slice(0, 10), today.toISOString().slice(0, 10)])
  // A missing admission lookup does not revoke the already recorded admission.
  await card.getByRole('button', { name: 'Проверить результат' }).click()
  await expect(card.getByRole('button', { name: 'Проверить результат' })).toBeEnabled()
  await expect(card).toHaveAttribute('data-state', 'accepted')
  expect(await hasSubmission(page, oldKey)).toBe(true)
  expect(fake.posts).toHaveLength(0)

  await page.unroute(oldUrl)
  await card.getByRole('button', { name: 'Открыть сообщение' }).click()
  await expect(page).toHaveURL(/message=old-message/)
  await expect(page.getByTestId('exchange').filter({ hasText: 'Синтетический обед' })).toBeVisible()
  await expect.poll(() => hasSubmission(page, oldKey)).toBe(false)
  expect(fake.posts).toHaveLength(0)
})
