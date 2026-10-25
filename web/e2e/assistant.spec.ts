import { deflateSync } from 'node:zlib'
import { fakeConversation } from './conversation'
import { APP, expect, test } from './fixtures'

const composer = (page: import('@playwright/test').Page) => page.getByLabel('Сообщение ассистенту')

/** Voice boundary tests use a synthetic browser session, without a database or provider. */
async function voiceSession(page: import('@playwright/test').Page) {
  await page.route('**/v1/**', (route) =>
    route.fulfill({ status: 503, json: { error: 'unmocked_voice_test_route' } }),
  )
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: {
        id: '00000000-0000-4000-8000-000000000001',
        login: 'speech-test',
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
}

/**
 * No change stream: the fake one closes after each change, and every reconnect makes the page
 * refetch everything, access included. Without it, once the page has settled, access is checked
 * again only when asked.
 */
async function withoutStream(page: import('@playwright/test').Page) {
  await page.route(/\/v1\/me\/events(\?.*)?$/, (route) => route.fulfill({ status: 503 }))
}

/** A tiny valid PNG with transparency, to exercise on-device conversion to JPEG. */
function png(): Buffer {
  const crc = (buffer: Buffer) => {
    let value = ~0
    for (const byte of buffer) {
      value ^= byte
      for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ (0xedb88320 & -(value & 1))
    }
    return ~value >>> 0
  }
  const chunk = (type: string, data: Buffer) => {
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length)
    const body = Buffer.concat([Buffer.from(type), data])
    const check = Buffer.alloc(4)
    check.writeUInt32BE(crc(body))
    return Buffer.concat([length, body, check])
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(4, 0)
  header.writeUInt32BE(3, 4)
  header.set([8, 6, 0, 0, 0], 8)
  const rows = Buffer.concat(
    Array.from({ length: 3 }, () => Buffer.from([0, ...Array(16).fill(128)])),
  )
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

test('a message shows progress, then only the model answer', async ({ page }) => {
  const fake = await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  await expect(page.getByText('Чем помочь?')).toBeVisible()
  await composer(page).fill('Съел йогурт 150 г')
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.getByTestId('processing')).toBeVisible()
  // The brand mark thinks while the turn runs (B⋮), then says done for a confirmed write (BD).
  const mark = page.locator('[data-slot="brand-mark"]').first()
  await expect(mark).toHaveAttribute('data-mood', 'thinking')
  await expect(composer(page)).toHaveValue('')
  expect(fake.posts).toHaveLength(1)
  expect(fake.posts[0]!.body).toEqual({ text: 'Съел йогурт 150 г' })
  expect(fake.posts[0]!.key).toMatch(/^[0-9a-f-]{36}$/)

  fake.answer('m1', {
    reply: 'Завтрак записал — **90 ккал**.',
    services: [
      {
        service: 'nutrition',
        status: 'completed',
        receipt_text: 'Йогурт 150 г — 90 ккал',
        receipts: [{ operation_id: 'meal', kind: 'entry.add_many' }],
        effects: [
          {
            operation_ref: 'meal',
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
  await expect(page.getByTestId('reply')).toContainText('Завтрак записал — 90 ккал.')
  await expect(page.getByTestId('reply').locator('strong')).toHaveText('90 ккал')
  await expect(page.getByTestId('reply')).not.toContainText('Йогурт 150 г — 90 ккал')
  await expect(page.getByTestId('reply')).not.toContainText('Catalog yogurt')
  await expect(page.getByTestId('recorded-result')).toHaveCount(0)
  await expect(page.getByTestId('recorded-actions')).toHaveCount(0)
  await expect(page.getByTestId('recorded-undo')).toHaveCount(0)
  await expect(page.getByTestId('processing')).toBeHidden()
  await expect(mark).toHaveAttribute('data-mood', 'done')
  // Model usage and the trace code stay out of the family's view (developer mode shows them).
  await expect(page.getByRole('button', { name: 'Подробности' })).toHaveCount(0)
})

test('a lost response is checked, and the message is never sent twice', async ({ page }) => {
  const fake = await fakeConversation(page)
  // The page looks the outcome up by itself a second after the lost response
  // (core/reconciliation.ts), and a check joins a lookup under way: every lookup waits until the
  // check is pressed, so the card is still there to press it whichever lookup settles it.
  const lookups: string[] = []
  let release!: () => void
  const checked = new Promise<void>((resolve) => (release = resolve))
  await page.route(/\/v1\/conversation\/submissions\/by-key\/.+/, async (route) => {
    lookups.push(decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!))
    await checked
    await route.fallback()
  })
  await page.goto(`${APP}assistant`)
  fake.next('lose-response')
  await composer(page).fill('Купить хлеб')
  await page.getByRole('button', { name: 'Отправить' }).click()
  const card = page.getByTestId('pending-send')
  await expect(card).toHaveAttribute('data-state', 'uncertain')
  await expect(page.getByText('Неизвестно, сохранилось ли')).toBeVisible()
  await page.getByRole('button', { name: 'Проверить результат' }).click()
  release()
  await expect(card).toBeHidden()
  await expect(page.getByTestId('exchange').filter({ hasText: 'Купить хлеб' })).toBeVisible()
  expect(fake.posts).toHaveLength(1)
  // The outcome came from looking the message up by the key it was sent with.
  expect(lookups).toContain(fake.posts[0]!.key)
})

test('a refused message returns to the field with the reason', async ({ page }) => {
  const fake = await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  fake.next('too-many')
  await composer(page).fill('Привет')
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.getByText('Очередь ассистента заполнена')).toBeVisible()
  await expect(composer(page)).toHaveValue('Привет')
  await expect(page.getByTestId('pending-send')).toHaveCount(0)
})

test('a spent daily limit says when it resets and what still works by hand', async ({ page }) => {
  const fake = await fakeConversation(page)
  // The counters tell a spent limit from a busy server; the limit itself is set by an admin.
  await page.route('**/v1/me/limits', (route) =>
    route.fulfill({
      json: {
        limits: [
          { kind: 'assistant', maximum: 20, used: '20' },
          { kind: 'dictation_ms', maximum: null, used: '0' },
          { kind: 'research', maximum: 5, used: '1' },
        ],
        timezone: 'UTC',
      },
    }),
  )
  await page.goto(`${APP}assistant`)
  fake.next('daily-limit')
  await composer(page).fill('Запиши завтрак')
  await page.getByRole('button', { name: 'Отправить' }).click()
  const notice = page.getByTestId('limit-notice')
  await expect(notice).toContainText('Лимит обращений к ассистенту на сегодня исчерпан')
  await expect(notice).toContainText(/Следующее обновление: (сегодня|завтра) в \d\d:\d\d/)
  await expect(composer(page)).toHaveValue('Запиши завтрак')
  await notice.getByRole('link', { name: 'Посмотреть лимиты' }).click()
  await expect(page.getByTestId('limits')).toContainText('20 из 20')
})

test('clarifications are answered in place and proposals are confirmed explicitly', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  fake.seed({
    id: 'm1',
    state: 'completed',
    text: 'Съел творог',
    turns: [
      {
        id: 't1',
        reply_message_id: 'r1',
        state: 'completed',
        result: { reply: 'Сколько граммов?', question: 'Служебный вопрос о количестве' },
      },
    ],
  })
  fake.seed({
    id: 'm2',
    state: 'completed',
    text: 'Поставь цель',
    turns: [
      {
        id: 't2',
        reply_message_id: 'r2',
        state: 'completed',
        result: {
          reply: 'Установить цель 2000 ккал с сегодняшнего дня?',
          confirmation_pending: true,
        },
      },
    ],
  })
  await page.goto(`${APP}assistant`)
  const clarification = page.getByTestId('reply').filter({ hasText: 'Сколько граммов?' })
  await expect(clarification).not.toContainText('Служебный вопрос о количестве')
  await clarification.getByTestId('question').getByRole('button', { name: 'Ответить' }).click()
  await expect(page.getByTestId('reply-to')).toContainText('Сколько граммов?')
  await expect(page.getByTestId('reply-to')).not.toContainText('Служебный вопрос о количестве')
  await composer(page).fill('150')
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  expect(fake.posts[0]!.body).toEqual({ text: '150', reply_to_message_id: 'r1' })

  await page.getByRole('button', { name: 'Подтвердить' }).click()
  await expect.poll(() => fake.posts.length).toBe(2)
  expect(fake.posts[1]!.body).toEqual({ text: 'Подтверждаю', reply_to_message_id: 'r2' })
})

test('search opens a past message in its context', async ({ page }) => {
  const fake = await fakeConversation(page)
  fake.seed({ id: 'm1', state: 'completed', text: 'Съел йогурт утром', turns: [] })
  fake.seed({ id: 'm2', state: 'completed', text: 'Что на сегодня?', turns: [] })
  await page.goto(`${APP}assistant`)
  await page.getByRole('button', { name: 'Найти в истории' }).click()
  await page.getByPlaceholder('Что искать').fill('йогурт')
  await page.getByRole('button', { name: /Съел йогурт утром/ }).click()
  await expect(page).toHaveURL(/message=m1/)
  await expect(page.getByText('Сообщение из истории')).toBeVisible()
  await page.getByRole('button', { name: 'К последним сообщениям' }).click()
  await expect(page.getByText('Сообщение из истории')).toBeHidden()
})

test('search opens and highlights the exact assistant reply, then returns to the current conversation', async ({
  page,
}) => {
  await voiceSession(page)
  const fake = await fakeConversation(page)
  for (let index = 1; index <= 20; index++)
    fake.seed({
      id: `m${index}`,
      state: 'completed',
      text:
        index === 3 ? 'Длинный синтетический запрос\n'.repeat(70) : `Синтетический запрос ${index}`,
      turns: [
        {
          id: `t${index}`,
          reply_message_id: `r${index}`,
          state: 'completed',
          result: { reply: index === 3 ? 'Найденный ответ про йогурт' : `Ответ ${index}` },
        },
      ],
    })
  await page.goto(`${APP}assistant`)
  await page.getByRole('button', { name: 'Найти в истории' }).click()
  await page.getByPlaceholder('Что искать').fill('йогурт')
  const hit = page.getByRole('button', { name: /Найденный ответ про йогурт/ })
  await expect(hit).toContainText('Bro')
  await hit.click()
  await expect(page).toHaveURL(/message=r3/)
  await expect(page.locator('#message-r3')).toHaveClass(/ring-2/)
  await expect(page.locator('#message-r3')).toBeInViewport()
  await expect(page.locator('#message-m3')).not.toHaveClass(/bg-accent\/50/)
  // A message opened from search comes with the messages around it.
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '20')
  await page.getByRole('button', { name: 'К последним сообщениям' }).click()
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '20')
  await expect
    .poll(() =>
      page
        .getByTestId('conversation')
        .evaluate((element) => element.scrollHeight - element.scrollTop - element.clientHeight),
    )
    .toBeLessThanOrEqual(2)
})

test('a photo is converted to JPEG, survives reload and is sent with the message', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  await page
    .locator('input[type=file]')
    .setInputFiles({ name: 'meal.png', mimeType: 'image/png', buffer: png() })
  await expect(page.getByAltText('Выбранное фото')).toBeVisible()
  // Wait for the real browser's durable image write before reloading. WebKit rejects
  // native Blob values in IndexedDB on some devices, despite displaying them correctly.
  await expect
    .poll(() =>
      page.evaluate(async () => {
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
            const request = db.transaction('meta').objectStore('meta').get('assistant.composer')
            // The photo itself and the id of its upload, started when it was chosen.
            request.onsuccess = () => resolve(!!request.result?.photo && !!request.result?.imageId)
            request.onerror = () => reject(request.error)
          })
        } finally {
          db.close()
        }
      }),
    )
    .toBe(true)
  await page.reload()
  await expect(page.getByAltText('Выбранное фото')).toBeVisible()
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  expect(fake.uploads).toEqual(['image/jpeg'])
  expect(fake.posts[0]!.body).toEqual({ image_id: 'img-1' })
})

/** The check dictation makes alongside microphone capture. */
const readiness = (url: URL) => url.pathname === '/v1/me/speech/readiness'

/**
 * Dictation where only the device is synthetic: a tone from an oscillator. Capture, the worklet,
 * framing and the WebSocket protocol run for real; the recognizer says «купить», then the final.
 */
async function fakeDictation(
  page: import('@playwright/test').Page,
  options: { finalize?: boolean } = {},
) {
  await page.route(readiness, (route) => route.fulfill({ json: { asr: { state: 'configured' } } }))
  await page.addInitScript(() => {
    // On the prototype: WebKit ignores an own property on navigator.mediaDevices.
    Object.defineProperty(MediaDevices.prototype, 'getUserMedia', {
      configurable: true,
      value: async () => {
        const context = new AudioContext()
        const tone = context.createOscillator()
        const output = context.createMediaStreamDestination()
        tone.connect(output)
        tone.start()
        return output.stream
      },
    })
  })
  const counted = { frames: 0, finishes: 0, final: () => {} }
  await page.routeWebSocket(/\/v1\/live$/, (socket) => {
    let id: string | null = null
    socket.send(JSON.stringify({ type: 'hello' }))
    socket.onMessage((message) => {
      if (typeof message !== 'string') {
        if (++counted.frames === 9)
          socket.send(JSON.stringify({ type: 'dictation.partial', id, text: 'купить' }))
        return
      }
      const frame = JSON.parse(message)
      if (frame.type === 'dictation.start') {
        id = frame.id
        counted.frames = 0
        counted.final = () =>
          socket.send(
            JSON.stringify({
              type: 'dictation.final',
              id: frame.id,
              text: 'Купить хлеб.',
              transcription_id: 'tr-1',
            }),
          )
        socket.send(JSON.stringify({ type: 'dictation.ready', id, sample_rate: 16000 }))
      } else if (frame.type === 'dictation.finish' && frame.id === id) {
        counted.finishes++
        if (options.finalize !== false) counted.final()
      } else if (frame.type === 'dictation.cancel' && frame.id === id) id = null
    })
  })
  return counted
}

/** The microphone that dictates into the composer: the field's own on a wide screen, the tab
 * bar's on a phone (the field shows its own only while the keyboard hides the tab bar). */
const composerMicrophone = (page: import('@playwright/test').Page) =>
  page.getByTestId(/^(dictation-start|voice-tab)$/).filter({ visible: true })

/** The microphone of this layout: the phone tab bar's, or the sidebar's on a desktop. */
const microphone = (page: import('@playwright/test').Page) =>
  page.getByTestId(/^voice-(tab|sidebar)$/).filter({ visible: true })

async function compactDictationControls(page: import('@playwright/test').Page, recording = true) {
  const form = page.getByTestId('composer')
  const controls = form.getByTestId('dictation-controls')
  await expect(controls).toBeVisible()
  await expect(controls.getByTestId('dictation-cancel')).toHaveAccessibleName('Отменить запись')
  if (recording)
    await expect(controls.getByTestId('dictation-stop')).toHaveAccessibleName('Остановить диктовку')
  else await expect(controls.getByTestId('dictation-stop')).toHaveCount(0)
  await expect(form.getByRole('button')).toHaveCount(recording ? 2 : 1)
  await expect(form.getByTestId('dictation-start')).toHaveCount(0)
  await expect(form.getByTestId('composer-send')).toHaveCount(0)
  await expect(form.getByTestId('composer-stop')).toHaveCount(0)
  return controls
}

test('dictation fills the draft; only the user sends it', async ({
  page,
  browserName,
}, testInfo) => {
  await voiceSession(page)
  const counted = await fakeDictation(page, { finalize: false })
  const fake = await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  await composerMicrophone(page).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const controls = await compactDictationControls(page)
  await expect(page.getByTestId('dictation-status')).toContainText('Слушаю')
  await expect(composer(page)).toHaveValue('купить')
  await expect(controls.getByTestId('dictation-timer')).toHaveText(/\d+:\d{2}/)
  // WebKit's screenshot preparation injects inline CSS, which this app's strict CSP rejects.
  if (browserName !== 'webkit') {
    await expect(page.locator('#boot')).toHaveCount(0)
    await testInfo.attach('dictation-recording', {
      body: await page.screenshot(),
      contentType: 'image/png',
    })
  }
  await controls.getByTestId('dictation-stop').click()
  await expect(controls.getByTestId('dictation-status')).toContainText('Уточняю текст')
  await expect(controls.getByTestId('dictation-stop')).toHaveCount(0)
  await expect(controls.getByTestId('dictation-cancel')).toBeEnabled()
  await compactDictationControls(page, false)
  await expect.poll(() => counted.finishes).toBe(1)
  expect(fake.posts).toHaveLength(0)
  counted.final()
  await expect(composer(page)).toHaveValue('Купить хлеб.')
  await expect(controls).toHaveCount(0)
  await expect(page.getByText('Проверьте текст перед отправкой.', { exact: true })).toHaveCount(0)
  expect(counted.frames).toBeGreaterThan(2)
  expect(fake.posts).toHaveLength(0)
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  expect(fake.posts[0]!.body).toEqual({ text: 'Купить хлеб.', transcription_id: 'tr-1' })
})

for (const phase of ['recording', 'finalizing']) {
  test(`Escape cancels ${phase} and restores the existing draft`, async ({ page }) => {
    await voiceSession(page)
    await fakeDictation(page, { finalize: false })
    const fake = await fakeConversation(page)
    await page.goto(`${APP}assistant`)
    await composer(page).fill('Уже написано')
    await composerMicrophone(page).click()
    const controls = await compactDictationControls(page)
    await expect(composer(page)).toHaveValue('Уже написано купить')
    if (phase === 'finalizing') {
      await controls.getByTestId('dictation-stop').click()
      await expect(controls.getByTestId('dictation-status')).toContainText('Уточняю текст')
      await expect(controls.getByTestId('dictation-stop')).toHaveCount(0)
      await expect(controls.getByTestId('dictation-cancel')).toBeEnabled()
    }
    await page.keyboard.press('Escape')
    await expect(controls).toHaveCount(0)
    await expect(composer(page)).toHaveValue('Уже написано')
    await expect(page.getByTestId('dictation-problem')).toHaveCount(0)
    expect(fake.posts).toHaveLength(0)
    await page.getByTestId('composer-send').click()
    await expect.poll(() => fake.posts.length).toBe(1)
    expect(fake.posts[0]!.body).toEqual({ text: 'Уже написано' })
  })
}

test('the microphone dictates from another screen into the draft; only the user sends it', async ({
  page,
}) => {
  await voiceSession(page)
  await fakeDictation(page)
  const fake = await fakeConversation(page)
  // A draft already typed in the chat keeps its words; dictation is added after them.
  await page.goto(`${APP}assistant`)
  await composer(page).fill('Молоко')
  // The main navigation (sidebar or tab bar), not the day rail beside the chat on a wide screen.
  await page
    .getByRole('navigation', { name: 'Основная навигация' })
    .getByRole('link', { name: 'Сегодня' })
    .filter({ visible: true })
    .click()
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()

  await microphone(page).click()
  const sheet = page.getByRole('dialog', { name: 'Диктовка' })
  await expect(sheet.getByTestId('voice-status')).toContainText('Слушаю')
  await expect(sheet.getByTestId('voice-live')).toHaveText('купить')
  await sheet.getByTestId('voice-stop').click()
  await expect(sheet.getByTestId('voice-text')).toHaveValue('Купить хлеб.')
  await sheet.getByTestId('voice-to-chat').click()

  await expect(page).toHaveURL(/\/assistant$/)
  await expect(composer(page)).toHaveValue('Молоко Купить хлеб.')
  expect(fake.posts).toHaveLength(0)
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  expect(fake.posts[0]!.body).toEqual({ text: 'Молоко Купить хлеб.', transcription_id: 'tr-1' })
})

test('the empty field says what it is for on one line of a narrow phone', async ({ page }) => {
  await voiceSession(page)
  await fakeConversation(page)
  await page.setViewportSize({ width: 320, height: 640 })
  await page.goto(`${APP}assistant`)
  await expect(composer(page)).toBeVisible()
  // A wrapped placeholder is cut off by the one-line field.
  const fit = await composer(page).evaluate(async (field: HTMLTextAreaElement) => {
    await document.fonts.ready
    const style = getComputedStyle(field)
    const context = document.createElement('canvas').getContext('2d')!
    context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    const room = field.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
    return { text: context.measureText(field.placeholder).width, room }
  })
  expect(fit.text).toBeLessThanOrEqual(fit.room)
})

test('on the assistant screen the microphone records in the composer itself', async ({ page }) => {
  await voiceSession(page)
  await fakeDictation(page)
  await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  await expect(composer(page)).toBeEnabled()
  await microphone(page).click()
  await expect(page.getByTestId('dictation-status')).toContainText('Слушаю')
  await expect(page.getByRole('dialog', { name: 'Диктовка' })).toHaveCount(0)
  await expect(composer(page)).toHaveValue('купить')
  // Recording actions stay in the composer even when the navigation microphone started it.
  const controls = await compactDictationControls(page)
  await controls.getByTestId('dictation-stop').click()
  await expect(composer(page)).toHaveValue('Купить хлеб.')
})

test('the day suggests where to start; a question is sent as it reads', async ({ page }) => {
  // 08:00 for the owner (Europe/Belgrade): breakfast is not in the diary and a task is due today.
  await page.clock.setFixedTime(new Date('2026-10-07T06:00:00Z'))
  await page.route('**/v1/nutrition/day?*', (route) =>
    route.fulfill({
      json: {
        day_revision: 1,
        completed: false,
        date: '2026-10-07',
        entries: [],
        items: [],
        totals: {},
        goal: null,
        remaining_kcal: null,
        remaining_complete: false,
        drafts: [],
      },
    }),
  )
  await page.route('**/v1/tasks/items?*', (route) =>
    route.fulfill({
      json: {
        items: [{ id: 'task-1', title: 'Позвонить в сервис', status: 'open', revision: 1 }],
        total: 1,
        next_cursor: null,
        today: '2026-10-07',
        timezone: 'Europe/Belgrade',
      },
    }),
  )
  const fake = await fakeConversation(page)
  // The last message is long past: this visit starts afresh.
  fake.seed({ id: 'm1', state: 'completed', text: 'Напомни взвеситься', turns: [] })
  await page.goto(`${APP}assistant`)

  const suggestions = page.getByRole('group', { name: 'Подсказки' })
  await expect(suggestions.getByRole('button')).toHaveText([
    'Записать завтрак',
    'Что у меня на сегодня?',
  ])
  // A meal only begins the message; the person says the rest.
  await suggestions.getByRole('button', { name: 'Записать завтрак' }).click()
  await expect(composer(page)).toHaveValue('На завтрак ')
  await expect(composer(page)).toBeFocused()
  await expect(suggestions).toHaveCount(0)
  expect(fake.posts).toHaveLength(0)

  await composer(page).fill('')
  await suggestions.getByRole('button', { name: 'Что у меня на сегодня?' }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  expect(fake.posts[0]!.body).toEqual({ text: 'Что у меня на сегодня?' })
  await expect(suggestions).toHaveCount(0)
  await expect(composer(page)).toHaveValue('')
})

test('a meal photo opens the camera and waits in the chat draft with a request to log it', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  await page.goto(`${APP}food`)
  await page.getByTestId('add-food').click()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: /Фото еды/ }).click()
  const picker = await chooser
  expect(await picker.element().getAttribute('capture')).toBe('environment')
  await picker.setFiles({ name: 'meal.png', mimeType: 'image/png', buffer: png() })

  await expect(page).toHaveURL(/\/assistant$/)
  await expect(composer(page)).toHaveValue('Запиши еду с фото')
  await expect(page.getByAltText('Выбранное фото')).toBeVisible()
  expect(fake.posts).toHaveLength(0)
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  expect(fake.uploads).toEqual(['image/jpeg'])
  expect(fake.posts[0]!.body).toEqual({ text: 'Запиши еду с фото', image_id: 'img-1' })
})

test('blocked access preserves the draft and restoration never sends it automatically', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  await withoutStream(page)
  fake.availability('blocked', 'no_account_access', 'configure_access')
  await page.goto(`${APP}assistant`)
  const notice = page.getByTestId('assistant-availability')
  await expect(notice).toBeVisible()
  await composer(page).fill('Синтетический черновик')
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeDisabled()
  await expect(notice.getByRole('link')).toHaveAttribute('href', '/admin/users')
  // The page has finished the checks it makes on opening: from here only the button asks again.
  await page.waitForLoadState('networkidle')
  fake.availability('configured')
  await notice.getByRole('button', { name: 'Проверить доступность' }).click()
  await expect(notice).toBeHidden()
  await expect(composer(page)).toHaveValue('Синтетический черновик')
  expect(fake.posts).toHaveLength(0)
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
})

test('a failure after a confirmed change preserves outcome safeguards and offers no resend', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  await page.goto(`${APP}assistant`)
  await composer(page).fill('Синтетический таймер')
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect.poll(() => fake.posts.length).toBe(1)
  fake.answer('m1', {
    error: 'model_rate_limited',
    execution: { effects: 'confirmed', completion: 'partial', safe_to_resubmit: false },
    services: [
      { service: 'timers', status: 'completed', receipt_text: 'Синтетический таймер запущен' },
    ],
  })
  await expect(page.getByText('Ответ прервался', { exact: true })).toBeVisible()
  await expect(page.locator('[data-slot="brand-mark"]').first()).toHaveAttribute(
    'data-mood',
    'oops',
  )
  await expect(page.getByText('Синтетический таймер запущен')).toHaveCount(0)
  await expect(page.getByTestId('failed')).toContainText('Подтверждённые изменения сохранены.')
  await expect(page.getByTestId('recorded-result')).toHaveCount(0)
  await expect(page.getByText('Ничего не записано.', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Вернуть текст в поле' })).toHaveCount(0)
})

test('a readiness refusal after sending retains the complete editable draft and refreshes access', async ({
  page,
}) => {
  const fake = await fakeConversation(page)
  await withoutStream(page)
  fake.seed({
    id: 'previous',
    state: 'completed',
    text: 'Синтетическая порция',
    turns: [
      {
        id: 'previous-turn',
        reply_message_id: 'previous-reply',
        state: 'completed',
        result: { reply: 'Уточните порцию', question: 'Служебный вопрос о порции' },
      },
    ],
  })
  await page.goto(`${APP}assistant`)
  await page.getByTestId('question').getByRole('button', { name: 'Ответить' }).click()
  await composer(page).fill('Синтетический ответ с фото')
  await page
    .locator('input[type=file]')
    .setInputFiles({ name: 'meal.png', mimeType: 'image/png', buffer: png() })
  await expect(page.getByAltText('Выбранное фото')).toBeVisible()
  fake.next('unavailable')
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  const notice = page.getByTestId('assistant-availability')
  await expect(notice).toContainText('Нужно заново войти')
  await expect(composer(page)).toHaveValue('Синтетический ответ с фото')
  await expect(composer(page)).toBeEnabled()
  await expect(page.getByAltText('Выбранное фото')).toBeVisible()
  await expect(page.getByTestId('reply-to')).toContainText('Уточните порцию')
  await expect(page.getByTestId('pending-send')).toHaveCount(0)
  expect(fake.posts).toHaveLength(1)
  await page.reload()
  await expect(composer(page)).toHaveValue('Синтетический ответ с фото')
  await expect(page.getByAltText('Выбранное фото')).toBeVisible()
  await expect(page.getByTestId('reply-to')).toContainText('Уточните порцию')
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeDisabled()
  await page.waitForLoadState('networkidle')
  fake.availability('configured')
  await notice.getByRole('button', { name: 'Проверить доступность' }).click()
  await expect(notice).toBeHidden()
  expect(fake.posts).toHaveLength(1)
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect.poll(() => fake.posts.length).toBe(2)
  expect(fake.posts[1]!.body).toEqual(fake.posts[0]!.body)
  expect(fake.posts[1]!.body).toEqual({
    text: 'Синтетический ответ с фото',
    reply_to_message_id: 'previous-reply',
    image_id: 'img-1',
  })
})

test('unconfigured dictation explains setup during microphone preparation', async ({ page }) => {
  await voiceSession(page)
  await fakeConversation(page)
  await fakeDictation(page)
  await page.route(readiness, (route) =>
    route.fulfill({
      json: {
        asr: { state: 'unavailable', code: 'not_configured', action: 'settings' },
      },
    }),
  )
  await page.goto(`${APP}assistant`)
  await composerMicrophone(page).click()
  const notice = page.getByTestId('dictation-problem')
  await expect(notice).toContainText('Распознавание не настроено')
  await expect(notice.getByRole('link', { name: 'Настроить голос' })).toHaveAttribute(
    'href',
    '/settings/speech',
  )
  await expect(notice).not.toContainText('через минуту')
})

/** A short synthetic tone, generated in memory; exercise the browser's real audio decoder. */
function speechTone(seconds: number): Buffer {
  const rate = 16000
  const samples = Math.round(rate * seconds)
  const wave = Buffer.alloc(44 + samples * 2)
  wave.write('RIFF', 0)
  wave.writeUInt32LE(36 + samples * 2, 4)
  wave.write('WAVEfmt ', 8)
  wave.writeUInt32LE(16, 16)
  wave.writeUInt16LE(1, 20)
  wave.writeUInt16LE(1, 22)
  wave.writeUInt32LE(rate, 24)
  wave.writeUInt32LE(rate * 2, 28)
  wave.writeUInt16LE(2, 32)
  wave.writeUInt16LE(16, 34)
  wave.write('data', 36)
  wave.writeUInt32LE(samples * 2, 40)
  for (let sample = 0; sample < samples; sample++)
    wave.writeInt16LE(
      Math.round(1000 * Math.sin((2 * Math.PI * 440 * sample) / rate)),
      44 + sample * 2,
    )
  return wave
}

// Compressed audio goes through the browser's decoder; streamed PCM is in speech-playback.spec.ts.
test('voice playback starts only by button, stops, reads remaining segments in order and replays them from memory', async ({
  page,
}) => {
  await voiceSession(page)
  // Counts sources the native decoder produced and actually started.
  await page.addInitScript(() => {
    const nativeStart = AudioBufferSourceNode.prototype.start
    const counter = window as unknown as { speechStarts: number }
    counter.speechStarts = 0
    AudioBufferSourceNode.prototype.start = function (
      this: AudioBufferSourceNode,
      ...args: Parameters<typeof nativeStart>
    ) {
      nativeStart.apply(this, args)
      counter.speechStarts++
    }
  })
  const starts = () =>
    page.evaluate(() => (window as unknown as { speechStarts: number }).speechStarts)
  const fake = await fakeConversation(page)
  fake.seed({
    id: 'voice-message',
    state: 'completed',
    text: 'Синтетический вопрос',
    turns: [
      {
        id: 'voice-turn',
        reply_message_id: 'voice-reply',
        state: 'completed',
        result: { reply: 'Синтетический ответ для проверки озвучки.' },
      },
    ],
  })
  const cacheKey = 'c'.repeat(64)
  const prepares: unknown[] = []
  await page.route('**/v1/conversation/turns/voice-turn/speech/prepare', (route) => {
    prepares.push(route.request().postDataJSON())
    return route.fulfill({
      json: { cache_key: cacheKey, segments: 2, connection_id: 'synthetic-connection' },
    })
  })
  const requests: Array<{
    segment: number
    playback_id: string
    cache_key: string
    accept: string | undefined
  }> = []
  let duration = 2
  await page.route('**/v1/conversation/turns/voice-turn/speech', (route) => {
    const body = route.request().postDataJSON() as {
      segment: number
      playback_id: string
      cache_key: string
    }
    requests.push({ ...body, accept: route.request().headers().accept })
    return route.fulfill({
      contentType: 'application/x-ndjson',
      headers: {
        'X-Speech-Segments': '2',
        'X-Speech-Segment': String(body.segment),
        'X-Speech-Cache-Key': cacheKey,
      },
      body: [
        { type: 'start', content_type: 'audio/wav' },
        { type: 'audio', data: speechTone(duration).toString('base64') },
        { type: 'done' },
      ]
        .map((frame) => JSON.stringify(frame) + '\n')
        .join(''),
    })
  })
  await page.goto(`${APP}assistant`)
  const toggle = page.getByTestId('speech-toggle')
  const problem = page.getByTestId('speech-error')
  await expect(toggle).toHaveText('Озвучить')
  expect(prepares).toHaveLength(0)
  expect(requests).toHaveLength(0)
  await toggle.click()
  await expect.poll(() => requests.length).toBe(1)
  await expect(toggle).toHaveText('Остановить')
  await toggle.click()
  await expect(toggle).toHaveText('Озвучить')
  expect(requests).toHaveLength(1)
  const stopped = await starts()
  duration = 0.15
  await toggle.click()
  await expect.poll(() => requests.length).toBe(3)
  await expect(toggle).toHaveText('Озвучить')
  await expect(problem).toBeHidden()
  expect(await starts()).toBe(stopped + 2)
  expect(requests.map((request) => request.segment)).toEqual([0, 0, 1])
  expect(requests[1]!.playback_id).toBe(requests[2]!.playback_id)
  expect(requests[0]!.playback_id).not.toBe(requests[1]!.playback_id)
  expect(requests.map((request) => request.cache_key)).toEqual([cacheKey, cacheKey, cacheKey])
  expect(requests.map((request) => request.accept)).toEqual(Array(3).fill('application/x-ndjson'))
  expect(prepares).toEqual([{}, {}])

  // The complete reply is authorized again, then decoded from memory without new synthesis.
  await toggle.click()
  await expect.poll(() => prepares.length).toBe(3)
  await expect(toggle).toHaveText('Озвучить')
  await expect(problem).toBeHidden()
  expect(await starts()).toBe(stopped + 4)
  expect(requests).toHaveLength(3)
})

/** Streams one message's activity in the page: route.fulfill would buffer the whole stream. */
async function activityFeed(page: import('@playwright/test').Page, messageId: string) {
  await page.addInitScript((id) => {
    const original = window.fetch.bind(window)
    const channels = new Set<ReadableStreamDefaultController<Uint8Array>>()
    const encoder = new TextEncoder()
    const feed = {
      connections: 0,
      open: () => channels.size,
      send(snapshot: unknown) {
        const chunk = encoder.encode(`event: activity\ndata: ${JSON.stringify(snapshot)}\n\n`)
        for (const channel of channels) channel.enqueue(chunk)
      },
    }
    Object.assign(window, { activityFeed: feed })
    window.fetch = (input, options) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (!url.endsWith(`/v1/conversation/messages/${id}/activity`)) return original(input, options)
      let channel: ReadableStreamDefaultController<Uint8Array>
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          channel = controller
          channels.add(controller)
          feed.connections++
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
  }, messageId)
  type Feed = {
    activityFeed: { connections: number; open(): number; send(snapshot: unknown): void }
  }
  return {
    connections: () => page.evaluate(() => (window as unknown as Feed).activityFeed.connections),
    /** Streams the page still reads; closed when it stops reading. */
    open: () => page.evaluate(() => (window as unknown as Feed).activityFeed.open()),
    send: (snapshot: unknown) =>
      page.evaluate((value) => (window as unknown as Feed).activityFeed.send(value), snapshot),
  }
}

test('scrolling up while an answer streams keeps the reader where they are', async ({ page }) => {
  await voiceSession(page)
  const fake = await fakeConversation(page)
  for (let index = 1; index <= 12; index++)
    fake.seed({
      id: `old-${index}`,
      state: 'completed',
      text: `Вопрос ${index}`,
      turns: [
        {
          id: `turn-${index}`,
          reply_message_id: `reply-${index}`,
          state: 'completed',
          result: { reply: `Ответ ${index}.\n\n- первое\n- второе\n- третье` },
        },
      ],
    })
  fake.seed({ id: 'live', state: 'processing', text: 'Что я ел на этой неделе?', turns: [] })
  const feed = await activityFeed(page, 'live')
  const started = new Date().toISOString()
  // The server sends a snapshot up to five times a second while the answer grows.
  const snapshot = (seq: number) => ({
    message_id: 'live',
    turn_id: 'live-turn',
    run_id: 'live-run',
    seq,
    status: 'active',
    phase: 'generating',
    source: 'assistant',
    preview_text: Array.from({ length: seq * 25 }, (_, word) => `слово${word}`).join(' '),
    started_at: started,
    updated_at: started,
    elapsed_ms: seq * 200,
    model_call: 1,
    attempt: 1,
    steps: [],
    usage: null,
    confirmed: [],
  })
  const conversation = page.getByTestId('conversation')
  const position = () =>
    conversation.evaluate((element) => ({
      top: element.scrollTop,
      gap: element.scrollHeight - element.scrollTop - element.clientHeight,
    }))
  const settled = async (seq: number) => {
    await expect(page.getByTestId('live-answer')).toContainText(`слово${seq * 25 - 1}`)
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    )
  }
  await page.goto(`${APP}assistant`)
  await expect.poll(feed.connections).toBeGreaterThan(0)
  await feed.send(snapshot(1))
  await settled(1)
  await expect.poll(async () => (await position()).gap).toBeLessThanOrEqual(2)

  // A few pixels up, well inside reach of the bottom: the growing answer must not pull back.
  await conversation.evaluate((element) => element.scrollBy(0, -40))
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)))
  const reading = (await position()).top
  for (let seq = 2; seq <= 6; seq++) {
    await feed.send(snapshot(seq))
    await settled(seq)
    expect((await position()).top).toBe(reading)
  }

  // Back at the bottom, the answer is followed again.
  await conversation.evaluate((element) => element.scrollBy(0, element.scrollHeight))
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)))
  await feed.send(snapshot(7))
  await settled(7)
  expect((await position()).gap).toBeLessThanOrEqual(2)
})

test('a finished answer stays where it is read until the saved reply takes its place', async ({
  page,
}) => {
  await voiceSession(page)
  const fake = await fakeConversation(page)
  for (let index = 1; index <= 6; index++)
    fake.seed({
      id: `old-${index}`,
      state: 'completed',
      text: `Вопрос ${index}`,
      turns: [
        {
          id: `turn-${index}`,
          reply_message_id: `reply-${index}`,
          state: 'completed',
          result: { reply: `Ответ ${index}.\n\n- первое\n- второе\n- третье` },
        },
      ],
    })
  fake.seed({ id: 'live', state: 'processing', text: 'Что я ел на этой неделе?', turns: [] })
  const feed = await activityFeed(page, 'live')
  // The change stream stays quiet until the message is done, then says so; the history takes a
  // round-trip more to bring the saved reply.
  let finished = false
  await page.route(/\/v1\/me\/events(\?.*)?$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: finished
        ? `id: 1000\nevent: assistant.changed\ndata: ${JSON.stringify({
            topic: 'assistant.changed',
            message_id: 'live',
            state: 'completed',
          })}\n\n`
        : ': idle\n\n',
    }),
  )
  let holding = false
  let release!: () => void
  const held = new Promise<void>((resolve) => (release = resolve))
  await page.route(/\/v1\/conversation\/messages(\?.*)?$/, async (route) => {
    if (holding && route.request().method() === 'GET') await held
    await route.fallback()
  })
  const answer = Array.from({ length: 150 }, (_, word) => `слово${word}`).join(' ')
  const started = new Date().toISOString()
  const conversation = page.getByTestId('conversation')
  const gap = () =>
    conversation.evaluate(
      (element) => element.scrollHeight - element.scrollTop - element.clientHeight,
    )
  // Where the answer's text stands on the screen, the live one or the saved one: the reader's place.
  // The scroll offset is not: a row above can change too (on a touch screen an earlier answer's
  // actions stop taking room once it is not the newest), and the list then scrolls with it.
  const place = () =>
    conversation.evaluate((element) => {
      const text = Array.from(
        element.querySelectorAll('[data-testid="live-answer"] p, [data-testid="reply"] p'),
      ).find((paragraph) => paragraph.textContent?.includes('слово149'))
      if (!text) return null
      const box = text.getBoundingClientRect()
      const top = element.getBoundingClientRect().top
      return { top: box.top - top, bottom: box.bottom - top }
    })
  const frame = () =>
    page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    )
  await page.goto(`${APP}assistant`)
  await expect.poll(feed.connections).toBeGreaterThan(0)
  await feed.send({
    message_id: 'live',
    turn_id: 'live-turn',
    run_id: 'live-run',
    seq: 1,
    status: 'active',
    phase: 'generating',
    source: 'assistant',
    preview_text: answer,
    started_at: started,
    updated_at: started,
    elapsed_ms: 200,
    model_call: 1,
    attempt: 1,
    steps: [],
    usage: null,
    confirmed: [],
  })
  await expect(page.getByTestId('live-answer')).toContainText('слово149')
  await expect.poll(gap).toBeLessThanOrEqual(2)

  // Reading the end of the answer a little above the bottom.
  await conversation.evaluate((element) => element.scrollBy(0, -120))
  await frame()
  const reading = await place()
  expect(reading).not.toBeNull()
  holding = true
  finished = true
  // The change ends the page's reading of the run; the history it asks for is held.
  await expect.poll(feed.open).toBe(0)
  // Until the history answers, this text is all there is of the answer: dropping it here would
  // pull the reader down, and the saved reply would then appear below them.
  await expect(page.getByTestId('processing')).toHaveText('Проверяю сохранённый результат…')
  await expect(page.getByTestId('live-answer')).toContainText('слово149')
  await frame()
  expect(await place()).toEqual(reading)

  fake.answer('live', { reply: answer })
  release()
  await expect(page.getByTestId('live-answer')).toHaveCount(0)
  await expect(page.getByTestId('reply').last()).toContainText('слово149')
  await frame()
  expect(await place()).toEqual(reading)
})

test('a photo takes its place before it loads', async ({ page }) => {
  await voiceSession(page)
  const fake = await fakeConversation(page)
  fake.seed({
    id: 'photo',
    state: 'completed',
    text: 'Обед',
    images: [{ id: 'held', width: 1200, height: 1600, url: '/v1/conversation/images/held' }],
    turns: [],
  })
  let release!: () => void
  const held = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/v1/conversation/images/held', async (route) => {
    await held
    await route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="1600"/>',
    })
  })
  await page.goto(`${APP}assistant`)
  const photo = page.getByTestId('exchange').getByRole('img', { name: 'Выбранное фото' })
  await expect(photo).toBeAttached()
  // Without its box a photo grows when it loads and pushes the conversation while one scrolls.
  expect(await photo.boundingBox()).toMatchObject({ width: 180, height: 240 })
  release()
  await expect
    .poll(() => photo.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth))
    .toBe(1200)
  expect(await photo.boundingBox()).toMatchObject({ width: 180, height: 240 })
})

test('leaving a message opened from search returns to the newest one', async ({ page }) => {
  await voiceSession(page)
  const fake = await fakeConversation(page)
  for (let index = 1; index <= 20; index++)
    fake.seed({
      id: `m${index}`,
      state: 'completed',
      text: index === 3 ? 'Съел йогурт утром' : `Сообщение ${index}`,
      turns: [
        {
          id: `t${index}`,
          reply_message_id: `r${index}`,
          state: 'completed',
          result: { reply: `Ответ ${index}.\n\n- первое\n- второе` },
        },
      ],
    })
  const conversation = page.getByTestId('conversation')
  const gap = () =>
    conversation.evaluate(
      (element) => element.scrollHeight - element.scrollTop - element.clientHeight,
    )
  await page.goto(`${APP}assistant`)
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '20')
  await expect.poll(gap).toBeLessThanOrEqual(2)
  // Reading older messages, then searching from there.
  await conversation.evaluate((element) => element.scrollTo(0, 0))
  await page.getByRole('button', { name: 'Найти в истории' }).click()
  await page.getByPlaceholder('Что искать').fill('йогурт')
  await page.getByRole('button', { name: /Съел йогурт утром/ }).click()
  await expect(page.getByText('Сообщение из истории')).toBeVisible()
  await page.getByRole('button', { name: 'К последним сообщениям' }).click()
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '20')
  await expect.poll(gap).toBeLessThanOrEqual(2)
})

/**
 * Keeps the change stream connected and silent, so the history is refetched only when a test
 * brings the network back. The scripted stream reconnects about every second, each time refetching.
 */
async function quietEvents(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    const original = window.fetch.bind(window)
    window.fetch = (input, options) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (!url.split('?')[0]!.endsWith('/v1/me/events')) return original(input, options)
      return Promise.resolve(
        new Response(new ReadableStream<Uint8Array>(), {
          headers: { 'Content-Type': 'text/event-stream' },
        }),
      )
    }
  })
}

/**
 * History reads fail while `down` is set, as on a phone that lost its network for a moment;
 * `earlier` fails only the pages of earlier messages.
 */
async function flakyHistory(
  page: import('@playwright/test').Page,
  reads: 'all' | 'earlier' = 'all',
) {
  const network = { down: false }
  await page.route(/\/v1\/conversation\/messages(\?.*)?$/, (route) =>
    network.down &&
    route.request().method() === 'GET' &&
    (reads === 'all' || new URL(route.request().url()).searchParams.has('before'))
      ? route.fulfill({ status: 503, json: { error: 'unavailable' } })
      : route.fallback(),
  )
  return network
}

test('a failed refresh keeps the conversation and the reader where they are', async ({ page }) => {
  await voiceSession(page)
  await quietEvents(page)
  const fake = await fakeConversation(page)
  for (let index = 1; index <= 20; index++)
    fake.seed({
      id: `m${index}`,
      state: 'completed',
      text: `Сообщение ${index}`,
      turns: [
        {
          id: `t${index}`,
          reply_message_id: `r${index}`,
          state: 'completed',
          result: { reply: `Ответ ${index}.\n\n- первое\n- второе` },
        },
      ],
    })
  const network = await flakyHistory(page)
  const conversation = page.getByTestId('conversation')
  const gap = () =>
    conversation.evaluate(
      (element) => element.scrollHeight - element.scrollTop - element.clientHeight,
    )
  const top = () => conversation.evaluate((element) => element.scrollTop)
  const notice = page.getByTestId('history-refresh-failed')
  await page.goto(`${APP}assistant`)
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '20')
  await expect.poll(gap).toBeLessThanOrEqual(2)

  // Reading older messages when the network drops and the stream asks for a refresh.
  await conversation.evaluate((element) => element.scrollTo(0, 0))
  network.down = true
  await page.evaluate(() => window.dispatchEvent(new Event('online')))
  await expect(notice).toContainText('Не удалось обновить переписку')
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '20')
  await expect(page.locator('[data-state="error"]')).toHaveCount(0)
  expect(await top()).toBe(0)

  // The network is back: the refresh brings the new message and leaves the reader in place.
  network.down = false
  fake.seed({ id: 'm21', state: 'completed', text: 'Новое сообщение', turns: [] })
  await notice.getByRole('button', { name: 'Повторить' }).click()
  await expect(notice).toHaveCount(0)
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '21')
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  )
  expect(await top()).toBe(0)
  expect(await gap()).toBeGreaterThan(100)
})

test('earlier messages that fail to load leave the conversation in place', async ({ page }) => {
  await voiceSession(page)
  await quietEvents(page)
  const fake = await fakeConversation(page)
  // One more than a page, so there is an earlier one.
  for (let index = 1; index <= 51; index++)
    fake.seed({ id: `m${index}`, state: 'completed', text: `Сообщение ${index}`, turns: [] })
  // The refresh the change stream asks for on start waits for running reads, the failing page
  // among them, so it may come during the outage; a failed refresh is the test above.
  const network = await flakyHistory(page, 'earlier')
  await page.goto(`${APP}assistant`)
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '50')
  network.down = true
  await page.getByRole('button', { name: 'Показать раньше' }).click()
  await expect(page.getByText('Сервер временно недоступен')).toBeVisible()
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '50')
  await expect(page.locator('[data-state="error"]')).toHaveCount(0)
  await expect(page.getByTestId('history-refresh-failed')).toHaveCount(0)

  network.down = false
  await page.getByRole('button', { name: 'Показать раньше' }).click()
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '51')
})
