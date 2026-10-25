import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { i18n } from '@/i18n'
import { setDeveloperMode } from '@/core/developer'
import { ApiError, NetworkError } from '@/core/http'
import RecordingsView from './RecordingsView.vue'

const context = vi.hoisted(() => ({
  session: {} as {
    me: { id: string; timezone: string; device_id: string; system_admin?: boolean } | null
    state: string
  },
  api: {
    get: vi.fn<(path: string, signal?: AbortSignal) => Promise<unknown>>(),
    send: vi.fn(),
    post: vi.fn(),
  },
}))
vi.mock('@/stores/session', () => ({ useSession: () => context.session }))
vi.mock('@/core/client', () => ({ api: context.api }))
vi.mock('@/ambient/native', () => ({
  nativeAmbientAudio: () => ({
    supported: ref(false),
    state: ref(null),
    failure: ref(null),
    busy: ref(false),
  }),
}))
const id = '00000000-0000-4000-8000-000000000001'
const item = {
  id,
  session_id: '00000000-0000-4000-8000-000000000002',
  sequence: 0,
  sha256: 'a'.repeat(64),
  stored: true,
  format: 'ogg-opus',
  bytes: 1600,
  duration_ms: 16_000,
  expires_at: '2026-10-13T06:04:02Z',
  started_at: '2026-10-06T06:04:02Z',
  received_at: '2026-10-06T06:05:00Z',
  state: 'stored',
  transcription: {
    status: 'ready',
    preview: 'Синтетический пример',
    error_code: null,
    updated_at: '2026-10-06T06:05:20Z',
    retry_may_repeat: false,
  },
}
const transcript = {
  id,
  status: 'ready',
  text: 'Синтетический пример\nВторая строка.',
  error_code: null,
  updated_at: '2026-10-06T06:05:20Z',
  retry_may_repeat: false,
  provider: 'synthetic',
  model: 'synthetic',
  transcribed_at: '2026-10-06T06:05:20Z',
}
const usage = { used_bytes: 1600, limits: { retention_days: null, max_user_bytes: 32 * 1024 ** 3 } }
const mounted: VueWrapper[] = []
async function screen() {
  const wrapper = mount(RecordingsView, {
    global: {
      plugins: [i18n],
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        RouterLink: { props: ['to'], template: '<a><slot /></a>' },
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((entry) => entry.text() === text)!
}
beforeEach(() => {
  vi.clearAllMocks()
  context.session = reactive({
    me: { id: 'owner', timezone: 'Europe/Moscow', device_id: 'device' },
    state: 'ready',
  })
  context.api.get.mockImplementation(async (path) =>
    path === '/v1/ambient-audio'
      ? usage
      : path.includes('/transcription')
        ? transcript
        : { recordings: [item], next_cursor: null },
  )
  context.api.send.mockImplementation(async (method) => ({
    status: 200,
    data:
      method === 'POST' ? { ...transcript, status: 'pending', text: null } : { id, deleted: true },
  }))
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
})
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('recordings screen', () => {
  it('shows original account-local timestamps, configurable retention, text and same-origin audio without autoplay', async () => {
    const wrapper = await screen()
    expect(wrapper.get(`[data-recording="${id}"] > button`).text()).toContain('09:04 · 16 с')
    // Storage and retention are technical details, not shown to the family.
    expect(wrapper.find('[data-testid="recordings-usage"]').exists()).toBe(false)
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('09:04:02–09:04:18')
    expect(wrapper.get('[data-testid="recording-transcript"]').text()).toContain('Вторая строка.')
    const audio = wrapper.get('audio')
    expect(audio.attributes('src')).toBe(`/v1/ambient-audio/batches/${id}`)
    expect(audio.attributes('preload')).toBe('none')
    expect(audio.attributes('autoplay')).toBeUndefined()
    expect(context.api.send).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('паузы между ними')
  })

  it('shows storage and retention to an administrator in developer mode', async () => {
    context.session.me = { ...context.session.me!, system_admin: true }
    setDeveloperMode(true)
    try {
      const wrapper = await screen()
      expect(wrapper.get('[data-testid="recordings-usage"]').text()).toContain(
        'Автоматическое удаление по сроку отключено',
      )
    } finally {
      setDeveloperMode(false)
    }
  })

  it('requires deletion confirmation and binds the write to the current owner', async () => {
    const wrapper = await screen()
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    await button(wrapper, 'Удалить запись').trigger('click')
    expect(context.api.send).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="recording-confirmation"]').text()).toContain(
      'нельзя отменить',
    )
    context.api.get.mockImplementation(async (path) =>
      path === '/v1/ambient-audio' ? usage : { recordings: [], next_cursor: null },
    )
    await button(wrapper, 'Удалить').trigger('click')
    await flushPromises()
    expect(context.api.send).toHaveBeenCalledWith(
      'DELETE',
      `/v1/ambient-audio/batches/${id}`,
      undefined,
      expect.objectContaining({
        expectedUser: 'owner',
        beforeSend: expect.any(Function),
        signal: expect.any(AbortSignal),
      }),
    )
    expect(wrapper.find('audio').exists()).toBe(false)
    expect(wrapper.text()).toContain('Записей пока нет')
  })

  it('stops playback and clears all previous account content immediately on logout or account change', async () => {
    const wrapper = await screen()
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    const audio = wrapper.get('audio').element
    context.session.state = 'signed-out'
    expect(audio.getAttribute('src')).toBeNull()
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('Синтетический пример')
    expect(wrapper.find('audio').exists()).toBe(false)
  })

  it('does not show ready or empty states after an API refusal, and explains an expired detail', async () => {
    context.api.get.mockRejectedValueOnce(new ApiError(403, null, null, null, null))
    const denied = await screen()
    expect(denied.find('[data-state="error"]').exists()).toBe(true)
    expect(denied.find('[data-state="empty"]').exists()).toBe(false)
    expect(denied.text()).not.toContain('Текст готов')
    const wrapper = await screen()
    context.api.get.mockRejectedValueOnce(new ApiError(410, null, null, null, null))
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('срок хранения истёк')
    expect(wrapper.find('audio').exists()).toBe(false)
  })

  it('preserves content as stale after a failed refresh and reports unknown deletion without pretending it succeeded', async () => {
    const wrapper = await screen()
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    await button(wrapper, 'Удалить запись').trigger('click')
    context.api.send.mockRejectedValueOnce(new NetworkError(false))
    await button(wrapper, 'Удалить').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('исход операции неизвестен')
    expect(button(wrapper, 'Удалить').attributes('disabled')).toBeDefined()
    const toggle = wrapper.get(`[data-recording="${id}"] > button`)
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.get('#recordings-date').attributes('disabled')).toBeDefined()
    await toggle.trigger('click')
    expect(wrapper.text()).toContain('исход операции неизвестен')
    expect(context.api.send).toHaveBeenCalledTimes(1)
    context.api.get.mockRejectedValue(new NetworkError(false))
    await button(wrapper, 'Обновить список').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Синтетический пример')
  })

  it('replaces an errored audio element after explicit refresh so manual playback can recover', async () => {
    const wrapper = await screen()
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    const failed = wrapper.get('audio').element
    Object.defineProperty(failed, 'error', { value: { code: 2 } })
    await wrapper.get('audio').trigger('error')
    await wrapper.get('[data-testid="recording-player"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('audio').element).not.toBe(failed)
    expect(failed.getAttribute('src')).toBeNull()
    expect(wrapper.get('audio').attributes('src')).toBe(`/v1/ambient-audio/batches/${id}`)
    expect(wrapper.get('audio').attributes('autoplay')).toBeUndefined()
    expect(wrapper.get('[data-testid="recording-player"]').text()).not.toContain(
      'Не удалось воспроизвести',
    )
  })

  it('polls only pending transcripts while visible and cancels on hide or unmount', async () => {
    vi.useFakeTimers()
    context.api.get.mockImplementation(async (path) =>
      path === '/v1/ambient-audio'
        ? usage
        : path.includes('/transcription')
          ? transcript
          : {
              recordings: [
                {
                  ...item,
                  transcription: {
                    ...item.transcription,
                    status: 'pending',
                    preview: null,
                    updated_at: null,
                  },
                },
              ],
              next_cursor: null,
            },
    )
    const wrapper = await screen()
    expect(wrapper.text()).toContain('Распознаётся: готово 0 из 1')
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(20_000)
    expect(
      context.api.get.mock.calls.filter(([path]) => path.includes('/transcription')),
    ).toHaveLength(0)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()
    expect(
      context.api.get.mock.calls.filter(([path]) => path.includes('/transcription')),
    ).toHaveLength(1)
    // Every fragment settled: the card no longer speaks of recognition.
    expect(wrapper.text()).not.toContain('Распознаётся')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(
      context.api.get.mock.calls.filter(([path]) => path.includes('/transcription')),
    ).toHaveLength(1)
  })

  it('reads the list again when the page comes back into view, with no refresh button', async () => {
    const wrapper = await screen()
    expect(button(wrapper, 'Обновить список')).toBeUndefined()
    const listed = (path: string) => path.startsWith('/v1/ambient-audio/recordings')
    const lists = () => context.api.get.mock.calls.filter(([path]) => listed(path)).length
    expect(lists()).toBe(1)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(lists()).toBe(1)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(lists()).toBe(2)
  })

  it('confirms an explicit retry when the previous ASR outcome may have consumed provider quota', async () => {
    context.api.get.mockImplementation(async (path) =>
      path === '/v1/ambient-audio'
        ? usage
        : path.includes('/transcription')
          ? {
              ...transcript,
              status: 'failed',
              text: null,
              error_code: 'unknown_outcome',
              retry_may_repeat: true,
            }
          : { recordings: [item], next_cursor: null },
    )
    const wrapper = await screen()
    await wrapper.get(`[data-recording="${id}"] > button`).trigger('click')
    await flushPromises()
    await button(wrapper, 'Повторить распознавание').trigger('click')
    expect(context.api.send).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="recording-confirmation"]').text()).toContain(
      'снова использовать лимит',
    )
    await button(wrapper, 'Повторить распознавание').trigger('click')
    await flushPromises()
    expect(context.api.send).toHaveBeenCalledWith(
      'POST',
      `/v1/ambient-audio/batches/${id}/transcription/retry`,
      undefined,
      expect.objectContaining({ expectedUser: 'owner' }),
    )
    expect(wrapper.text()).toContain('Ожидает распознавания')
  })
})

describe('recordings as conversations', () => {
  it('joins fragments spoken in a row into one card and opens them one by one', async () => {
    const second = '00000000-0000-4000-8000-000000000003'
    const later = '00000000-0000-4000-8000-000000000004'
    context.api.get.mockImplementation(async (path) =>
      path === '/v1/ambient-audio'
        ? usage
        : path.includes('/transcription')
          ? { ...transcript, id: path.includes(second) ? second : id }
          : {
              recordings: [
                {
                  ...item,
                  id: later,
                  sequence: 2,
                  started_at: '2026-10-06T06:30:00Z',
                  transcription: { ...item.transcription, preview: 'Позже' },
                },
                {
                  ...item,
                  id: second,
                  sequence: 1,
                  started_at: '2026-10-06T06:04:30Z',
                  transcription: { ...item.transcription, status: 'processing', preview: null },
                },
                item,
              ],
              next_cursor: null,
            },
    )
    const wrapper = await screen()
    const cards = wrapper.findAll('[data-recording]')
    expect(cards.map((card) => card.attributes('data-fragments'))).toEqual(['1', '2'])
    const talk = wrapper.get(`[data-recording="${id}"]`)
    expect(talk.get('button').text()).toContain('09:04 · 44 с · 2 фрагмента')
    expect(talk.text()).toContain('Распознаётся: готово 1 из 2')
    await talk.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="recording-transcript"]').exists()).toBe(false)
    await wrapper.get(`[data-fragment="${second}"]`).trigger('click')
    await flushPromises()
    expect(wrapper.get(`#recording-${second}`).find('audio').attributes('src')).toBe(
      `/v1/ambient-audio/batches/${second}`,
    )
  })
})
