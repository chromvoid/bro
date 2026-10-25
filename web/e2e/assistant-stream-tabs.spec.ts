import type { Page } from '@playwright/test'
import { fakeConversation } from './conversation'
import { APP, expect, test } from './fixtures'

interface Fixture {
  active: number
  histories: number
  send(id: number): void
  visibility(value: DocumentVisibilityState): void
}
const state = (page: Page) =>
  page.evaluate(() => {
    const value = (window as unknown as { accountEventsFixture: Fixture }).accountEventsFixture
    return { active: value.active, histories: value.histories }
  })

test('two tabs share one account stream and refresh after fanout and leader replacement', async ({
  page,
  context,
}) => {
  // Keep both tabs visible in headless browsers; locking and cross-tab broadcasting are real.
  // Only the long-running SSE transport is synthetic, with no writes or domain operations.
  await context.addInitScript(() => {
    let visibility: DocumentVisibilityState = 'visible'
    Object.defineProperty(document, 'visibilityState', {
      get: () => visibility,
      configurable: true,
    })
    const original = window.fetch.bind(window)
    const channels = new Set<ReadableStreamDefaultController<Uint8Array>>()
    const encoder = new TextEncoder()
    const fixture = {
      active: 0,
      histories: 0,
      send(id: number) {
        for (const channel of channels)
          channel.enqueue(
            encoder.encode(
              `id: ${id}\nevent: assistant.changed\ndata: {"topic":"assistant.changed"}\n\n`,
            ),
          )
      },
      visibility(value: DocumentVisibilityState) {
        visibility = value
        document.dispatchEvent(new Event('visibilitychange'))
      },
    }
    Object.assign(window, { accountEventsFixture: fixture })
    window.fetch = (input, options) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (
        /\/v1\/conversation\/messages(?:\?|$)/.test(url) &&
        (!options?.method || options.method === 'GET')
      )
        fixture.histories++
      if (!/\/v1\/me\/events(?:\?|$)/.test(url)) return original(input, options)
      let writer: ReadableStreamDefaultController<Uint8Array>
      return Promise.resolve(
        new Response(
          new ReadableStream<Uint8Array>({
            start(value) {
              writer = value
              channels.add(value)
              fixture.active++
            },
            cancel() {
              if (channels.delete(writer)) fixture.active--
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
      )
    }
  })
  await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  await expect.poll(async () => (await state(page)).active).toBe(1)
  const second = await context.newPage()
  try {
    await fakeConversation(second)
    await second.goto(`${APP}assistant`)
    await expect(second.getByLabel('Сообщение ассистенту')).toBeVisible()
    await expect.poll(async () => (await state(page)).active + (await state(second)).active).toBe(1)
    const before = (await state(second)).histories
    await page.evaluate(() =>
      (window as unknown as { accountEventsFixture: Fixture }).accountEventsFixture.send(999),
    )
    await expect.poll(async () => (await state(second)).histories).toBeGreaterThan(before)
    const returning = (await state(second)).histories
    await second.evaluate(() => {
      const fixture = (window as unknown as { accountEventsFixture: Fixture }).accountEventsFixture
      fixture.visibility('hidden')
      fixture.visibility('visible')
    })
    await expect.poll(async () => (await state(second)).histories).toBeGreaterThan(returning)
    // Hide the leader: it releases its Web Lock after closing the transport.
    const promoted = (await state(second)).histories
    await page.evaluate(() =>
      (window as unknown as { accountEventsFixture: Fixture }).accountEventsFixture.visibility(
        'hidden',
      ),
    )
    await expect.poll(async () => (await state(page)).active).toBe(0)
    await expect.poll(async () => (await state(second)).active).toBe(1)
    await expect.poll(async () => (await state(second)).histories).toBeGreaterThan(promoted)
  } finally {
    await second.close()
  }
})

test('IndexedDB refusal keeps history readable and prevents unjournaled sends', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'indexedDB', {
      value: {
        open() {
          throw new DOMException('Synthetic storage refusal', 'SecurityError')
        },
      },
      configurable: true,
    })
  })
  const fake = await fakeConversation(page)
  fake.seed({ id: 'saved', state: 'completed', text: 'Сохранённая история доступна', turns: [] })
  await page.goto(`${APP}assistant`)
  await expect(page.getByText('Сохранённая история доступна', { exact: true })).toBeVisible()
  await expect(
    page.getByText('Не удалось открыть сохранённый черновик на этом устройстве.'),
  ).toBeVisible()
  await expect(page.getByLabel('Сообщение ассистенту')).toBeDisabled()
  expect(fake.posts).toHaveLength(0)
})
