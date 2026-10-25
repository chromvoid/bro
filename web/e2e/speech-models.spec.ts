import type { Page, Route } from '@playwright/test'
import { APP, expect, photograph, test } from './fixtures'

interface SpeechModel {
  id: string
  name: string
  capabilities: Array<'asr' | 'tts'>
  asr_mode: 'streaming' | 'file' | null
  voices: string[]
  default_voice: string | null
  tts_segment_chars: number | null
  selectable: boolean
  unavailable_reason: string | null
}

// These scenarios exercise the built Vue app in a real browser. Every API is synthetic and
// intercepted before navigation; they never need provider credentials or a running backend.
test.use({ signedOut: true })

const CONNECTION = 'dceec314-d2c8-4a79-8eb5-e750bb8fe611'
const ASR = 'synthetic/listen-file'
const TTS = 'synthetic/speak'
const UNSUPPORTED = 'synthetic/no-voice-metadata'

function model(id: string, fields: Partial<SpeechModel>): SpeechModel {
  return {
    id,
    name: id,
    capabilities: [],
    asr_mode: null,
    voices: [],
    default_voice: null,
    tts_segment_chars: null,
    selectable: true,
    unavailable_reason: null,
    ...fields,
  }
}

const MODELS: SpeechModel[] = [
  model(ASR, { name: 'Synthetic Listener', capabilities: ['asr'], asr_mode: 'file' }),
  model(TTS, {
    name: 'Synthetic Speaker',
    capabilities: ['tts'],
    voices: ['Kore', 'Puck'],
    default_voice: 'Kore',
    tts_segment_chars: 3500,
  }),
  model(UNSUPPORTED, {
    name: 'Synthetic Voice Without Metadata',
    capabilities: ['tts'],
    selectable: false,
    unavailable_reason: 'voices_unavailable',
  }),
]

function connection(admin = false) {
  return {
    id: CONNECTION,
    ownership: admin ? 'shared' : 'personal',
    provider: 'openrouter',
    label: 'Synthetic OpenRouter',
    enabled: true,
    revision: 1,
    has_key: true,
    // A stale connection summary must not hide ASR that its loaded model catalog advertises.
    capabilities: ['tts'],
  }
}

function snapshot(admin: boolean, connected: boolean) {
  return {
    providers: [
      {
        id: 'codex',
        label: 'Codex',
        connection_kind: 'subscription',
        capabilities: ['asr', 'tts'],
        voices: ['default'],
        default_voice: 'default',
        dynamic_models: false,
        models: [
          model('codex-dictation', { capabilities: ['asr'], asr_mode: 'streaming' }),
          model('codex-pronunciation', {
            capabilities: ['tts'],
            voices: ['default'],
            default_voice: 'default',
            tts_segment_chars: 500,
          }),
        ],
      },
      {
        id: 'openrouter',
        label: 'OpenRouter',
        connection_kind: 'api_key',
        capabilities: ['tts'],
        voices: [],
        default_voice: null,
        dynamic_models: true,
        models: [],
      },
    ],
    connections: connected ? [connection(admin)] : [],
    ...(admin
      ? {}
      : {
          preferences: {
            revision: 0,
            asr: 'auto',
            asr_model: null,
            fallback_asr: null,
            fallback_asr_model: null,
            tts: 'auto',
            tts_model: null,
            voice: 'default',
          },
          asr: { state: 'configured' as const, provider: 'codex' },
          tts: { state: 'configured' as const, provider: 'codex' },
        }),
  }
}

/** Match Gemini's mono PCM16LE at 24 kHz, wrapped as WAV; no recorded audio is kept. */
function syntheticAudio(): Buffer {
  const rate = 24_000
  const bytes = rate * 3 * 2
  const wave = Buffer.alloc(44 + bytes)
  wave.write('RIFF', 0)
  wave.writeUInt32LE(36 + bytes, 4)
  wave.write('WAVEfmt ', 8)
  wave.writeUInt32LE(16, 16)
  wave.writeUInt16LE(1, 20)
  wave.writeUInt16LE(1, 22)
  wave.writeUInt32LE(rate, 24)
  wave.writeUInt32LE(rate * 2, 28)
  wave.writeUInt16LE(2, 32)
  wave.writeUInt16LE(16, 34)
  wave.write('data', 36)
  wave.writeUInt32LE(bytes, 40)
  for (let index = 0; index < bytes / 2; index++)
    wave.writeInt16LE(Math.round(32 * Math.sin((index * 2 * Math.PI * 440) / rate)), 44 + index * 2)
  return wave
}

/** Observe native playback only: a failed decode must never look like a successful preview. */
async function observePlayback(page: Page) {
  type Event =
    | { action: 'start'; channels: number | undefined; duration: number | undefined }
    | {
        action: 'stop'
      }
  const events: Event[] = []
  await page.exposeFunction('observeSpeechPlayback', (event: Event) => events.push(event))
  await page.addInitScript(() => {
    const report = (window as unknown as { observeSpeechPlayback(event: Event): Promise<void> })
      .observeSpeechPlayback
    const start = AudioBufferSourceNode.prototype.start
    const stop = AudioBufferSourceNode.prototype.stop
    AudioBufferSourceNode.prototype.start = function (
      this: AudioBufferSourceNode,
      ...args: Parameters<typeof start>
    ) {
      start.apply(this, args)
      // To the millisecond: decoding resamples the 24 kHz clip to the device's rate, and WebKit
      // comes out one 44.1 kHz frame short of the three seconds (2.99998 s).
      void report({
        action: 'start',
        channels: this.buffer?.numberOfChannels,
        duration: this.buffer ? Math.round(this.buffer.duration * 1000) / 1000 : undefined,
      })
    }
    AudioBufferSourceNode.prototype.stop = function (
      this: AudioBufferSourceNode,
      ...args: Parameters<typeof stop>
    ) {
      stop.apply(this, args)
      void report({ action: 'stop' })
    }
  })
  return events
}

async function syntheticApi(
  page: Page,
  options: { admin?: boolean; connected?: boolean; catalog?: (route: Route) => Promise<void> } = {},
) {
  const admin = options.admin ?? false
  const state = snapshot(admin, options.connected ?? true)
  const catalogRequests: string[] = []
  const writes: Array<{ path: string; body: Record<string, unknown> }> = []
  const unexpected: string[] = []
  const scope = admin ? 'admin' : 'me'
  await page.route('**/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const json = (value: unknown, status = 200) => route.fulfill({ status, json: value })
    if (path === '/v1/me')
      return json({
        id: 'synthetic-speech-user',
        login: 'synthetic-speech-user',
        name: 'Synthetic Speech User',
        locale: 'ru',
        timezone: 'Europe/Moscow',
        system_admin: admin,
        revision: 1,
        scopes: [],
      })
    if (path === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
    if (path === '/v1/me/events')
      return route.fulfill({ contentType: 'text/event-stream', body: ': synthetic heartbeat\n\n' })
    if (path === '/v1/notifications') return json({ notifications: [], unread: 0, before: null })
    if (path === '/v1/channels') return json({ channels: [] })
    if (path === '/v1/spaces') return json({ spaces: [], invitations: [] })
    if (path === `/v1/${scope}/speech`) return json(state)
    if (path.match(new RegExp(`^/v1/${scope}/speech/connections/[^/]+/models$`))) {
      catalogRequests.push(`${path}${url.search}`)
      return options.catalog ? options.catalog(route) : json({ models: MODELS })
    }
    if (request.method() === 'PUT' && path === '/v1/me/speech/preferences') {
      const body = request.postDataJSON()
      writes.push({ path, body })
      state.preferences = { ...body, revision: body.revision + 1 }
      return json(state.preferences)
    }
    if (
      request.method() === 'PUT' &&
      path.match(new RegExp(`^/v1/${scope}/speech/connections/[^/]+$`))
    ) {
      const body = request.postDataJSON()
      writes.push({ path, body })
      state.connections = [{ ...connection(admin), id: path.split('/').at(-1)!, label: body.label }]
      return json(state.connections[0])
    }
    if (request.method() === 'POST' && path === '/v1/me/speech/preview') {
      writes.push({ path, body: request.postDataJSON() })
      expect(request.headers().accept).toBe('application/x-ndjson')
      return route.fulfill({
        contentType: 'application/x-ndjson',
        headers: { 'X-Speech-Segments': '1', 'X-Speech-Segment': '0' },
        body: [
          { type: 'start', content_type: 'audio/wav' },
          { type: 'audio', data: syntheticAudio().toString('base64') },
          { type: 'done' },
        ]
          .map((frame) => JSON.stringify(frame) + '\n')
          .join(''),
      })
    }
    unexpected.push(`${request.method()} ${path}`)
    return json({ error: 'unexpected_synthetic_request' }, 404)
  })
  return { state, catalogRequests, writes, unexpected }
}

test.describe('speech model catalog in the browser', () => {
  test.beforeEach(({ baseURL }) => {
    expect(['127.0.0.1', 'localhost', '[::1]']).toContain(new URL(baseURL!).hostname)
  })

  test('saving a key loads its models with Codex selected, then persists separate ASR and TTS choices', async ({
    page,
  }) => {
    const playback = await observePlayback(page)
    let releaseCatalog: (() => void) | undefined
    const held = new Promise<void>((resolve) => (releaseCatalog = resolve))
    const api = await syntheticApi(page, {
      connected: false,
      catalog: async (route) => {
        await held
        await route.fulfill({ json: { models: MODELS } })
      },
    })
    await page.goto(`${APP}settings/speech`)
    await expect(page.locator('#speech-tts')).toHaveValue('auto')
    await page.getByRole('button', { name: 'Добавить провайдера', exact: true }).click()
    await page.locator('#speech-provider').selectOption('openrouter')
    await page.locator('#speech-label').fill('Synthetic OpenRouter')
    await page.locator('#speech-key').fill('synthetic-openrouter-key-not-a-real-secret')
    await page.getByRole('dialog').getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    // On a phone the success toast overlays the global player; close it before trying a voice.
    const saved = page.locator('[data-sonner-toast]').filter({ hasText: 'Подключение сохранено' })
    await saved.getByRole('button', { name: 'Закрыть' }).click()
    await expect(saved).toBeHidden()
    await expect.poll(() => api.catalogRequests.length).toBe(1)
    const id = api.state.connections[0]!.id
    expect(api.catalogRequests[0]).toBe(`/v1/me/speech/connections/${id}/models?capability=all`)
    const catalog = page.getByTestId(`speech-catalog-${id}`)
    await expect(catalog).toContainText(/Загружаем модели/)
    await expect(page.locator('#speech-asr')).toHaveValue('auto')
    await expect(page.locator('#speech-tts')).toHaveValue('auto')
    releaseCatalog!()
    await catalog.locator('summary').click()
    await expect(catalog.getByText('Synthetic Listener', { exact: true })).toBeVisible()
    await expect(catalog).toContainText('Synthetic Listener')
    await expect(catalog).toContainText('Synthetic Speaker')
    await expect(catalog).toContainText('Synthetic Voice Without Metadata')

    await page.locator('#speech-asr').selectOption(id)
    await page.locator('#speech-asr-model').selectOption(ASR)
    await page.locator('#speech-tts').selectOption(id)
    await page.locator('#speech-tts-model').selectOption(TTS)
    await page.locator('#speech-voice').selectOption('Puck')
    await expect(
      page.getByText(
        'Codex использует подключённую подписку, к которой у вас есть доступ. API-ключ для неё не нужен.',
        { exact: true },
      ),
    ).toHaveCount(0)
    await expect(
      page.locator('#speech-tts-model option', { hasText: 'Synthetic Voice Without Metadata' }),
    ).toBeDisabled()
    await expect(page.locator('#speech-asr-model')).toHaveValue(ASR)

    await page.getByRole('button', { name: 'Послушать голос', exact: true }).click()
    await expect
      .poll(() => api.writes.find((entry) => entry.path.endsWith('/preview'))?.body)
      .toEqual({
        connection_id: id,
        tts_model: TTS,
        voice: 'Puck',
      })
    await expect.poll(() => playback).toEqual([{ action: 'start', channels: 1, duration: 3 }])
    await page.getByTestId('speech-player-stop').click()
    await expect
      .poll(() => playback)
      .toEqual([{ action: 'start', channels: 1, duration: 3 }, { action: 'stop' }])
    await expect(page.getByRole('button', { name: 'Послушать голос', exact: true })).toBeEnabled()
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByText('Настройки голоса сохранены', { exact: true })).toBeVisible()
    expect(api.state.preferences).toMatchObject({
      asr: id,
      asr_model: ASR,
      tts: id,
      tts_model: TTS,
      voice: 'Puck',
    })
    await page.reload()
    await expect(page.locator('#speech-asr-model')).toHaveValue(ASR)
    await expect(page.locator('#speech-tts-model')).toHaveValue(TTS)
    await expect(page.locator('#speech-voice')).toHaveValue('Puck')
    await catalog.locator('summary').click()
    await expect(catalog).toContainText('Synthetic Voice Without Metadata')
    expect(api.unexpected).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await photograph(page, 'speech-model-catalog.png')
  })

  test('an administrator sees a failed shared catalog and explicitly refreshes it through the admin API', async ({
    page,
  }) => {
    const api = await syntheticApi(page, {
      admin: true,
      catalog: (route) =>
        new URL(route.request().url()).searchParams.get('refresh') === '1'
          ? route.fulfill({ json: { models: MODELS } })
          : route.fulfill({ status: 503, json: { error: 'provider_error' } }),
    })
    await page.goto(`${APP}admin/speech`)
    const catalog = page.getByTestId(`speech-catalog-${CONNECTION}`)
    await expect(catalog).toContainText(/Не удалось/)
    await expect(catalog).not.toContainText(/Загружаем модели/)
    await catalog.getByRole('button', { name: 'Обновить модели', exact: true }).click()
    await catalog.locator('summary').click()
    await expect(catalog.getByText('Synthetic Speaker', { exact: true })).toBeVisible()
    await expect(catalog).toContainText('Synthetic Listener')
    await expect(catalog).toContainText('Synthetic Speaker')
    await expect(catalog).toContainText('Synthetic Voice Without Metadata')
    expect(api.catalogRequests).toEqual([
      `/v1/admin/speech/connections/${CONNECTION}/models?capability=all`,
      `/v1/admin/speech/connections/${CONNECTION}/models?capability=all&refresh=1`,
    ])
    expect(api.unexpected).toEqual([])
  })
})
