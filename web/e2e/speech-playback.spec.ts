import type { Page } from '@playwright/test'
import { fakeConversation } from './conversation'
import { APP, expect, photograph, test } from './fixtures'
import { openSection } from './navigation'

// Real Vue, streaming reader and Web Audio; only HTTP data is synthetic.
test.use({ signedOut: true })

const TURN = 'synthetic-stream-turn'
const INITIAL_KEY = 'a'.repeat(64)
const CHANGED_KEY = 'b'.repeat(64)
type Frame =
  | { type: 'start'; content_type: 'audio/pcm'; sample_rate: number; channels: number }
  | { type: 'audio'; data: string }
  | { type: 'done' }
  | { type: 'error'; code: string }
interface AudioEvent {
  action: 'start' | 'stop' | 'close'
  context: number
  channels?: number
  sampleRate?: number
  hasSignal?: boolean
}
interface StreamRequest {
  path: string
  body: Record<string, unknown>
  accept: string | null
  eof: boolean
  aborted: boolean
  cancelled: boolean
}
interface StreamProbe {
  segments: number
  requests: StreamRequest[]
  audio: AudioEvent[]
  send(index: number, frames: Frame[], eof: boolean): void
}
type ProbeWindow = Window & { speechStreamProbe: StreamProbe }

function pcm(seconds: number): string {
  const frames = Math.round(24_000 * seconds)
  const bytes = Buffer.alloc(frames * 2)
  for (let index = 0; index < frames; index++)
    bytes.writeInt16LE(Math.round(64 * Math.sin((index * 2 * Math.PI * 440) / 24_000)), index * 2)
  return bytes.toString('base64')
}

/** The server may pause between frames indefinitely; EOF is controlled explicitly by the test. */
async function installStreamProbe(page: Page) {
  await page.addInitScript(() => {
    const encoder = new TextEncoder()
    const requests: StreamRequest[] = []
    const audio: AudioEvent[] = []
    const controllers: ReadableStreamDefaultController<Uint8Array>[] = []
    const contexts = new WeakMap<BaseAudioContext, number>()
    let contextSequence = 0
    const contextId = (context: BaseAudioContext) => {
      if (!contexts.has(context)) contexts.set(context, ++contextSequence)
      return contexts.get(context)!
    }
    const probe: StreamProbe = {
      segments: 1,
      requests,
      audio,
      send(index, frames, eof) {
        const controller = controllers[index]!
        const request = requests[index]!
        if (request.eof || request.aborted || request.cancelled)
          throw new Error('Cannot write into a finished synthetic stream')
        const bytes = encoder.encode(frames.map((frame) => JSON.stringify(frame) + '\n').join(''))
        // Split inside JSON as a network read can; this exercises the actual NDJSON parser.
        controller.enqueue(bytes.subarray(0, 7))
        controller.enqueue(bytes.subarray(7))
        if (eof) {
          request.eof = true
          controller.close()
        }
      },
    }
    ;(window as unknown as ProbeWindow).speechStreamProbe = probe

    // The native decoder, buffers and scheduling are untouched. Observe successful native calls.
    const nativeStart = AudioBufferSourceNode.prototype.start
    AudioBufferSourceNode.prototype.start = function (
      this: AudioBufferSourceNode,
      ...args: Parameters<typeof nativeStart>
    ) {
      nativeStart.apply(this, args)
      audio.push({
        action: 'start',
        context: contextId(this.context),
        channels: this.buffer?.numberOfChannels,
        sampleRate: this.buffer?.sampleRate,
        hasSignal: this.buffer?.getChannelData(0).some((value) => Math.abs(value) > 0.0001),
      })
    }
    const nativeStop = AudioBufferSourceNode.prototype.stop
    AudioBufferSourceNode.prototype.stop = function (
      this: AudioBufferSourceNode,
      ...args: Parameters<typeof nativeStop>
    ) {
      nativeStop.apply(this, args)
      audio.push({ action: 'stop', context: contextId(this.context) })
    }
    const nativeClose = AudioContext.prototype.close
    AudioContext.prototype.close = function (this: AudioContext) {
      const result = nativeClose.call(this)
      void result.then(() => audio.push({ action: 'close', context: contextId(this) }))
      return result
    }

    const nativeFetch = window.fetch.bind(window)
    window.fetch = async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : String(input), location.href)
      if (
        init?.method !== 'POST' ||
        (!/^\/v1\/conversation\/turns\/[^/]+\/speech$/.test(url.pathname) &&
          url.pathname !== '/v1/me/speech/preview')
      )
        return nativeFetch(input, init)
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      const request: StreamRequest = {
        path: url.pathname,
        body,
        accept: new Headers(init.headers).get('Accept'),
        eof: false,
        aborted: false,
        cancelled: false,
      }
      requests.push(request)
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controllers.push(controller)
          const abort = () => {
            request.aborted = true
            if (!request.eof && !request.cancelled) controller.error(init.signal?.reason)
          }
          if (init.signal?.aborted) abort()
          else init.signal?.addEventListener('abort', abort, { once: true })
        },
        cancel() {
          request.cancelled = true
        },
      })
      return new Response(stream, {
        headers: {
          'Content-Type': 'application/x-ndjson',
          'X-Speech-Segments': String(probe.segments),
          'X-Speech-Segment': String(body.segment ?? 0),
          'X-Speech-Cache-Key': String(body.cache_key ?? ''),
        },
      })
    }
  })
}

function starts(page: Page) {
  return page.evaluate(() =>
    (window as unknown as ProbeWindow).speechStreamProbe.audio.filter(
      (event) => event.action === 'start',
    ),
  )
}
function requests(page: Page) {
  return page.evaluate(() => (window as unknown as ProbeWindow).speechStreamProbe.requests)
}
async function playingContexts(page: Page) {
  return new Set((await starts(page)).map((event) => event.context)).size
}
async function sendAudio(page: Page, index: number, seconds: number, complete = false) {
  const frames: Frame[] = [
    { type: 'start', content_type: 'audio/pcm', sample_rate: 24_000, channels: 1 },
    { type: 'audio', data: pcm(seconds) },
    ...(complete ? [{ type: 'done' } as const] : []),
  ]
  await page.evaluate(
    ({ index, frames, complete }) =>
      (window as unknown as ProbeWindow).speechStreamProbe.send(index, frames, complete),
    { index, frames, complete },
  )
}

async function fixture(page: Page) {
  await installStreamProbe(page)
  const state = { cacheKey: INITIAL_KEY, segments: 1, prepares: 0, unexpected: [] as string[] }
  await page.route('**/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname
    const json = (value: unknown) => route.fulfill({ json: value })
    if (path === '/v1/me')
      return json({
        id: 'synthetic-stream-user',
        login: 'synthetic-stream-user',
        name: 'Synthetic Stream User',
        locale: 'ru',
        timezone: 'UTC',
        system_admin: false,
        revision: 1,
        scopes: [],
      })
    if (path === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
    if (path === '/v1/channels') return json({ channels: [] })
    if (path === '/v1/spaces') return json({ spaces: [], invitations: [] })
    if (path === '/v1/me/limits') return json({ limits: [], timezone: 'UTC' })
    // Settings show the installation.
    if (path === '/v1/client/config')
      return json({ instance_id: 'synthetic-instance', name: 'Synthetic installation' })
    if (path === '/v1/me/speech')
      return json({
        providers: [
          {
            id: 'codex',
            label: 'Codex',
            connection_kind: 'subscription',
            capabilities: ['asr', 'tts'],
            voices: ['default'],
            default_voice: 'default',
          },
        ],
        connections: [],
        preferences: {
          revision: 0,
          asr: 'auto',
          fallback_asr: null,
          tts: 'auto',
          voice: 'default',
        },
        asr: { state: 'configured', provider: 'codex' },
        tts: { state: 'configured', provider: 'codex' },
      })
    state.unexpected.push(`${route.request().method()} ${path}`)
    return route.fulfill({ status: 503, json: { error: 'unhandled_synthetic_request' } })
  })
  const conversation = await fakeConversation(page)
  conversation.seed({
    id: 'synthetic-stream-message',
    state: 'completed',
    text: 'Проверка озвучки',
    turns: [
      {
        id: TURN,
        reply_message_id: 'synthetic-stream-reply',
        state: 'completed',
        result: { reply: 'Синтетический ответ для проверки озвучки.' },
      },
    ],
  })
  await page.route(`**/v1/conversation/turns/${TURN}/speech/prepare`, (route) => {
    state.prepares++
    expect(route.request().method()).toBe('POST')
    expect(route.request().postDataJSON()).toEqual({})
    return route.fulfill({
      json: {
        cache_key: state.cacheKey,
        segments: state.segments,
        connection_id: 'synthetic-connection',
      },
    })
  })
  await page.goto(`${APP}assistant`)
  await expect(page.getByTestId('speech-toggle')).toBeVisible()
  return state
}

async function settings(page: Page) {
  await openSection(page, 'Настройки')
  await expect(page.getByRole('heading', { name: 'Настройки', exact: true })).toBeVisible()
  await page.locator('a[href$="/settings/speech"]').click()
  await expect(page.getByRole('heading', { name: 'Голос', exact: true })).toBeVisible()
}

test.describe('streaming reply playback', () => {
  test.beforeEach(({ baseURL }) => {
    expect(['127.0.0.1', 'localhost', '[::1]']).toContain(new URL(baseURL!).hostname)
  })

  test('sound starts before EOF, survives settings navigation, and global stop cancels the unfinished stream', async ({
    page,
  }) => {
    const state = await fixture(page)
    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(1)
    await sendAudio(page, 0, 12)
    await expect.poll(() => starts(page).then((value) => value.length)).toBeGreaterThan(0)
    const first = (await starts(page))[0]!
    expect(first).toMatchObject({ channels: 1, sampleRate: 24_000, hasSignal: true })
    expect((await requests(page))[0]).toMatchObject({
      accept: 'application/x-ndjson',
      eof: false,
      aborted: false,
      body: { cache_key: INITIAL_KEY, segment: 0 },
    })
    const player = await page.getByTestId('speech-player').boundingBox()
    const composer = await page.getByLabel('Сообщение ассистенту').boundingBox()
    expect(player).not.toBeNull()
    expect(composer).not.toBeNull()
    expect(composer!.y).toBeGreaterThanOrEqual(player!.y + player!.height)
    expect(composer!.y + composer!.height).toBeLessThanOrEqual(page.viewportSize()!.height)
    const tabs = await page.locator('nav.fixed').boundingBox()
    if (tabs) expect(composer!.y + composer!.height).toBeLessThanOrEqual(tabs.y)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await photograph(page, 'speech-stream-before-eof.png')
    await settings(page)
    await expect(page.getByTestId('speech-player')).toBeVisible()
    expect((await requests(page))[0]).toMatchObject({
      eof: false,
      aborted: false,
      cancelled: false,
    })
    expect(
      await page.evaluate(() =>
        (window as unknown as ProbeWindow).speechStreamProbe.audio.filter(
          (event) => event.action === 'close',
        ),
      ),
    ).toHaveLength(0)
    await photograph(page, 'speech-stream-after-navigation.png')
    await page.getByTestId('speech-player-stop').click()
    await expect(page.getByTestId('speech-player')).toBeHidden()
    await expect
      .poll(() =>
        page.evaluate(() =>
          (window as unknown as ProbeWindow).speechStreamProbe.audio.some(
            (event) => event.action === 'close',
          ),
        ),
      )
      .toBe(true)
    expect((await requests(page))[0]!.aborted || (await requests(page))[0]!.cancelled).toBe(true)

    // An explicit stop cannot promote a partially received response into the replay cache.
    await page.locator('a[href$="/assistant"]').filter({ visible: true }).click()
    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(2)
    expect(state.prepares).toBe(2)
    await page.getByTestId('speech-player-stop').click()
    expect(state.unexpected).toEqual([])
  })

  test('a completed reply replays from memory after fresh authorization, while a changed key regenerates it', async ({
    page,
  }) => {
    const state = await fixture(page)
    state.segments = 2
    await page.evaluate(() => ((window as unknown as ProbeWindow).speechStreamProbe.segments = 2))
    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(1)
    await sendAudio(page, 0, 0.2, true)
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(2)
    expect((await requests(page))[1]!.body.segment).toBe(1)
    await sendAudio(page, 1, 0.2, true)
    await expect.poll(() => playingContexts(page)).toBe(1)
    await expect(page.getByTestId('speech-player')).toBeHidden()

    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => state.prepares).toBe(2)
    await expect.poll(() => playingContexts(page)).toBe(2)
    expect(await requests(page)).toHaveLength(2)
    await expect(page.getByTestId('speech-player')).toBeHidden()

    state.cacheKey = CHANGED_KEY
    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(3)
    expect(state.prepares).toBe(3)
    expect((await requests(page))[2]!.body.cache_key).toBe(CHANGED_KEY)
    await sendAudio(page, 2, 0.2, true)
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(4)
    expect((await requests(page))[3]!.body.segment).toBe(1)
    await sendAudio(page, 3, 0.2, true)
    await expect.poll(() => playingContexts(page)).toBe(3)
    await expect(page.getByTestId('speech-player')).toBeHidden()
    expect(state.unexpected).toEqual([])
  })

  test('a partial stream ending with a provider error is audible but is never cached', async ({
    page,
  }) => {
    const state = await fixture(page)
    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(1)
    await sendAudio(page, 0, 12)
    await expect.poll(() => starts(page).then((value) => value.length)).toBeGreaterThan(0)
    await page.evaluate(() =>
      (window as unknown as ProbeWindow).speechStreamProbe.send(
        0,
        [{ type: 'error', code: 'provider_error' }],
        true,
      ),
    )
    await expect(page.getByTestId('speech-error')).toBeVisible()
    await page.getByTestId('speech-toggle').click()
    await expect.poll(() => requests(page).then((value) => value.length)).toBe(2)
    expect(state.prepares).toBe(2)
    expect((await requests(page))[1]!.body.cache_key).toBe(INITIAL_KEY)
    await sendAudio(page, 1, 0.2, true)
    await expect(page.getByTestId('speech-player')).toBeHidden()
    expect(state.unexpected).toEqual([])
  })
})
