import { flushPromises } from '@vue/test-utils'
import { effectScope, ref, shallowReactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChangeEvent } from '@/core/events'
import { useActivity } from './useActivity'

const mocks = vi.hoisted(() => ({ useSession: vi.fn() }))
vi.mock('@/stores/session', () => ({ useSession: mocks.useSession }))

function account() {
  const listeners = new Set<(event: ChangeEvent) => void>()
  return {
    stream: {
      subscribe(listener: (event: ChangeEvent) => void) {
        listeners.add(listener)
        return () => listeners.delete(listener)
      },
    },
    emit: (event: ChangeEvent) => listeners.forEach((listener) => listener(event)),
  }
}
const data = {
  message_id: 'message',
  turn_id: 'turn',
  run_id: 'run',
  seq: 1,
  status: 'active',
  phase: 'generating',
  preview_text: 'Предварительный ответ',
  source: 'assistant',
  started_at: '2026-10-02T12:00:00Z',
  updated_at: '2026-10-02T12:00:01Z',
  elapsed_ms: 1000,
  model_call: 1,
  attempt: 1,
  steps: [],
  usage: null,
}
const changed = (state: string): ChangeEvent => ({
  id: 9,
  name: 'change',
  topic: 'assistant.changed',
  data: { topic: 'assistant.changed', message_id: 'message', state },
})
function visibility(value: 'hidden' | 'visible') {
  Object.defineProperty(document, 'visibilityState', { value, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}
function streamed() {
  let writer!: ReadableStreamDefaultController<Uint8Array>
  const cancel = vi.fn()
  const body = new ReadableStream<Uint8Array>({
    start: (value) => {
      writer = value
    },
    cancel,
  })
  return {
    response: new Response(body),
    cancel,
    send: (value: unknown) =>
      writer.enqueue(
        new TextEncoder().encode(`event: activity\ndata: ${JSON.stringify(value)}\n\n`),
      ),
  }
}

describe('activity ownership and lifecycle', () => {
  const scopes: ReturnType<typeof effectScope>[] = []
  beforeEach(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })
  afterEach(() => {
    scopes.splice(0).forEach((scope) => scope.stop())
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
  function setup(enabled = ref(true), messageId = ref('message')) {
    const session = shallowReactive({ account: account(), me: { id: 'a', revision: 1 } })
    mocks.useSession.mockReturnValue(session)
    const scope = effectScope()
    scopes.push(scope)
    const settled = vi.fn()
    const activity = scope.run(() => useActivity(messageId, enabled, settled))!
    return { session, scope, activity, settled }
  }

  it('retains confirmations across transport gaps but replaces them on an authorized snapshot', async () => {
    const first = streamed(),
      second = streamed(),
      third = streamed()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(first.response)
        .mockResolvedValueOnce(second.response)
        .mockResolvedValueOnce(third.response),
    )
    const h = setup()
    const receipt = { id: 'operation', service: 'nutrition', text: 'Йогурт — 180 ккал' }
    await flushPromises()
    first.send({ ...data, confirmed: [receipt] })
    await flushPromises()
    visibility('hidden')
    expect(h.activity.snapshot.value).toBeNull()
    expect(h.activity.confirmed.value).toEqual([receipt])
    visibility('visible')
    await flushPromises()
    second.send({ ...data, seq: 0, run_id: null, status: 'unavailable', confirmed: [receipt] })
    await flushPromises()
    expect(h.activity.confirmed.value).toEqual([receipt])
    h.session.account.emit(changed('processing'))
    await flushPromises()
    third.send({ ...data, confirmed: [] })
    await flushPromises()
    expect(h.activity.confirmed.value).toEqual([])
  })

  it.each([403, 404, 410])('clears retained confirmations after HTTP %i', async (status) => {
    const first = streamed()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(first.response)
        .mockResolvedValueOnce(new Response(null, { status })),
    )
    const h = setup()
    await flushPromises()
    first.send({
      ...data,
      confirmed: [{ id: 'operation', service: 'nutrition', text: 'Йогурт — 180 ккал' }],
    })
    await flushPromises()
    expect(h.activity.confirmed.value).toHaveLength(1)
    visibility('hidden')
    visibility('visible')
    await flushPromises()
    expect(h.activity.confirmed.value).toEqual([])
    expect(h.activity.connection.value).toBe('denied')
  })

  it('clears confirmations on message, account and access changes', async () => {
    const streams = [streamed(), streamed(), streamed()]
    let next = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => streams[next++]!.response),
    )
    const messageId = ref('message')
    const h = setup(ref(true), messageId)
    const receipt = { id: 'operation', service: 'nutrition', text: 'Йогурт — 180 ккал' }
    await flushPromises()
    streams[0]!.send({ ...data, confirmed: [receipt] })
    await flushPromises()
    messageId.value = 'other'
    expect(h.activity.confirmed.value).toEqual([])
    await flushPromises()
    streams[1]!.send({ ...data, message_id: 'other', confirmed: [receipt] })
    await flushPromises()
    expect(h.activity.confirmed.value).toEqual([receipt])
    h.session.account = account()
    expect(h.activity.confirmed.value).toEqual([])
    await flushPromises()
    streams[2]!.send({ ...data, message_id: 'other', confirmed: [receipt] })
    await flushPromises()
    h.session.account.emit({ id: 4, name: 'change', topic: 'access.changed', data: {} })
    expect(h.activity.confirmed.value).toEqual([])
    expect(h.activity.snapshot.value).toBeNull()
  })

  it('clears a preview and aborts its reader when authoritative history stops the pending view', async () => {
    const stream = streamed()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => stream.response),
    )
    const enabled = ref(true)
    const h = setup(enabled)
    await flushPromises()
    stream.send(data)
    await flushPromises()
    expect(h.activity.snapshot.value?.preview_text).toBe('Предварительный ответ')
    enabled.value = false
    expect(h.activity.snapshot.value).toBeNull()
    await flushPromises()
    expect(stream.cancel).toHaveBeenCalledOnce()
    expect(h.settled).not.toHaveBeenCalled()
  })

  it('rejects a late response from the previous account and clears immediately on revoked access', async () => {
    let resolve!: (value: Response) => void
    const next = streamed()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise<Response>((done) => {
              resolve = done
            }),
        )
        .mockResolvedValueOnce(next.response),
    )
    const h = setup()
    h.session.account = account()
    await flushPromises()
    next.send({ ...data, preview_text: 'Текст нового аккаунта' })
    await flushPromises()
    const old = streamed()
    resolve(old.response)
    await flushPromises()
    expect(h.activity.snapshot.value?.preview_text).toBe('Текст нового аккаунта')
    expect(old.cancel).toHaveBeenCalledOnce()
    h.session.account.emit({ id: 3, name: 'change', topic: 'access.changed', data: {} })
    expect(h.activity.snapshot.value).toBeNull()
  })

  it('pauses in a hidden tab and reconnects to the current snapshot on return', async () => {
    const first = streamed(),
      second = streamed()
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(first.response)
      .mockResolvedValueOnce(second.response)
    vi.stubGlobal('fetch', fetcher)
    const h = setup()
    await flushPromises()
    first.send(data)
    await flushPromises()
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(h.activity.snapshot.value).toBeNull()
    await flushPromises()
    expect(first.cancel).toHaveBeenCalledOnce()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    second.send({ ...data, seq: 9, preview_text: 'Текущий полный текст' })
    await flushPromises()
    expect(h.activity.snapshot.value?.preview_text).toBe('Текущий полный текст')
    second.send({ ...data, seq: 10, status: 'completed', preview_text: '' })
    await flushPromises()
    expect(h.settled).toHaveBeenCalledOnce()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('never opens a stream for an inactive view and prevents late callbacks after disposal', async () => {
    const fetcher = vi.fn(async () => streamed().response)
    vi.stubGlobal('fetch', fetcher)
    const enabled = ref(false)
    const h = setup(enabled)
    expect(fetcher).not.toHaveBeenCalled()
    h.scope.stop()
    enabled.value = true
    visibility('visible')
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('keeps denied access across viewport toggles until the permissions are refreshed', async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 403 }))
    vi.stubGlobal('fetch', fetcher)
    const enabled = ref(true)
    const h = setup(enabled)
    await flushPromises()
    expect(h.activity.connection.value).toBe('denied')
    enabled.value = false
    enabled.value = true
    visibility('visible')
    h.session.account.emit(changed('processing'))
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    h.session.me = { id: 'a', revision: 2 }
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('streams in a visible window without the focus and keeps one stream', async () => {
    vi.spyOn(document, 'hasFocus').mockReturnValue(false)
    const stream = streamed()
    const fetcher = vi.fn().mockResolvedValueOnce(stream.response)
    vi.stubGlobal('fetch', fetcher)
    const h = setup()
    await flushPromises()
    stream.send(data)
    await flushPromises()
    window.dispatchEvent(new Event('blur'))
    visibility('visible')
    window.dispatchEvent(new Event('online'))
    h.session.account.emit(changed('processing'))
    await flushPromises()
    expect(stream.cancel).not.toHaveBeenCalled()
    expect(fetcher).toHaveBeenCalledOnce()
    expect(h.activity.connection.value).toBe('connected')
    expect(h.activity.snapshot.value?.preview_text).toBe('Предварительный ответ')
  })

  it('connects when the run starts instead of waiting out the retry delay', async () => {
    const early = streamed(),
      live = streamed()
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(early.response)
      .mockResolvedValueOnce(live.response)
    vi.stubGlobal('fetch', fetcher)
    const h = setup()
    await flushPromises()
    // Routing: the message is in work, its run has not begun yet.
    early.send({ ...data, seq: 0, run_id: null, status: 'unavailable', preview_text: '' })
    await flushPromises()
    expect(h.activity.connection.value).toBe('reconnecting')
    h.session.account.emit(changed('processing'))
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    live.send(data)
    await flushPromises()
    expect(h.activity.connection.value).toBe('connected')
    expect(h.activity.preview.value).toBe('Предварительный ответ')
  })

  it('follows the live public text and drops it with the connection', async () => {
    const first = streamed(),
      second = streamed()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response),
    )
    const h = setup()
    await flushPromises()
    first.send(data)
    await flushPromises()
    expect(h.activity.preview.value).toBe('Предварительный ответ')
    // A tool step or a new model call clears it while the run is live, as the server does.
    first.send({ ...data, seq: 2, phase: 'tool', preview_text: '' })
    await flushPromises()
    expect(h.activity.preview.value).toBe('')
    first.send({
      ...data,
      seq: 3,
      source: 'compaction',
      phase: 'compacting',
      preview_text: 'Сводка',
    })
    await flushPromises()
    expect(h.activity.preview.value).toBe('')
    first.send({ ...data, seq: 4 })
    await flushPromises()
    visibility('hidden')
    expect(h.activity.preview.value).toBe('')
    // Text from before the gap does not come back with a finished run's empty snapshot.
    visibility('visible')
    await flushPromises()
    second.send({ ...data, seq: 5, status: 'completed', preview_text: '' })
    await flushPromises()
    expect(h.activity.connection.value).toBe('ended')
    expect(h.activity.preview.value).toBe('')
  })

  it.each(['snapshot', 'change'] as const)(
    'keeps the text of a run whose %s says it completed until the saved reply replaces the view',
    async (path) => {
      const stream = streamed()
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => stream.response),
      )
      const h = setup()
      await flushPromises()
      stream.send(data)
      await flushPromises()
      if (path === 'snapshot')
        stream.send({ ...data, seq: 2, status: 'completed', preview_text: '' })
      else h.session.account.emit(changed('completed'))
      await flushPromises()
      expect(h.activity.preview.value).toBe('Предварительный ответ')
      expect(h.activity.connection.value).toBe('ended')
      expect(stream.cancel).toHaveBeenCalledOnce()
      // A change event after the terminal snapshot, or a repeated one, keeps it as well.
      h.session.account.emit(changed('completed'))
      expect(h.activity.preview.value).toBe('Предварительный ответ')
      expect(h.activity.snapshot.value).toBeNull()
    },
  )

  it.each(['snapshot', 'change'] as const)(
    'drops the text of a run whose %s says it failed',
    async (path) => {
      const stream = streamed()
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => stream.response),
      )
      const h = setup()
      await flushPromises()
      stream.send(data)
      await flushPromises()
      if (path === 'snapshot') stream.send({ ...data, seq: 2, status: 'failed', preview_text: '' })
      else h.session.account.emit(changed('failed'))
      await flushPromises()
      expect(h.activity.preview.value).toBe('')
      expect(h.activity.connection.value).toBe('ended')
    },
  )
})
