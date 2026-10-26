import type { Page } from '@playwright/test'
import { fakeConversation } from './conversation'
import { APP, expect, test } from './fixtures'

/** The microphone that dictates into the composer: the field's own on a wide screen, the tab
 * bar's on a phone (the field shows its own only while the keyboard hides the tab bar). */
const composerMicrophone = (page: import('@playwright/test').Page) =>
  page.getByTestId(/^(dictation-start|voice-tab)$/).filter({ visible: true })

/** Every API boundary is synthetic, so these layout checks need no backend or family data. */
async function session(page: Page) {
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
        system_admin: false,
        revision: 1,
        scopes: ['session.control'],
      },
    }),
  )
  await page.route('**/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'synthetic-csrf' } }),
  )
  return fakeConversation(page)
}

test('the keyboard copies raw Markdown once and leaves sending as a separate action', async ({
  page,
}) => {
  const markdown = '**Ответ**\n\n- [Источник](https://example.org/source)\n- `текст`'
  await page.addInitScript(() => {
    const writes: string[] = []
    Object.defineProperty(window, 'answerCopies', { value: writes })
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (text: string) => void writes.push(text) },
    })
  })
  const fake = await session(page)
  fake.seed({
    id: 'message',
    state: 'completed',
    text: 'Вопрос',
    turns: [
      {
        id: 'turn',
        reply_message_id: 'reply',
        state: 'completed',
        result: { reply: markdown },
      },
    ],
  })
  await page.goto(`${APP}assistant`)
  await page.getByTestId('reply-menu').focus()
  await page.keyboard.press('ArrowDown')
  const copy = page.getByRole('menuitem', { name: 'Копировать' })
  await expect(copy).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('menu')).toBeHidden()
  await expect(
    page.locator('[data-sonner-toast]').filter({ hasText: 'Ответ скопирован' }),
  ).toHaveCount(1)
  expect(
    await page.evaluate(() => (window as unknown as { answerCopies: string[] }).answerCopies),
  ).toEqual([markdown])
  expect(fake.posts).toHaveLength(0)
})

test('dictation starts from a populated field and preserves it when voice needs setup', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(MediaDevices.prototype, 'getUserMedia', {
      configurable: true,
      value: async () => {
        // Capture starts alongside readiness; keep it synthetic while setup is unavailable.
        const context = new AudioContext()
        return context.createMediaStreamDestination().stream
      },
    })
  })
  const fake = await session(page)
  await page.route('**/v1/me/speech/readiness?capability=asr', (route) =>
    route.fulfill({
      json: { asr: { state: 'unavailable', code: 'not_configured', action: 'settings' } },
    }),
  )
  await page.routeWebSocket(/\/v1\/live$/, (socket) =>
    socket.send(JSON.stringify({ type: 'hello' })),
  )
  await page.goto(`${APP}assistant`)
  const field = page.getByLabel('Сообщение ассистенту')
  await field.fill('Сохранённый черновик')
  await composerMicrophone(page).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('dictation-problem')).toContainText('Распознавание не настроено')
  await expect(field).toHaveValue('Сохранённый черновик')
  await expect(page.getByTestId('composer-send')).toBeEnabled()
  expect(fake.posts).toHaveLength(0)
})

for (const textSize of ['100%', '200%']) {
  test(`recording controls fit a narrow screen with reduced motion: ${textSize}`, async ({
    page,
    browserName,
  }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 800 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    const fake = await session(page)
    await page.route('**/v1/me/speech/readiness?capability=asr', (route) =>
      route.fulfill({ json: { asr: { state: 'configured' } } }),
    )
    await page.addInitScript(() => {
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
    await page.routeWebSocket(/\/v1\/live$/, (socket) => {
      let id: string | null = null
      socket.send(JSON.stringify({ type: 'hello' }))
      let frames = 0
      socket.onMessage((message) => {
        if (typeof message !== 'string') {
          if (++frames === 9)
            socket.send(JSON.stringify({ type: 'dictation.partial', id, text: 'Продолжение' }))
          return
        }
        const frame = JSON.parse(message)
        if (frame.type === 'dictation.start') {
          id = frame.id
          frames = 0
          socket.send(JSON.stringify({ type: 'dictation.ready', id, sample_rate: 16000 }))
        }
        // Hold the final transcript so the longer processing status can be measured too.
      })
    })
    await page.goto(`${APP}assistant`)
    await page.evaluate((size) => (document.documentElement.style.fontSize = size), textSize)
    const field = page.getByLabel('Сообщение ассистенту')
    await field.fill('Начало')
    await composerMicrophone(page).click()
    const form = page.getByTestId('composer')
    const controls = form.getByTestId('dictation-controls')
    const cancel = controls.getByTestId('dictation-cancel')
    const stop = controls.getByTestId('dictation-stop')
    await expect(field).toHaveValue('Начало Продолжение')
    await expect(controls.getByTestId('dictation-timer')).toHaveText(/\d+:\d{2}/)
    expect(
      await controls
        .getByTestId('dictation-timer')
        .evaluate((element) => !!element.closest('[aria-hidden="true"]')),
    ).toBe(true)
    await expect(controls.getByRole('status').getByTestId('dictation-timer')).toHaveCount(0)

    async function fits(recording = true) {
      await expect(controls).toBeInViewport()
      await expect(form.getByRole('button')).toHaveCount(recording ? 2 : 1)
      const formBox = (await form.boundingBox())!
      const targets = []
      for (const action of recording ? [cancel, stop] : [cancel]) {
        await expect(action).toBeInViewport()
        const box = (await action.boundingBox())!
        expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(44)
        expect(box.x).toBeGreaterThanOrEqual(formBox.x)
        expect(box.x + box.width).toBeLessThanOrEqual(formBox.x + formBox.width)
        expect(box.y + box.height).toBeLessThanOrEqual(formBox.y + formBox.height)
        targets.push(box)
      }
      if (recording) {
        expect(targets[0]!.x + targets[0]!.width).toBeLessThanOrEqual(targets[1]!.x)
        const timer = (await controls.getByTestId('dictation-timer').boundingBox())!
        expect(timer.x).toBeGreaterThanOrEqual(targets[0]!.x + targets[0]!.width)
        expect(timer.x + timer.width).toBeLessThanOrEqual(targets[1]!.x)
        expect(timer.y + timer.height).toBeLessThanOrEqual(formBox.y + formBox.height)
      }
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
      ).toBeLessThanOrEqual(0)
      expect(
        await controls.evaluate((element) =>
          element
            .getAnimations({ subtree: true })
            .some((animation) => animation.playState === 'running'),
        ),
      ).toBe(false)
    }

    await fits()
    // WebKit screenshots inject inline CSS; keep the app's strict CSP checks enabled there.
    if (browserName !== 'webkit')
      await testInfo.attach('narrow-recording', {
        body: await page.screenshot(),
        contentType: 'image/png',
      })
    await stop.click()
    await expect(controls.getByRole('status')).toContainText('Уточняю текст')
    await expect(stop).toHaveCount(0)
    await expect(cancel).toBeEnabled()
    await fits(false)
    if (browserName !== 'webkit')
      await testInfo.attach('narrow-finalizing', {
        body: await page.screenshot(),
        contentType: 'image/png',
      })
    await cancel.click()
    await expect(controls).toHaveCount(0)
    await expect(field).toHaveValue('Начало')
    expect(fake.posts).toHaveLength(0)
  })
}

for (const theme of ['light', 'dark']) {
  for (const textSize of ['100%', '200%']) {
    test(`answer actions and a populated composer fit a narrow screen: ${theme}, ${textSize}`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 320, height: 800 })
      await page.addInitScript((value) => localStorage.setItem('assistant.web.theme', value), theme)
      const fake = await session(page)
      fake.seed({
        id: 'message',
        state: 'completed',
        text: 'Какие продукты выбрать?',
        turns: [
          {
            id: 'turn',
            reply_message_id: 'reply',
            state: 'completed',
            result: { reply: '**Ответ** со списком:\n\n- один\n- два\n- три' },
          },
        ],
      })
      await page.goto(`${APP}assistant`)
      await expect(page.getByTestId('reply')).toContainText('Ответ со списком')
      await page.evaluate((size) => (document.documentElement.style.fontSize = size), textSize)
      const field = page.getByLabel('Сообщение ассистенту')
      await field.fill('Сохранённый черновик')
      await expect(field).toBeFocused()
      const fieldBox = (await field.boundingBox())!
      expect(fieldBox.width).toBeGreaterThanOrEqual(44)
      expect(fieldBox.x + fieldBox.width).toBeLessThanOrEqual(320)
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
      ).toBeLessThanOrEqual(0)
      for (const action of [
        composerMicrophone(page),
        page.getByTestId('composer-send'),
        page.getByTestId('speech-toggle'),
        page.getByTestId('reply-menu'),
      ]) {
        await action.scrollIntoViewIfNeeded()
        await expect(action).toBeInViewport()
        const box = (await action.boundingBox())!
        expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(44)
        expect(box.x).toBeGreaterThanOrEqual(0)
        expect(box.x + box.width).toBeLessThanOrEqual(320)
      }
      const trigger = page.getByTestId('reply-menu')
      await trigger.focus()
      await trigger.press('ArrowDown')
      const menu = page.getByRole('menu')
      await expect(menu).toBeVisible()
      const box = (await menu.boundingBox())!
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(320)
      for (const action of await menu.getByRole('menuitem').all()) {
        // The opening animation scales the menu briefly; measure its settled touch target.
        await expect
          .poll(async () => {
            const target = (await action.boundingBox())!
            return Math.min(target.width, target.height)
          })
          .toBeGreaterThanOrEqual(44)
      }
      await page.keyboard.press('Escape')
      await expect(menu).toBeHidden()
      await expect(trigger).toBeFocused()
    })
  }
}

test('returning to new messages resumes following without changing a reading position', async ({
  page,
}) => {
  const fake = await session(page)
  for (let index = 1; index <= 12; index++)
    fake.seed({
      id: `message-${index}`,
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
  await page.goto(`${APP}assistant`)
  const conversation = page.getByTestId('conversation')
  const latest = page.getByTestId('new-messages')
  await expect(page.getByRole('feed')).toHaveAttribute('data-exchanges', '12')
  await expect(latest).toBeHidden()
  await conversation.evaluate((element) => element.scrollBy(0, -120))
  await expect(latest).toBeVisible()
  const before = await conversation.evaluate((element) => element.scrollTop)
  // Opening and closing actions must leave the reader anchored.
  const visibleIndex = await page.getByTestId('reply-menu').evaluateAll((elements) => {
    const scroller = document.querySelector('[data-testid="conversation"]')!.getBoundingClientRect()
    return elements.findLastIndex((element) => {
      const box = element.getBoundingClientRect()
      return box.top >= scroller.top && box.bottom <= scroller.bottom
    })
  })
  expect(visibleIndex).toBeGreaterThanOrEqual(0)
  const visibleMenu = page.getByTestId('reply-menu').nth(visibleIndex)
  await visibleMenu.focus()
  await visibleMenu.press('ArrowDown')
  await page.keyboard.press('Escape')
  expect(await conversation.evaluate((element) => element.scrollTop)).toBe(before)
  await latest.focus()
  await latest.press('Enter')
  await expect(latest).toBeHidden()
  await expect
    .poll(() =>
      conversation.evaluate(
        (element) => element.scrollHeight - element.scrollTop - element.clientHeight,
      ),
    )
    .toBeLessThanOrEqual(2)
})

test('a tap shows an earlier answer’s actions on a touch screen, one answer at a time', async ({
  page,
  isMobile,
}) => {
  test.skip(!isMobile, 'a mouse shows them on a hover')
  const fake = await session(page)
  for (const index of [1, 2, 3])
    fake.seed({
      id: `message-${index}`,
      state: 'completed',
      text: `Вопрос ${index}`,
      turns: [
        {
          id: `turn-${index}`,
          reply_message_id: `reply-${index}`,
          state: 'completed',
          result: { reply: `Ответ номер ${index}` },
        },
      ],
    })
  await page.goto(`${APP}assistant`)
  const replies = page.getByTestId('reply')
  await expect(replies).toHaveCount(3)
  const actions = (index: number) => replies.nth(index).getByTestId('reply-actions')
  // The newest answer always shows its actions; earlier ones wait for a tap.
  await expect(actions(2)).toHaveCSS('opacity', '1')
  await expect(actions(0)).toHaveCSS('opacity', '0')
  await replies.nth(0).getByTestId('reply-text').tap()
  await expect(actions(0)).toHaveCSS('opacity', '1')
  const speak = actions(0).getByTestId('speech-toggle')
  await expect(speak).toBeInViewport()
  const box = (await speak.boundingBox())!
  expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(44)
  await replies.nth(1).getByTestId('reply-text').tap()
  await expect(actions(1)).toHaveCSS('opacity', '1')
  await expect(actions(0)).toHaveCSS('opacity', '0')
  // A second tap hides them again (an emulated hover may linger, so the state is read).
  await replies.nth(1).getByTestId('reply-text').tap()
  await expect(actions(1)).not.toHaveAttribute('data-shown')
  expect(fake.posts).toHaveLength(0)
})
