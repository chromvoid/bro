import type { Page } from '@playwright/test'
import { fakeConversation } from './conversation'
import { APP, expect, test } from './fixtures'

interface Fixture {
  active: number
  requests: string[]
  send(data: Record<string, unknown> | null): void
}
const fixture = (page: Page) =>
  page.evaluate(() => {
    const value = (window as unknown as { historyFixture: Fixture }).historyFixture
    return { active: value.active, requests: value.requests.slice() }
  })
const send = (page: Page, data: Record<string, unknown> | null) =>
  page.evaluate(
    (value) => (window as unknown as { historyFixture: Fixture }).historyFixture.send(value),
    data,
  )
/** History reads after `from`, by kind: the newest page, an earlier page, or one exchange. */
async function readsSince(page: Page, from: number) {
  return (await fixture(page)).requests.slice(from).map((url) => {
    const query = new URL(url, 'http://synthetic').searchParams
    return query.get('message_id') ?? (query.get('before') ? 'earlier' : 'newest')
  })
}

test('a change reads only the exchange it names while three pages are loaded', async ({ page }) => {
  // Only the long-running change stream is synthetic here; history reads are counted, not altered.
  await page.addInitScript(() => {
    Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true })
    const original = window.fetch.bind(window)
    const channels = new Set<ReadableStreamDefaultController<Uint8Array>>()
    const encoder = new TextEncoder()
    let sequence = 1000
    const value: Fixture = {
      active: 0,
      requests: [],
      send(data) {
        const payload = JSON.stringify({ ...(data ?? {}), topic: 'assistant.changed' })
        for (const channel of channels)
          channel.enqueue(
            encoder.encode(`id: ${++sequence}\nevent: assistant.changed\ndata: ${payload}\n\n`),
          )
      },
    }
    Object.assign(window, { historyFixture: value })
    window.fetch = (input, options) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (
        /\/v1\/conversation\/messages(?:\?|$)/.test(url) &&
        (!options?.method || options.method === 'GET')
      )
        value.requests.push(url)
      if (!/\/v1\/me\/events(?:\?|$)/.test(url)) return original(input, options)
      let writer: ReadableStreamDefaultController<Uint8Array>
      return Promise.resolve(
        new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              writer = controller
              channels.add(controller)
              value.active++
            },
            cancel() {
              if (channels.delete(writer)) value.active--
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
      )
    }
  })
  const fake = await fakeConversation(page)
  for (let n = 1; n <= 120; n++) {
    fake.seed({ id: `s${n}`, state: 'completed', text: `Синтетический вопрос ${n}`, turns: [] })
    fake.answer(`s${n}`, { reply: `Синтетический ответ ${n}` })
  }
  await page.goto(`${APP}assistant`)
  // Only the rows near the screen are rendered: the feed states how many exchanges are loaded.
  const loaded = (count: number) =>
    expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', String(count))
  await loaded(50)
  await expect.poll(async () => (await fixture(page)).active).toBe(1)
  for (const count of [100, 120]) {
    await page.getByRole('button', { name: 'Показать раньше' }).click()
    await loaded(count)
  }
  // Of 120 loaded exchanges only those near the screen exist in the page.
  expect(await page.getByTestId('exchange').count()).toBeLessThan(30)
  // The stream's first connection reloads everything once; let that settle before counting.
  await page.waitForTimeout(600)
  let from = (await fixture(page)).requests.length

  // An answer on the middle page, on screen after "earlier", changes: only it is read again.
  fake.messages.find((item) => item.id === 's25')!.turns[0]!.result = {
    reply: 'Синтетически исправленный ответ',
  }
  await send(page, { message_id: 's25', state: 'completed' })
  await expect(page.locator('#message-s25')).toContainText('Синтетически исправленный ответ')
  await page.waitForTimeout(600)
  expect(await readsSince(page, from)).toEqual(['s25'])

  // A running tool step is progress only.
  from = (await fixture(page)).requests.length
  await send(page, {
    message_id: 's120',
    turn_id: 't-s120',
    state: 'processing',
    stage: 'tool',
    tool: 'tasks.list',
  })
  await page.waitForTimeout(600)
  expect(await readsSince(page, from)).toEqual([])

  // A message sent from another device joins the bottom without reloading the pages.
  fake.seed({
    id: 's121',
    state: 'queued',
    text: 'Синтетический вопрос с другого устройства',
    turns: [],
  })
  from = (await fixture(page)).requests.length
  await send(page, { message_id: 's121', state: 'queued' })
  await loaded(121)
  await page.waitForTimeout(600)
  expect(await readsSince(page, from)).toEqual(['s121'])

  // An event that names nothing reads the newest page once, never the earlier ones.
  from = (await fixture(page)).requests.length
  await send(page, null)
  await page.waitForTimeout(600)
  expect(await readsSince(page, from)).toEqual(['newest'])
  await loaded(121)
})

test('a long history keeps a bounded window and returns to the newest messages', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  for (let n = 1; n <= 400; n++)
    fake.seed({ id: `w${n}`, state: 'completed', text: `Синтетический вопрос ${n}`, turns: [] })
  await page.goto(`${APP}assistant`)
  const loaded = (count: number) =>
    expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', String(count))
  await loaded(50)
  const earlier = page.getByRole('button', { name: 'Показать раньше' })
  // Six pages at most: the seventh earlier page drops the newest one.
  for (const count of [100, 150, 200, 250, 300, 300]) {
    await earlier.click()
    await loaded(count)
  }
  // The newest page left the window: the next newer page brings it back and drops the oldest.
  const newer = page.getByRole('button', { name: 'Показать новее' })
  await newer.click()
  await loaded(300)
  await expect(newer).toHaveCount(0)
  // Away from the newest messages again, one action returns to them.
  await earlier.click()
  await expect(newer).toBeVisible()
  await page.getByRole('button', { name: 'К последним сообщениям' }).click()
  await loaded(50)
  await expect(newer).toHaveCount(0)
  await expect(page.locator('#message-w400')).toBeVisible()
})
