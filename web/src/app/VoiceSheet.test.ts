import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { live } from '@/core/live'
import type { CaptureHandlers } from '@/dictation/audio'
import VoiceSheet from './VoiceSheet.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(async () => ({ asr: { state: 'configured' } })),
  handOff: vi.fn(async () => undefined),
  push: vi.fn(async () => undefined),
  toast: Object.assign(vi.fn(), { error: vi.fn() }),
  order: [] as string[],
}))
vi.mock('@/core/client', () => ({
  api: { get: mocks.get, hooks: {} },
  updates: { hold: () => () => undefined },
}))
vi.mock('@/dictation/audio', () => ({
  BrowserMicrophone: class {
    prepare = vi.fn(async () => undefined)
    begin = vi.fn((handlers: CaptureHandlers) => handlers.frame(new ArrayBuffer(24_000)))
    stop = vi.fn(async () => undefined)
    release = vi.fn()
  },
  MicrophoneError: class extends Error {},
}))
vi.mock('@/sections/assistant/useComposerDraft', () => ({ handOff: mocks.handOff }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ account: { store: 'store', journal: 'journal' } }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('vue-sonner', () => ({ toast: mocks.toast }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

class Socket {
  static OPEN = 1
  static CONNECTING = 0
  static instance: Socket
  readyState = 1
  bufferedAmount = 0
  binaryType = ''
  id = ''
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
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

const panel = { props: ['open'], template: '<div v-if="open"><slot /></div>' }

function sheet() {
  return mount(VoiceSheet, {
    global: {
      stubs: {
        Panel: panel,
        RouterLink: { template: '<a><slot /></a>' },
        Label: { template: '<label><slot /></label>' },
        Textarea: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<textarea :value="modelValue" data-testid="voice-text" />',
        },
      },
    },
  })
}

describe('voice sheet', () => {
  beforeEach(() => {
    vi.stubGlobal('WebSocket', Socket)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    live.start()
    mocks.order.length = 0
    mocks.handOff.mockImplementation(async () => void mocks.order.push('handOff'))
    mocks.push.mockImplementation(async () => void mocks.order.push('push'))
  })
  afterEach(() => {
    live.stop()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('starts dictation inside the tap that opened it', () => {
    const wrapper = sheet()
    ;(wrapper.vm as unknown as { dictate(): void }).dictate()
    // The microphone request and speech check both start inside the gesture.
    expect(mocks.get).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('starts on the first tap without a separate introduction', async () => {
    localStorage.clear()
    const wrapper = sheet()
    ;(wrapper.vm as unknown as { dictate(): void }).dictate()
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-testid="voice-status"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="voice-intro"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('hands the reviewed text to the assistant draft before opening the chat', async () => {
    const wrapper = sheet()
    ;(wrapper.vm as unknown as { dictate(): void }).dictate()
    await flushPromises()
    Socket.instance.event({ type: 'dictation.ready', sample_rate: 16000 })
    Socket.instance.event({ type: 'dictation.partial', text: 'купить' })
    await flushPromises()
    expect(wrapper.get('[data-testid="voice-live"]').text()).toBe('купить')
    await wrapper.get('[data-testid="voice-stop"]').trigger('click')
    await flushPromises()
    Socket.instance.event({
      type: 'dictation.final',
      text: 'Купить хлеб.',
      transcription_id: 'tr-1',
    })
    await flushPromises()
    await wrapper.get('[data-testid="voice-to-chat"]').trigger('click')
    await flushPromises()
    expect(mocks.handOff).toHaveBeenCalledWith('store', 'journal', {
      mode: 'append',
      text: 'Купить хлеб.',
      transcriptionId: 'tr-1',
    })
    expect(mocks.order).toEqual(['handOff', 'push'])
    expect(mocks.push).toHaveBeenCalledWith({ name: 'assistant' })
    wrapper.unmount()
  })
})
