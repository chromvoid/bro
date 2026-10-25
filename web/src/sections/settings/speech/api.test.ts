import { describe, expect, it, vi } from 'vitest'
import { Api } from '@/core/http'
import {
  missingSelection,
  SpeechModelCatalog,
  SpeechSnapshot,
  speechSettingsClient,
  usableConnections,
} from './api'

const snapshot = () =>
  SpeechSnapshot.parse({
    providers: [
      {
        id: 'codex',
        label: 'Codex',
        capabilities: ['asr', 'tts'],
        connection_kind: 'subscription',
        voices: ['default'],
        default_voice: 'default',
        models: [
          {
            id: 'codex-dictation',
            name: 'Dictation',
            capabilities: ['asr'],
            asr_mode: 'streaming',
            voices: [],
            default_voice: null,
            tts_segment_chars: null,
            selectable: true,
            unavailable_reason: null,
          },
          {
            id: 'codex-pronunciation',
            name: 'Codex voice',
            capabilities: ['tts'],
            asr_mode: null,
            voices: ['default'],
            default_voice: 'default',
            tts_segment_chars: 500,
            selectable: true,
            unavailable_reason: null,
          },
        ],
      },
      {
        id: 'openai',
        label: 'OpenAI API',
        capabilities: ['asr', 'tts'],
        connection_kind: 'api_key',
        voices: ['marin'],
        default_voice: 'marin',
        models: [
          {
            id: 'gpt-transcribe',
            name: 'Transcribe',
            capabilities: ['asr'],
            asr_mode: 'file',
            voices: [],
            default_voice: null,
            tts_segment_chars: null,
            selectable: true,
            unavailable_reason: null,
          },
          {
            id: 'gpt-4o-mini-tts',
            name: 'Speech',
            capabilities: ['tts'],
            asr_mode: null,
            voices: ['marin'],
            default_voice: 'marin',
            tts_segment_chars: 3500,
            selectable: true,
            unavailable_reason: null,
          },
        ],
      },
    ],
    connections: [
      {
        id: 'one',
        ownership: 'personal',
        provider: 'openai',
        label: 'Mine',
        enabled: true,
        has_key: true,
        revision: 3,
        capabilities: ['asr', 'tts'],
        api_key: 'should-be-discarded',
      },
      {
        id: 'two',
        ownership: 'shared',
        provider: 'mistral',
        label: 'Family',
        enabled: false,
        has_key: true,
        revision: 4,
        capabilities: ['asr'],
      },
      {
        id: 'three',
        ownership: 'personal',
        provider: 'mistral',
        label: 'Missing key',
        enabled: true,
        has_key: false,
        revision: 1,
        capabilities: ['asr'],
      },
    ],
    preferences: { revision: 0, asr: 'auto', fallback_asr: null, tts: null, voice: 'marin' },
  })

describe('speech settings contracts', () => {
  it('offers only enabled capability-matching connections with a key and preserves revoked choices', () => {
    const data = snapshot()
    expect(usableConnections(data, 'asr').map((item) => item.id)).toEqual(['one'])
    expect(usableConnections(data, 'tts').map((item) => item.id)).toEqual(['one'])
    expect(missingSelection('two', usableConnections(data, 'asr'))).toBe(true)
    expect(missingSelection('auto', [])).toBe(false)
    expect(missingSelection(null, [])).toBe(false)
    expect(JSON.stringify(data)).not.toContain('should-be-discarded')
    expect(data.preferences).toMatchObject({
      asr: 'auto',
      fallback_asr: null,
      tts: null,
      asr_model: null,
      fallback_asr_model: null,
      tts_model: null,
    })
  })

  it('keeps provider-specific voices and the default, automatic and disabled TTS choices', () => {
    const data = snapshot()
    expect(data.providers[0]).toMatchObject({ voices: ['default'], default_voice: 'default' })
    expect(data.providers[1]).toMatchObject({ voices: ['marin'], default_voice: 'marin' })
    for (const tts of ['auto', 'one', null])
      expect(
        SpeechSnapshot.parse({ ...data, preferences: { ...data.preferences, tts } }).preferences
          ?.tts,
      ).toBe(tts)
  })

  it('accepts model preferences and normalized capabilities while stripping unrecognized fields', () => {
    expect(snapshot().preferences?.tts_model).toBeNull()
    const data = snapshot()
    expect(
      SpeechSnapshot.parse({
        ...data,
        preferences: {
          ...data.preferences,
          asr_model: 'synthetic/asr',
          fallback_asr_model: 'synthetic/backup',
          tts_model: 'google/gemini-3.8-flash-tts',
        },
      }).preferences,
    ).toMatchObject({
      asr_model: 'synthetic/asr',
      fallback_asr_model: 'synthetic/backup',
      tts_model: 'google/gemini-3.8-flash-tts',
    })
    const catalog = SpeechModelCatalog.parse({
      models: [
        {
          id: 'synthetic/tts',
          name: 'Speech',
          voices: ['Kore'],
          default_voice: 'Kore',
          capabilities: ['tts'],
          asr_mode: null,
          tts_segment_chars: 3500,
          selectable: true,
          unavailable_reason: null,
          api_key: 'discarded-secret',
        },
        {
          id: 'synthetic/asr',
          name: 'File recognition',
          capabilities: ['asr'],
          asr_mode: 'file',
          voices: [],
          default_voice: null,
          tts_segment_chars: null,
          selectable: true,
          unavailable_reason: null,
        },
        {
          id: 'synthetic/no-voice',
          name: 'Voice not available',
          capabilities: ['tts'],
          asr_mode: null,
          voices: [],
          default_voice: null,
          tts_segment_chars: 3500,
          selectable: false,
          unavailable_reason: 'voices_unavailable',
        },
      ],
    })
    expect(catalog.models[0]?.voices).toEqual(['Kore'])
    expect(catalog.models[1]).toMatchObject({ capabilities: ['asr'], asr_mode: 'file' })
    expect(catalog.models[2]).toMatchObject({ selectable: false, default_voice: null })
    expect(JSON.stringify(catalog)).not.toContain('discarded-secret')
  })
})

const response = (data: unknown) =>
  new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })

describe('direct speech secret writes', () => {
  it.each([false, true])(
    'routes model reads and explicit refreshes for admin=%s',
    async (admin) => {
      const apiClient = new Api()
      const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ models: [] })
      const client = speechSettingsClient(
        'u',
        () => 'u',
        new AbortController().signal,
        admin,
        apiClient,
      )
      await client.models('id/one')
      await client.models('id/one', undefined, true)
      const endpoint = `/v1/${admin ? 'admin' : 'me'}/speech/connections/id%2Fone/models?capability=all`
      expect(get.mock.calls.map(([path]) => path)).toEqual([endpoint, `${endpoint}&refresh=1`])
    },
  )

  it('rejects a late catalog result after the request is cancelled', async () => {
    const controller = new AbortController()
    const apiClient = new Api()
    const get = vi.spyOn(apiClient, 'get').mockImplementation(async () => {
      controller.abort()
      return { models: [] }
    })
    const client = speechSettingsClient(
      'u',
      () => 'u',
      new AbortController().signal,
      false,
      apiClient,
    )
    await expect(client.models('one', controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    })
    expect(get.mock.calls[0]?.[1]?.aborted).toBe(true)
  })

  it('reads available models directly and discards a result if the account changed', async () => {
    let user = 'u'
    const apiClient = new Api()
    const get = vi.spyOn(apiClient, 'get').mockImplementation(async () => {
      user = 'different'
      return { models: [] }
    })
    const client = speechSettingsClient(
      'u',
      () => user,
      new AbortController().signal,
      false,
      apiClient,
    )
    await expect(client.models('id/one')).rejects.toMatchObject({ name: 'AbortError' })
    expect(get).toHaveBeenCalledWith(
      '/v1/me/speech/connections/id%2Fone/models?capability=all',
      expect.any(AbortSignal),
    )
  })

  it('sends the key immediately with CSRF and expected user, preserves the revision and omits unchanged keys', async () => {
    const calls: Array<{ path: string; init: RequestInit }> = []
    const fetcher = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ path: String(path), init: init ?? {} })
      return response(String(path).endsWith('/csrf') ? { token: 'csrf' } : {})
    })
    const client = speechSettingsClient(
      'u',
      () => 'u',
      new AbortController().signal,
      false,
      new Api(fetcher),
    )
    await client.saveConnection('id', {
      provider: 'openai',
      label: 'Mine',
      enabled: true,
      revision: 0,
      api_key: 'synthetic-secret',
    })
    await client.saveConnection('id', {
      provider: 'openai',
      label: 'Renamed',
      enabled: true,
      revision: 1,
    })
    await client.deleteConnection('id', 2)
    expect(calls.map((call) => call.path)).toEqual([
      '/v1/auth/csrf',
      '/v1/me/speech/connections/id',
      '/v1/me/speech/connections/id',
      '/v1/me/speech/connections/id',
    ])
    const headers = new Headers(calls[1]!.init.headers)
    expect(headers.get('X-CSRF-Token')).toBe('csrf')
    expect(headers.get('X-Expected-User')).toBe('u')
    expect(headers.has('Idempotency-Key')).toBe(false)
    expect(JSON.parse(String(calls[1]!.init.body))).toMatchObject({
      api_key: 'synthetic-secret',
      revision: 0,
    })
    expect(JSON.parse(String(calls[2]!.init.body))).not.toHaveProperty('api_key')
    expect(calls[3]!.init.method).toBe('DELETE')
    expect(JSON.parse(String(calls[3]!.init.body))).toEqual({ revision: 2 })
  })

  it('refuses a key dispatch if the account changes while CSRF is fetched', async () => {
    let user = 'u'
    const fetcher = vi.fn(async () => {
      user = 'different'
      return response({ token: 'csrf' })
    })
    const client = speechSettingsClient(
      'u',
      () => user,
      new AbortController().signal,
      false,
      new Api(fetcher),
    )
    await expect(
      client.saveConnection('id', {
        provider: 'mistral',
        label: 'Mine',
        enabled: true,
        revision: 0,
        api_key: 'synthetic-secret',
      }),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('does not dispatch after leaving the page and routes shared writes to admin', async () => {
    const controller = new AbortController()
    const paths: string[] = []
    const fetcher = vi.fn(async (path: RequestInfo | URL) => {
      paths.push(String(path))
      return response(String(path).endsWith('/csrf') ? { token: 'csrf' } : {})
    })
    const client = speechSettingsClient(
      'admin',
      () => 'admin',
      controller.signal,
      true,
      new Api(fetcher),
    )
    await client.deleteConnection('shared', 7)
    expect(paths).toEqual(['/v1/auth/csrf', '/v1/admin/speech/connections/shared'])
    controller.abort()
    await expect(client.deleteConnection('shared', 8)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
})
