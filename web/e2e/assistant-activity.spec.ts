import { fakeConversation } from './conversation'
import { APP, expect, test } from './fixtures'

// Model usage and the trace code are technical details: shown in developer mode only.
test.use({ developer: true })

const usage = {
  calls: 1,
  input_total_tokens: 120,
  input_uncached_tokens: 20,
  cache_read_tokens: 100,
  cache_write_tokens: 0,
  output_tokens: 12,
  total_tokens: 132,
  model_duration_ms: 900,
  first_text_ms: 150,
  first_public_text_ms: 640,
}

test('only model Markdown appears while streaming and after saved history replaces it', async ({
  page,
}) => {
  // Presentation contract only: no backend account, credentials or family data.
  await page.route('**/v1/**', (route) =>
    route.fulfill({ status: 503, json: { error: 'unmocked_presentation_test_route' } }),
  )
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: {
        id: '00000000-0000-4000-8000-000000000001',
        login: 'presentation-test',
        name: 'Синтетический пользователь',
        locale: 'ru',
        timezone: 'UTC',
        system_admin: true,
        revision: 1,
        scopes: ['session.control'],
      },
    }),
  )
  await page.route('**/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'synthetic-csrf' } }),
  )
  const fake = await fakeConversation(page)
  // Playwright route.fulfill buffers the entire body. Keep only the activity
  // transport open in-page so the real component consumes separate SSE chunks.
  await page.addInitScript(() => {
    const original = window.fetch.bind(window)
    const channels = new Set<ReadableStreamDefaultController<Uint8Array>>()
    const encoder = new TextEncoder()
    const harness = {
      connections: 0,
      send(snapshot: unknown) {
        const chunk = encoder.encode(`event: activity\ndata: ${JSON.stringify(snapshot)}\n\n`)
        for (const controller of channels) controller.enqueue(chunk)
      },
    }
    Object.assign(window, { activityFixture: harness })
    window.fetch = (input, options) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (!/\/v1\/conversation\/messages\/m1\/activity$/.test(url)) return original(input, options)
      let channel: ReadableStreamDefaultController<Uint8Array>
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          channel = controller
          channels.add(controller)
          harness.connections++
          options?.signal?.addEventListener(
            'abort',
            () => {
              if (channels.delete(controller)) controller.close()
            },
            { once: true },
          )
        },
        cancel() {
          channels.delete(channel)
        },
      })
      return Promise.resolve(
        new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }),
      )
    }
  })
  await page.route(/\/v1\/conversation\/turns\/t-m1$/, (route) =>
    route.fulfill({
      json: { id: 't-m1', state: 'completed', result: { services: [] }, usage },
    }),
  )
  await page.goto(`${APP}assistant`)
  await page.getByLabel('Сообщение ассистенту').fill('Запиши йогурт 150 г')
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.getByTestId('processing')).toBeVisible()
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          (window as unknown as { activityFixture: { connections: number } }).activityFixture
            .connections,
      ),
    )
    .toBeGreaterThan(0)

  const started = new Date().toISOString()
  const snapshot = {
    message_id: 'm1',
    turn_id: 't-m1',
    run_id: 'run-1',
    seq: 1,
    status: 'active',
    phase: 'generating',
    source: 'assistant',
    preview_text: 'Йогурт — **90',
    started_at: started,
    updated_at: started,
    elapsed_ms: 1500,
    model_call: 1,
    attempt: 1,
    steps: [
      {
        id: 'model-1',
        kind: 'model',
        status: 'running',
        label: 'model',
        started_at: started,
        duration_ms: null,
      },
    ],
    usage: { ...usage, calls: 0, total_tokens: null },
  }
  const send = (value: unknown) =>
    page.evaluate((snapshot) => {
      ;(
        window as unknown as { activityFixture: { send(value: unknown): void } }
      ).activityFixture.send(snapshot)
    }, value)
  await send(snapshot)
  await expect(page.getByTestId('live-answer')).toContainText('Йогурт — **90')
  await expect(page.getByTestId('live-answer').locator('strong')).toHaveCount(0)
  // The stage line under the text says it is still being written.
  await expect(page.getByTestId('processing')).toHaveText('Пишу ответ…')
  await expect(page.getByTestId('activity-elapsed')).toBeVisible()
  const confirmed = [{ id: 'operation', service: 'nutrition', text: 'Йогурт 150 г — 90 ккал' }]
  // A confirmed operation can arrive on a heartbeat with no new model tokens;
  // it remains internal and does not add prose to the streaming answer.
  await send({ ...snapshot, confirmed })
  await expect(page.getByTestId('confirmed-result')).toHaveCount(0)
  await expect(page.getByTestId('activity')).not.toContainText(confirmed[0]!.text)
  await expect(page.getByTestId('live-answer')).toContainText('Йогурт — **90')
  await expect(page.locator('.bg-success-surface')).toHaveCount(0)
  await send({
    ...snapshot,
    seq: 2,
    confirmed,
    preview_text:
      'Йогурт — **90 ккал**. Записал.\n\n- Порция: 150 г\n\n<img src=x onerror=alert(1)>',
  })
  await expect(page.getByTestId('live-answer').locator('strong')).toHaveText('90 ккал')
  await expect(page.getByTestId('live-answer').locator('li')).toHaveText('Порция: 150 г')
  await expect(page.getByTestId('live-answer').locator('img')).toHaveCount(0)
  expect(fake.messages[0]!.state).toBe('processing')
  expect(fake.posts).toHaveLength(1)

  fake.answer('m1', {
    reply: 'Ужин записал — **90 ккал**.',
    services: [
      {
        service: 'nutrition',
        status: 'completed',
        receipt_text: confirmed[0]!.text,
        receipts: [{ operation_id: 'operation' }],
        effects: [
          {
            operation_ref: 'operation',
            kind: 'entry.add_many',
            entities: [
              {
                kind: 'entry',
                id: 'yogurt',
                label: 'Catalog yogurt',
                date: '2026-10-05',
                revision: 1,
              },
            ],
          },
        ],
      },
    ],
  })
  await send({ ...snapshot, seq: 3, status: 'completed', preview_text: '', confirmed, usage })
  await expect(page.getByTestId('reply')).toContainText('Ужин записал — 90 ккал.')
  await expect(page.getByTestId('reply').locator('strong')).toHaveText('90 ккал')
  await expect(page.getByTestId('reply')).not.toContainText(confirmed[0]!.text)
  await expect(page.getByTestId('reply')).not.toContainText('Catalog yogurt')
  await expect(page.getByTestId('recorded-actions')).toHaveCount(0)
  await expect(page.getByTestId('recorded-undo')).toHaveCount(0)
  await expect(page.getByTestId('confirmed-result')).toHaveCount(0)
  await expect(page.getByTestId('recorded-result')).toHaveCount(0)
  await expect(page.getByTestId('live-answer')).toHaveCount(0)
  await expect(page.getByTestId('processing')).toHaveCount(0)
  await page.getByTestId('reply-menu').click()
  await page.getByRole('menuitem', { name: 'Подробности', exact: true }).click()
  await expect(page.getByTestId('usage-details')).toContainText(
    'По завершённым обращениям к модели',
  )
  await expect(page.locator('[data-usage-metric="total_tokens"]')).toContainText('132')
  await expect(page.locator('[data-usage-metric="cache_read_tokens"]')).toContainText('100')
  // The details give the code that finds this reply's trace.
  await expect(page.getByTestId('turn-diagnostic')).toHaveText('t-m1')
  expect(fake.posts).toHaveLength(1)
})
