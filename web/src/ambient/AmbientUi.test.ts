import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref, type Ref } from 'vue'
import { i18n } from '@/i18n'
import AmbientSettings from './AmbientSettings.vue'
import type { AmbientState } from './native'

const context = vi.hoisted(() => ({
  native: {} as {
    supported: Ref<boolean>
    state: Ref<AmbientState | null>
    failure: Ref<string | null>
    busy: Ref<boolean>
    open: () => Promise<void>
    stop: () => Promise<void>
  },
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ state: 'ready' }) }))
vi.mock('./native', async (original) => ({
  ...(await original<typeof import('./native')>()),
  nativeAmbientAudio: () => context.native,
}))
const mounted: VueWrapper[] = []
function render(component: typeof AmbientSettings) {
  const wrapper = mount(component, {
    global: {
      plugins: [i18n],
      stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } },
    },
  })
  mounted.push(wrapper)
  return wrapper
}
beforeEach(() => {
  context.native = {
    supported: ref(true),
    state: ref({
      recording: false,
      queued_batches: 0,
      queued_bytes: 0,
      last_uploaded_at: null,
      error: null,
    }),
    failure: ref(null),
    busy: ref(false),
    open: vi.fn(async () => undefined),
    stop: vi.fn(async () => undefined),
  }
})
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))

describe('ambient capture settings', () => {
  it('hides both controls and status when the native capture capability is absent', () => {
    context.native.supported.value = false
    context.native.state.value!.recording = true
    expect(render(AmbientSettings).find('[data-testid="ambient-settings"]').exists()).toBe(false)
    expect(context.native.open).not.toHaveBeenCalled()
  })

  it('explains surrounding voices and retention before opening explicit native controls', async () => {
    const wrapper = render(AmbientSettings)
    expect(wrapper.text()).toContain('голоса окружающих людей')
    expect(wrapper.text()).toContain('пакетами до 30 секунд')
    expect(wrapper.text()).toContain('Долгие паузы не сохраняются')
    expect(wrapper.text()).toContain('7 дней')
    expect(context.native.open).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click')
    expect(context.native.open).toHaveBeenCalledOnce()
  })

  it('keeps the problem of a stopped recorder quiet in settings and hides a stale ended session', async () => {
    context.native.state.value!.error = 'codec_unavailable'
    const wrapper = render(AmbientSettings)
    const failure = wrapper.get('[data-testid="ambient-settings-failure"]')
    expect(failure.text()).toContain('Сжатая запись недоступна')
    expect(failure.attributes('role')).toBeUndefined()
    context.native.state.value!.error = 'not_authenticated'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="ambient-settings-failure"]').exists()).toBe(false)
  })
})
