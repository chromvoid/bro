import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref, watch, type Ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { live } from '@/core/live'
import type { CaptureHandlers } from './audio'
import { useDictation } from './useDictation'

const clients = vi.hoisted(() => ({
  get: vi.fn(async () => ({ asr: { state: 'configured' } })),
  unauthorized: vi.fn(),
  release: vi.fn(),
}))
vi.mock('@/core/client', () => ({
  api: { get: clients.get, hooks: { unauthorized: clients.unauthorized } },
  updates: { hold: () => clients.release },
}))
const account = ref<{ id: string; device_id: string } | null>({
  id: 'owner',
  device_id: 'browser',
})
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return account.value
    },
  }),
}))

class Socket {
  static OPEN = 1
  static CONNECTING = 0
  static instance: Socket
  readyState = 1
  bufferedAmount = 0
  id = ''
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  constructor() {
    Socket.instance = this
  }
  send = vi.fn((data: string | ArrayBuffer) => {
    if (typeof data !== 'string') return
    const frame = JSON.parse(data)
    if (frame.type === 'dictation.start') this.id = frame.id
  })
  close = vi.fn()
  event(value: object) {
    this.onmessage?.({ data: JSON.stringify({ id: this.id, ...value }) })
  }
}

afterEach(() => {
  live.stop()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  localStorage.clear()
  account.value = { id: 'owner', device_id: 'browser' }
})

/** Dictation into `draft` from a scripted microphone, inside a mounted component. */
function mountDictation(draft: Ref<string>) {
  vi.stubGlobal('WebSocket', Socket)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  live.start()
  const source = {
    prepare: vi.fn(async () => undefined),
    begin: vi.fn<(handlers: CaptureHandlers) => void>(),
    stop: vi.fn(async () => undefined),
    release: vi.fn(),
  }
  let dictation!: ReturnType<typeof useDictation>
  const wrapper = mount(
    defineComponent({
      setup() {
        dictation = useDictation(draft, () => source)
        return () => null
      },
    }),
  )
  return { dictation, wrapper, source }
}

describe('dictation session expiry', () => {
  it('keeps recording when the account opens or refreshes, then cancels on an account switch', async () => {
    account.value = null
    const { dictation, wrapper, source } = mountDictation(ref('Черновик'))
    dictation.start()
    await flushPromises()
    Socket.instance.event({ type: 'dictation.ready' })
    source.begin.mock.calls[0]![0].frame(new ArrayBuffer(24_000))

    account.value = { id: 'owner', device_id: 'browser' }
    expect(dictation.state.value).toBe('recording')
    expect(source.release).not.toHaveBeenCalled()
    account.value = { id: 'owner', device_id: 'browser' }
    expect(dictation.active.value).toBe(true)
    expect(source.release).not.toHaveBeenCalled()

    account.value = { id: 'other', device_id: 'browser' }
    expect(dictation.active.value).toBe(false)
    expect(dictation.state.value).toBe('idle')
    expect(dictation.canRetry.value).toBe(false)
    expect(source.release).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('checks readiness alongside capture and ignores repeated starts while preparing', async () => {
    const { dictation, wrapper, source } = mountDictation(ref('Черновик'))
    dictation.start()
    dictation.start()
    expect(clients.get).toHaveBeenCalledOnce()
    await flushPromises()
    expect(source.prepare).toHaveBeenCalledOnce()
    expect(dictation.active.value).toBe(true)
    wrapper.unmount()
  })

  it('ends the browser session after releasing recording resources and keeps partial draft text', async () => {
    const draft = ref('Черновик')
    const { dictation, wrapper, source } = mountDictation(draft)
    dictation.start()
    await flushPromises()
    expect(clients.get).toHaveBeenCalledWith(
      '/v1/me/speech/readiness?capability=asr',
      expect.any(AbortSignal),
    )
    expect(source.prepare.mock.invocationCallOrder[0]).toBeLessThan(
      clients.get.mock.invocationCallOrder[0]!,
    )
    Socket.instance.event({ type: 'dictation.ready', sample_rate: 16000 })
    Socket.instance.event({ type: 'dictation.partial', text: 'Продолжение' })
    Socket.instance.event({ type: 'dictation.error', error: 'session_expired', action: 'login' })
    expect(dictation.problem.value).toBe('session_expired')
    expect(dictation.failure.value.action).toBe('login')
    expect(dictation.active.value).toBe(false)
    expect(dictation.incomplete.value).toBe(true)
    // The revision heard before the error stays, although no frame showed it yet.
    expect(draft.value).toBe('Черновик Продолжение')
    expect(clients.release).toHaveBeenCalledOnce()
    expect(clients.unauthorized).toHaveBeenCalledOnce()
    expect(source.release).toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('running transcript', () => {
  /** Frames that run only when the test paints one. */
  function frames() {
    const waiting = new Map<number, FrameRequestCallback>()
    let last = 0
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      waiting.set(++last, callback)
      return last
    })
    vi.stubGlobal('cancelAnimationFrame', (id: number) => waiting.delete(id))
    return {
      get waiting() {
        return waiting.size
      },
      paint() {
        const callbacks = [...waiting.values()]
        waiting.clear()
        callbacks.forEach((callback) => callback(0))
      },
    }
  }

  async function recording(draft: Ref<string>) {
    const { dictation, wrapper, source } = mountDictation(draft)
    dictation.start()
    await flushPromises()
    Socket.instance.event({ type: 'dictation.ready', sample_rate: 16000 })
    source.begin.mock.calls[0]![0].frame(new ArrayBuffer(24_000))
    return { dictation, wrapper }
  }

  it('takes the newest revision once per frame instead of every revision', async () => {
    const screen = frames()
    const draft = ref('Черновик')
    const { wrapper } = await recording(draft)
    const shown: string[] = []
    watch(draft, (value) => shown.push(value), { flush: 'sync' })
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить' })
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить хлеб' })
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить хлеб и молоко' })
    expect(draft.value).toBe('Черновик')
    expect(screen.waiting).toBe(1)
    screen.paint()
    expect(shown).toEqual(['Черновик Купить хлеб и молоко'])
    wrapper.unmount()
  })

  it('lets the final text win over a revision still waiting for its frame', async () => {
    const screen = frames()
    const draft = ref('Черновик')
    const { dictation, wrapper } = await recording(draft)
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить' })
    dictation.stop()
    await flushPromises()
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить хлеб' })
    Socket.instance.event({
      type: 'dictation.final',
      text: 'Купить хлеб.',
      transcription_id: 'tr-1',
    })
    screen.paint()
    expect(draft.value).toBe('Черновик Купить хлеб.')
    expect(dictation.state.value).toBe('review')
    wrapper.unmount()
  })

  it('leaves no waiting revision behind a cancelled dictation', async () => {
    const screen = frames()
    const draft = ref('Черновик')
    const { dictation, wrapper } = await recording(draft)
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить' })
    screen.paint()
    expect(draft.value).toBe('Черновик Купить')
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить хлеб' })
    dictation.cancel()
    screen.paint()
    expect(draft.value).toBe('Черновик')
    wrapper.unmount()
  })
})

describe('retrying stopped dictation', () => {
  async function stopped() {
    const draft = ref('Черновик')
    const current = mountDictation(draft)
    current.dictation.start()
    await flushPromises()
    Socket.instance.event({ type: 'dictation.ready' })
    current.source.begin.mock.calls[0]![0].frame(new Uint8Array(24_000).fill(7).buffer)
    Socket.instance.event({ type: 'dictation.partial', text: 'Купить' })
    current.dictation.stop()
    await flushPromises()
    return { ...current, draft }
  }

  it('replaces the failed partial with the retried final in the same draft', async () => {
    const { dictation, draft, source, wrapper } = await stopped()
    const previous = Socket.instance
    const id = previous.id
    previous.readyState = 3
    previous.onclose?.()
    expect(dictation.problem.value).toBe('connection')
    expect(dictation.canRetry.value).toBe(true)
    expect(draft.value).toBe('Черновик Купить')

    dictation.retry()
    dictation.retry()
    await flushPromises()
    expect(Socket.instance.id).not.toBe(id)
    expect(dictation.state.value).toBe('finalizing')
    expect(dictation.canRetry.value).toBe(false)
    Socket.instance.event({ type: 'dictation.ready' })
    const audio = Socket.instance.send.mock.calls
      .map(([data]) => data)
      .filter((data) => data instanceof ArrayBuffer)
    expect(audio.map((data) => new Uint8Array(data))).toEqual([new Uint8Array(24_000).fill(7)])
    Socket.instance.event({
      type: 'dictation.final',
      text: 'Купить хлеб.',
      transcription_id: 'retried',
    })
    expect(draft.value).toBe('Черновик Купить хлеб.')
    expect(dictation.transcriptionId.value).toBe('retried')
    expect(dictation.state.value).toBe('review')
    expect(dictation.canRetry.value).toBe(false)
    expect(dictation.problem.value).toBeNull()
    expect(source.prepare).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it.each(['cancel', 'start', 'reset', 'sign-out', 'switch-account', 'switch-device'] as const)(
    'drops the failed recording on %s',
    async (action) => {
      const { dictation, source, wrapper } = await stopped()
      Socket.instance.event({ type: 'dictation.error', error: 'provider_error' })
      expect(dictation.canRetry.value).toBe(true)
      if (action === 'sign-out') account.value = null
      else if (action === 'switch-account') account.value = { id: 'other', device_id: 'browser' }
      else if (action === 'switch-device')
        account.value = { id: 'owner', device_id: 'other-browser' }
      else dictation[action]()
      await flushPromises()
      expect(dictation.canRetry.value).toBe(false)
      const sent = Socket.instance.send.mock.calls.length
      dictation.retry()
      expect(Socket.instance.send.mock.calls).toHaveLength(sent)
      if (action === 'start') {
        Socket.instance.event({ type: 'dictation.ready' })
        source.begin.mock.calls[1]![0].frame(new Uint8Array(24_000).fill(9).buffer)
        dictation.stop()
        await flushPromises()
        Socket.instance.event({ type: 'dictation.error', error: 'provider_error' })
        dictation.retry()
        Socket.instance.event({ type: 'dictation.ready' })
        const audio = Socket.instance.send.mock.calls
          .slice(sent)
          .map(([data]) => data)
          .filter((data) => data instanceof ArrayBuffer)
        expect(audio.map((data) => new Uint8Array(data))).toEqual([
          new Uint8Array(24_000).fill(9),
          new Uint8Array(24_000).fill(9),
        ])
      }
      wrapper.unmount()
    },
  )

  it('offers no retry for a non-retryable failure after Stop', async () => {
    const { dictation, wrapper } = await stopped()
    Socket.instance.event({ type: 'dictation.error', error: 'no_access' })
    expect(dictation.problem.value).toBe('no_access')
    expect(dictation.canRetry.value).toBe(false)
    const sent = Socket.instance.send.mock.calls.length
    dictation.retry()
    expect(Socket.instance.send.mock.calls).toHaveLength(sent)
    wrapper.unmount()
  })
})
