import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { i18n } from '@/i18n'
import RecordingPlayer from './RecordingPlayer.vue'
import type { Recording } from './api'

const context = vi.hoisted(() => ({
  session: {} as { me: { id: string; device_id: string } | null; state: string },
}))
vi.mock('@/stores/session', () => ({ useSession: () => context.session }))
const id = '00000000-0000-4000-8000-000000000001'
const recording: Recording = {
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
    status: 'pending',
    preview: null,
    error_code: null,
    updated_at: null,
    retry_may_repeat: false,
  },
}
const mounted: VueWrapper[] = []
function player() {
  const wrapper = mount(RecordingPlayer, {
    props: { recording, owner: 'owner' },
    global: { plugins: [i18n] },
  })
  mounted.push(wrapper)
  return wrapper
}
async function fail(wrapper: VueWrapper, code: number) {
  Object.defineProperty(wrapper.get('audio').element, 'error', { value: { code } })
  await wrapper.get('audio').trigger('error')
}
beforeEach(() => {
  context.session = reactive({ me: { id: 'owner', device_id: 'device' }, state: 'ready' })
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
})
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

describe('recording playback', () => {
  it('uses a protected WAV source when this browser cannot play the original codec', () => {
    vi.mocked(HTMLMediaElement.prototype.canPlayType).mockReturnValue('')
    const wrapper = player()
    expect(wrapper.get('audio').attributes('src')).toBe(
      `/v1/ambient-audio/batches/${id}/playback?format=wav`,
    )
    expect(wrapper.get('audio').attributes('preload')).toBe('none')
    expect(wrapper.get('audio').attributes('autoplay')).toBeUndefined()
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled()
  })

  it('offers compatible playback once after a codec failure without automatically playing', async () => {
    const wrapper = player()
    await fail(wrapper, 4)
    expect(wrapper.text()).toContain('Подготовить совместимое аудио')
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('audio').attributes('src')).toContain('/playback?format=wav')
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledOnce()
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled()
    await wrapper.get('audio').trigger('error')
    expect(wrapper.text()).not.toContain('Подготовить совместимое аудио')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('requests metadata verification after a network failure rather than hiding it as a codec issue', async () => {
    const wrapper = player()
    await fail(wrapper, 2)
    expect(wrapper.text()).not.toContain('Подготовить совместимое аудио')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.get('audio').attributes('src')).toBe(`/v1/ambient-audio/batches/${id}`)
  })

  it('synchronously releases audio on device change and page navigation', () => {
    const wrapper = player()
    const audio = wrapper.get('audio').element
    context.session.me!.device_id = 'another-device'
    expect(audio.getAttribute('src')).toBeNull()
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledOnce()
    const next = player()
    const nextAudio = next.get('audio').element
    window.dispatchEvent(new Event('pagehide'))
    expect(nextAudio.getAttribute('src')).toBeNull()
    expect(HTMLMediaElement.prototype.load).toHaveBeenCalled()
  })
})
