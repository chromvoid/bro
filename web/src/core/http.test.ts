import { describe, expect, it, vi } from 'vitest'
import { Api, ApiError, NetworkError, writeAttempt } from './http'

type Call = { path: string; init: RequestInit }

function server(...replies: Array<Response | Error>) {
  const calls: Call[] = []
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ path: String(input), init: init ?? {} })
    const reply = replies.shift()
    if (!reply) throw new Error('unexpected request')
    if (reply instanceof Error) throw reply
    return reply
  })
  return { calls, fetcher: fetcher as unknown as typeof fetch }
}

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
const header = (call: Call, name: string) => new Headers(call.init.headers).get(name)

describe('Api', () => {
  it('reads with the session cookie and reports the server build', async () => {
    const { calls, fetcher } = server(json({ id: 'u1' }, 200, { 'X-Client-Build': 'b2' }))
    const builds: string[] = []
    const api = new Api(fetcher, { build: (build) => builds.push(build) })
    expect(await api.get('/v1/me')).toEqual({ id: 'u1' })
    expect(calls[0]!.init.credentials).toBe('same-origin')
    expect(calls[0]!.init.redirect).toBe('error')
    expect(builds).toEqual(['b2'])
  })

  it('writes the exact bytes with CSRF and the idempotency key', async () => {
    const { calls, fetcher } = server(json({ token: 'csrf-1' }), json({ ok: true }, 201))
    const api = new Api(fetcher)
    const reply = await api.send('POST', '/v1/tasks/operations', '{"kind":"task.create"}', {
      key: 'key-1',
    })
    expect(reply).toEqual({ status: 201, data: { ok: true } })
    expect(calls.map((call) => call.path)).toEqual(['/v1/auth/csrf', '/v1/tasks/operations'])
    const write = calls[1]!
    expect(write.init.body).toBe('{"kind":"task.create"}')
    expect(header(write, 'X-CSRF-Token')).toBe('csrf-1')
    expect(header(write, 'Idempotency-Key')).toBe('key-1')
  })

  it('retries a CSRF rejection once with a fresh token and the same key', async () => {
    const { calls, fetcher } = server(
      json({ token: 'stale' }),
      json({ error: 'forbidden' }, 403),
      json({ token: 'fresh' }),
      json({ ok: true }),
    )
    const api = new Api(fetcher)
    await api.post('/v1/nutrition/operations', { kind: 'entry.add' }, { key: 'same' })
    expect(header(calls[3]!, 'X-CSRF-Token')).toBe('fresh')
    expect(header(calls[3]!, 'Idempotency-Key')).toBe('same')
    expect(calls[3]!.init.body).toBe(calls[1]!.init.body)
  })

  it('reports a real permission denial after the fresh token is refused too', async () => {
    const { fetcher } = server(
      json({ token: 'same' }),
      json({ error: 'forbidden' }, 403),
      json({ token: 'same' }),
    )
    await expect(new Api(fetcher).post('/v1/admin/users', {})).rejects.toMatchObject({
      status: 403,
    })
  })

  it('decodes structured errors and signals an ended session', async () => {
    const { fetcher } = server(
      json({ error: 'limit_exceeded', detail: { kind: 'dictation_ms' }, request_id: 'r1' }, 429, {
        'Retry-After': '30',
      }),
      json({ error: 'unauthorized' }, 401),
    )
    const unauthorized = vi.fn()
    const api = new Api(fetcher, { unauthorized })
    const limited = await api.get('/v1/me/limits').catch((error: unknown) => error)
    expect(limited).toBeInstanceOf(ApiError)
    expect(limited).toMatchObject({
      status: 429,
      code: 'limit_exceeded',
      detail: { kind: 'dictation_ms' },
      retryAfter: '30',
      requestId: 'r1',
    })
    await expect(api.get('/v1/me')).rejects.toMatchObject({ status: 401 })
    expect(unauthorized).toHaveBeenCalledOnce()
  })

  it('turns a lost response into a network error with unknown outcome', async () => {
    const { fetcher } = server(json({ token: 't' }), new TypeError('Failed to fetch'))
    await expect(new Api(fetcher).post('/v1/timers/operations', {})).rejects.toBeInstanceOf(
      NetworkError,
    )
  })

  it('distinguishes a preflight failure from a lost write response without retaining its text', async () => {
    const first = server(new TypeError('private connection failure'))
    await expect(
      new Api(first.fetcher).post('/v1/conversation/messages', { text: 'private' }),
    ).rejects.toMatchObject({
      diagnostic: { kind: 'network', phase: 'csrf', status: null, traceId: null },
    })
    expect(first.calls.map((call) => call.init.method)).toEqual(['GET'])

    const second = server(json({ token: 'secret' }), new TypeError('private connection failure'))
    const failure = await new Api(second.fetcher)
      .post('/v1/conversation/messages', { text: 'private' })
      .catch((error: NetworkError) => error)
    expect(failure).toMatchObject({
      diagnostic: { kind: 'network', phase: 'request', status: null, traceId: null },
    })
    expect(JSON.stringify(failure)).not.toMatch(/secret|private/)
    expect(writeAttempt(failure)).toBe('unknown')
  })

  it('records no dispatch for a first CSRF failure and a refused attempt before failed token refresh', async () => {
    const first = server(new TypeError('offline'))
    const firstError = await new Api(first.fetcher)
      .post('/v1/conversation/messages', {})
      .catch((error: unknown) => error)
    expect(writeAttempt(firstError)).toBe('not_dispatched')
    expect(first.calls.map((call) => call.init.method)).toEqual(['GET'])
    const refresh = server(
      json({ token: 'old' }),
      json({ error: 'forbidden' }, 403),
      new TypeError('offline'),
    )
    const refreshedError = await new Api(refresh.fetcher)
      .post('/v1/conversation/messages', {})
      .catch((error: unknown) => error)
    expect(writeAttempt(refreshedError)).toBe('refused')
    expect(refresh.calls.map((call) => call.init.method)).toEqual(['GET', 'POST', 'GET'])
  })

  it('does not invoke identity or build hooks after a read was aborted during its response body', async () => {
    const controller = new AbortController()
    const response = json({ error: 'unauthorized' }, 401, { 'X-Client-Build': 'old' })
    vi.spyOn(response, 'text').mockImplementation(async () => {
      controller.abort()
      return '{}'
    })
    const hooks = { unauthorized: vi.fn(), build: vi.fn() }
    await expect(
      new Api(async () => response, hooks).get('/v1/me', controller.signal),
    ).rejects.toThrow()
    expect(hooks.unauthorized).not.toHaveBeenCalled()
    expect(hooks.build).not.toHaveBeenCalled()
  })

  it('forgets an old session without letting a delayed 401 or CSRF response affect the new one', async () => {
    let oldResponse!: (response: Response) => void
    const hooks = { unauthorized: vi.fn(), build: vi.fn() }
    let count = 0
    const fetcher = vi.fn<typeof fetch>(async () => {
      if (++count === 1)
        return new Promise<Response>((resolve) => {
          oldResponse = resolve
        })
      if (count === 2) return json({ token: 'new-session' })
      return json({ ok: true })
    })
    const api = new Api(fetcher, hooks)
    const old = api.get('/v1/me').catch((error: unknown) => error)
    api.forget()
    await api.post('/v1/tasks/operations', {})
    oldResponse(json({ error: 'unauthorized' }, 401, { 'X-Client-Build': 'old-build' }))
    expect(await old).toMatchObject({ name: 'AbortError' })
    expect(hooks.unauthorized).not.toHaveBeenCalled()
    expect(hooks.build).not.toHaveBeenCalled()

    let csrfResponse!: (response: Response) => void
    const csrfFetcher = vi.fn<typeof fetch>(
      async () =>
        new Promise<Response>((resolve) => {
          csrfResponse = resolve
        }),
    )
    const changing = new Api(csrfFetcher)
    const pending = changing.post('/v1/conversation/messages', {}).catch((error: unknown) => error)
    changing.forget()
    csrfResponse(json({ token: 'old-session' }))
    const error = await pending
    expect(error).toMatchObject({ name: 'AbortError' })
    expect(writeAttempt(error)).toBe('not_dispatched')
    expect(csrfFetcher).toHaveBeenCalledOnce()
  })

  it('keeps attempt facts separate when two writes share the same session abort reason', async () => {
    const csrf: Array<(response: Response) => void> = []
    const fetcher = vi.fn<typeof fetch>(
      async (input, init) =>
        new Promise<Response>((resolve, reject) => {
          init!.signal!.addEventListener('abort', () => reject(init!.signal!.reason), {
            once: true,
          })
          if (input === '/v1/auth/csrf') csrf.push(resolve)
        }),
    )
    const api = new Api(fetcher)
    const first = api
      .post('/v1/conversation/messages', { text: 'A' })
      .catch((error: unknown) => error)
    const second = api
      .post('/v1/conversation/messages', { text: 'B' })
      .catch((error: unknown) => error)
    csrf[0]!(json({ token: 'token' }))
    await vi.waitFor(() =>
      expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1),
    )
    api.forget()
    const [dispatched, preparing] = await Promise.all([first, second])
    expect(dispatched).not.toBe(preparing)
    expect(writeAttempt(dispatched)).toBe('unknown')
    expect(writeAttempt(preparing)).toBe('not_dispatched')
  })

  it('retains a safe trace ID and HTTP status even when reading the response body fails', async () => {
    const trace = '0123456789abcdef0123456789abcdef'
    const response = json({}, 202, { 'X-Trace-ID': trace })
    vi.spyOn(response, 'text').mockRejectedValue(new TypeError('body lost'))
    const { fetcher } = server(json({ token: 't' }), response)
    await expect(new Api(fetcher).post('/v1/conversation/messages', {})).rejects.toMatchObject({
      diagnostic: { kind: 'network', phase: 'response', status: 202, traceId: trace },
    })
  })

  it('records deadlines separately from network failures', async () => {
    const controller = new AbortController()
    const timeout = vi.spyOn(AbortSignal, 'timeout').mockReturnValue(controller.signal)
    try {
      const api = new Api(async () => {
        controller.abort()
        throw new DOMException('aborted', 'AbortError')
      })
      await expect(api.get('/v1/conversation/availability')).rejects.toMatchObject({
        timedOut: true,
        diagnostic: { kind: 'timeout', phase: 'request', status: null },
      })
    } finally {
      timeout.mockRestore()
    }
  })

  it.each(['0123456789abcdef0123456789abcdef', 'not-a-trace-private-text'])(
    'retains only a valid server trace ID on an HTTP error: %s',
    async (trace) => {
      const { fetcher } = server(json({ error: 'unavailable' }, 503, { 'X-Trace-ID': trace }))
      await expect(new Api(fetcher).get('/v1/conversation/availability')).rejects.toMatchObject({
        diagnostic: {
          kind: 'http',
          phase: 'response',
          status: 503,
          traceId: trace.length === 32 ? trace : null,
        },
      })
    },
  )

  it('refuses paths outside the API', async () => {
    const { fetcher } = server()
    await expect(new Api(fetcher).get('/login')).rejects.toThrow('Not an API path')
  })

  it('revalidates the originating account after the CSRF token arrives', async () => {
    let current = true
    const fetcher = vi.fn(async () => {
      current = false
      return json({ token: 'other-account' })
    })
    await expect(
      new Api(fetcher).post(
        '/v1/conversation/messages',
        { text: 'Private A' },
        {
          beforeSend() {
            if (!current) throw new Error('account changed')
          },
        },
      ),
    ).rejects.toThrow('account changed')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('revalidates after a refreshed CSRF token without sending under a new account', async () => {
    let count = 0
    let current = true
    const fetcher = vi.fn(async () => {
      count++
      if (count === 1) return json({ token: 'stale' })
      if (count === 2) return json({ error: 'forbidden' }, 403)
      current = false
      return json({ token: 'new-account' })
    })
    await expect(
      new Api(fetcher).post(
        '/v1/conversation/messages',
        { text: 'Private A' },
        {
          beforeSend() {
            if (!current) throw new Error('account changed')
          },
        },
      ),
    ).rejects.toThrow('account changed')
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('binds both upload and admission to the expected cookie owner and does not retry account mismatch', async () => {
    const { calls, fetcher } = server(
      json({ token: 'csrf' }),
      json({ id: 'photo' }),
      json({ error: 'account_changed' }, 403),
    )
    const api = new Api(fetcher)
    await api.upload('/v1/conversation/images', new Blob(['photo']), { expectedUser: 'A' })
    await expect(
      api.post('/v1/conversation/messages', { image_id: 'photo' }, { expectedUser: 'A' }),
    ).rejects.toMatchObject({ code: 'account_changed' })
    expect(header(calls[1]!, 'X-Expected-User')).toBe('A')
    expect(header(calls[2]!, 'X-Expected-User')).toBe('A')
    expect(calls).toHaveLength(3)
  })
})

describe('protected audio responses', () => {
  it('uses CSRF and identity binding and returns audio plus segment metadata', async () => {
    const { calls, fetcher } = server(
      json({ token: 'speech-csrf' }),
      new Response('synthetic audio', {
        headers: {
          'Content-Type': 'audio/mpeg',
          'X-Speech-Segments': '3',
          'X-Speech-Segment': '1',
        },
      }),
    )
    const reply = await new Api(fetcher).postAudio(
      '/v1/conversation/turns/t/speech',
      { segment: 1 },
      { expectedUser: 'user' },
    )
    expect(header(calls[1]!, 'X-CSRF-Token')).toBe('speech-csrf')
    expect(header(calls[1]!, 'X-Expected-User')).toBe('user')
    expect(reply.data.segments).toBe(3)
    expect(reply.data.segment).toBe(1)
    expect(reply.data.audio.type).toBe('audio/mpeg')
    expect(await reply.data.audio.text()).toBe('synthetic audio')
  })

  it('decodes a typed synthesis error and only reports HTTP 401 as an expired browser session', async () => {
    const { fetcher } = server(json({ token: 't' }), json({ error: 'auth_required' }, 409))
    const unauthorized = vi.fn()
    await expect(
      new Api(fetcher, { unauthorized }).postAudio('/v1/me/speech/preview', {}),
    ).rejects.toMatchObject({ code: 'auth_required' })
    expect(unauthorized).not.toHaveBeenCalled()
  })

  it('refuses a stale audio body after logout even if fetch ignored its signal', async () => {
    const response = new Response('audio', { headers: { 'Content-Type': 'audio/mpeg' } })
    const { fetcher } = server(json({ token: 't' }), response)
    const api = new Api(fetcher)
    vi.spyOn(response, 'blob').mockImplementation(async () => {
      api.forget()
      return new Blob(['audio'], { type: 'audio/mpeg' })
    })
    await expect(api.postAudio('/v1/me/speech/preview', {})).rejects.toMatchObject({
      name: 'AbortError',
    })
  })
})

describe('protected audio streams', () => {
  const frames = (code?: string) =>
    [
      { type: 'start', content_type: 'audio/pcm', sample_rate: 24000, channels: 1 },
      { type: 'audio', data: 'AQACAA==' },
      code ? { type: 'error', code, message: 'synthetic-secret' } : { type: 'done' },
    ]
      .map((frame) => JSON.stringify(frame))
      .join('\n') + '\n'
  const streamed = (code?: string) =>
    new Response(frames(code), {
      headers: {
        'Content-Type': 'application/x-ndjson',
        'X-Speech-Segments': '2',
        'X-Speech-Segment': '0',
        'X-Speech-Cache-Key': '1'.repeat(64),
      },
    })

  it.each(['audio', 'audio-stream'] as const)(
    'never retries a structured provider 403 for %s even when a new CSRF token is available',
    async (kind) => {
      const { calls, fetcher } = server(
        json({ token: 'old-token' }),
        json({ error: 'no_access' }, 403),
        json({ token: 'fresh-token' }),
        streamed(),
      )
      const api = new Api(fetcher)
      const pending =
        kind === 'audio'
          ? api.postAudio('/v1/me/speech/preview', {})
          : api.postAudioStream('/v1/me/speech/preview', {})
      const error = await pending.catch((failure: unknown) => failure)
      expect(error).toMatchObject({ status: 403, code: 'no_access' })
      expect(writeAttempt(error)).toBe('unknown')
      expect(calls.map((call) => call.init.method)).toEqual(['GET', 'POST'])
    },
  )

  it.each(['audio', 'audio-stream'] as const)(
    'can refresh the CSRF token after a bare pre-handler 403 for %s',
    async (kind) => {
      const success =
        kind === 'audio'
          ? new Response('synthetic audio', { headers: { 'Content-Type': 'audio/mpeg' } })
          : streamed()
      const { calls, fetcher } = server(
        json({ token: 'old-token' }),
        new Response('403: Forbidden', { status: 403 }),
        json({ token: 'fresh-token' }),
        success,
      )
      const api = new Api(fetcher)
      const reply = await (kind === 'audio'
        ? api.postAudio('/v1/me/speech/preview', {})
        : api.postAudioStream('/v1/me/speech/preview', {}))
      expect(reply.status).toBe(200)
      expect(calls.map((call) => call.init.method)).toEqual(['GET', 'POST', 'GET', 'POST'])
      expect(header(calls[3]!, 'X-CSRF-Token')).toBe('fresh-token')
    },
  )

  it('uses guarded writes and emits PCM while preserving complete segment metadata', async () => {
    const { calls, fetcher } = server(json({ token: 'speech-csrf' }), streamed())
    const onAudio = vi.fn()
    const reply = await new Api(fetcher).postAudioStream(
      '/v1/conversation/turns/t/speech',
      { segment: 0, playback_id: 'synthetic' },
      { expectedUser: 'user' },
      onAudio,
      { segments: 2, segment: 0, cacheKey: '1'.repeat(64) },
    )
    expect(header(calls[1]!, 'Accept')).toBe('application/x-ndjson')
    expect(header(calls[1]!, 'X-CSRF-Token')).toBe('speech-csrf')
    expect(header(calls[1]!, 'X-Expected-User')).toBe('user')
    expect(header(calls[1]!, 'Idempotency-Key')).toBeNull()
    expect(reply.data).toEqual({
      audio: new Uint8Array([1, 0, 2, 0]),
      contentType: 'audio/pcm',
      sampleRate: 24000,
      channels: 1,
      segments: 2,
      segment: 0,
      cacheKey: '1'.repeat(64),
    })
    expect(onAudio).toHaveBeenCalledOnce()
  })

  it.each([
    { segments: 1, segment: 0, cacheKey: '1'.repeat(64) },
    { segments: 2, segment: 1, cacheKey: '1'.repeat(64) },
    { segments: 2, segment: 0, cacheKey: '2'.repeat(64) },
  ])('validates manifest metadata before delivering any PCM: %o', async (expected) => {
    const { calls, fetcher } = server(json({ token: 'token' }), streamed())
    const onAudio = vi.fn()
    await expect(
      new Api(fetcher).postAudioStream(
        '/v1/conversation/turns/t/speech',
        { segment: expected.segment },
        {},
        onAudio,
        expected,
      ),
    ).rejects.toMatchObject({ status: 502, code: 'provider_error' })
    expect(onAudio).not.toHaveBeenCalled()
    expect(calls).toHaveLength(2)
  })

  it.each(['provider_limit', 'auth_required', 'no_access', 'unsafe-secret'])(
    'does not retry an in-band %s failure or confuse it with session expiry',
    async (code) => {
      const { calls, fetcher } = server(json({ token: 'token' }), streamed(code))
      const unauthorized = vi.fn()
      const error = await new Api(fetcher, { unauthorized })
        .postAudioStream('/v1/me/speech/preview', {})
        .catch((failure: unknown) => failure)
      expect(error).toBeInstanceOf(ApiError)
      expect(error).toMatchObject({
        status: 502,
        code: code === 'unsafe-secret' ? 'provider_error' : code,
        serverMessage: null,
        diagnostic: { kind: 'http', phase: 'response', status: 200 },
      })
      expect(JSON.stringify(error)).not.toContain('secret')
      expect(writeAttempt(error)).toBe('unknown')
      expect(calls).toHaveLength(2)
      expect(unauthorized).not.toHaveBeenCalled()
    },
  )

  it('preserves ordinary HTTP startup errors and real session expiry', async () => {
    const { fetcher } = server(json({ token: 'token' }), json({ error: 'unauthorized' }, 401))
    const unauthorized = vi.fn()
    await expect(
      new Api(fetcher, { unauthorized }).postAudioStream('/v1/me/speech/preview', {}),
    ).rejects.toMatchObject({ status: 401, code: 'unauthorized' })
    expect(unauthorized).toHaveBeenCalledOnce()
  })

  it('prevents dispatch after the session changes during CSRF preflight', async () => {
    let respond!: (response: Response) => void
    const fetcher = vi.fn<typeof fetch>(
      () =>
        new Promise((resolve) => {
          respond = resolve
        }),
    )
    const api = new Api(fetcher)
    const pending = api
      .postAudioStream('/v1/me/speech/preview', {})
      .catch((error: unknown) => error)
    api.forget()
    respond(json({ token: 'old-session' }))
    const error = await pending
    expect(error).toMatchObject({ name: 'AbortError' })
    expect(writeAttempt(error)).toBe('not_dispatched')
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('forgets an active stream immediately, cancels its reader, and delivers no later callbacks', async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const cancel = vi.fn()
    const body = new ReadableStream<Uint8Array>({
      start(value) {
        controller = value
      },
      cancel,
    })
    const { fetcher } = server(
      json({ token: 'token' }),
      new Response(body, {
        headers: { 'Content-Type': 'application/x-ndjson' },
      }),
    )
    const api = new Api(fetcher)
    const onAudio = vi.fn()
    const pending = api
      .postAudioStream('/v1/me/speech/preview', {}, {}, onAudio)
      .catch((error: unknown) => error)
    controller.enqueue(new TextEncoder().encode(frames().split('\n').slice(0, 2).join('\n') + '\n'))
    await vi.waitFor(() => expect(onAudio).toHaveBeenCalledOnce())
    api.forget()
    expect(await pending).toMatchObject({ name: 'AbortError' })
    expect(cancel).toHaveBeenCalledOnce()
    expect(onAudio).toHaveBeenCalledOnce()
  })

  it('never accepts a truncated stream as complete cached audio', async () => {
    const { calls, fetcher } = server(
      json({ token: 'token' }),
      new Response(frames().split('\n').slice(0, 2).join('\n') + '\n', {
        headers: { 'Content-Type': 'application/x-ndjson' },
      }),
    )
    await expect(
      new Api(fetcher).postAudioStream('/v1/me/speech/preview', {}),
    ).rejects.toMatchObject({ status: 502, code: 'provider_error' })
    expect(calls).toHaveLength(2)
  })
})
