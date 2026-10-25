import { enableAutoUnmount, mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import Composer from './Composer.vue'

enableAutoUnmount(afterEach)

const dictation = vi.hoisted(() => ({ value: {} as Record<string, unknown> }))
vi.mock('@/dictation/useDictation', () => ({ useDictation: () => dictation.value }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
// A phone with the keyboard up: the tab bar is hidden, so the field shows its own microphone.
const layout = vi.hoisted(() => ({
  tabBarHidden: undefined as unknown as { value: boolean },
  wide: undefined as unknown as { value: boolean },
}))
vi.mock('@vueuse/core', async (original) => {
  const { ref } = await import('vue')
  layout.wide = ref(false)
  return { ...(await original<typeof import('@vueuse/core')>()), useMediaQuery: () => layout.wide }
})
vi.mock('@/app/composing', async (original) => {
  const { ref } = await import('vue')
  layout.tabBarHidden = ref(true)
  return {
    ...(await original<typeof import('@/app/composing')>()),
    tabBarHidden: layout.tabBarHidden,
  }
})

function render(
  props: {
    modelValue?: string
    photo?: string
    photoPending?: boolean
    photoUploading?: boolean
    disabled?: boolean
    replyTo?: string
    stoppable?: boolean
    stopping?: boolean
  } = {},
  attached = false,
) {
  return mount(Composer, {
    ...(attached ? { attachTo: document.body } : {}),
    props: { modelValue: 'Сохранённый черновик', ...props },
    global: {
      stubs: {
        Button: { template: '<button><slot /></button>' },
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

describe('dictation error presentation', () => {
  beforeEach(() => {
    layout.tabBarHidden.value = true
    dictation.value = {
      state: ref('failed'),
      problem: ref('not_configured'),
      failure: ref({}),
      active: ref(false),
      canRetry: ref(false),
      elapsed: ref(0),
      limitReached: ref(false),
      incomplete: ref(false),
      transcriptionId: ref(null),
      reset: vi.fn(),
    }
  })

  it('explains missing setup, links to voice settings, and keeps the draft', () => {
    const wrapper = render()
    const notice = wrapper.get('[data-testid="dictation-problem"]')
    expect(notice.text()).toContain('dictation.not_configured')
    expect(notice.get('a').attributes('href')).toBe('/settings/speech')
    expect(notice.text()).not.toContain('dictation.busy')
    expect(wrapper.get('textarea').element.value).toBe('Сохранённый черновик')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('only displays a delay when provided, and explains preservation of partial text', async () => {
    dictation.value.problem = ref('busy')
    const wrapper = render()
    expect(wrapper.text()).not.toContain('dictation.retryAfter')
    ;(dictation.value.failure as ReturnType<typeof ref>).value = { retryAfter: 45 }
    ;(dictation.value.incomplete as ReturnType<typeof ref>).value = true
    await flushPromises()
    expect(wrapper.text()).toContain('dictation.retryAfter')
    expect(wrapper.text()).toContain('dictation.incomplete')
    expect(wrapper.find('a').exists()).toBe(false)
  })
})

describe('composer dictation actions', () => {
  beforeEach(() => {
    dictation.value = {
      state: ref('idle'),
      problem: ref(null),
      failure: ref({}),
      active: ref(false),
      elapsed: ref(0),
      limitReached: ref(false),
      incomplete: ref(false),
      transcriptionId: ref(null),
      start: vi.fn(),
      stop: vi.fn(),
      cancel: vi.fn(),
      reset: vi.fn(),
    }
  })

  it.each([
    { modelValue: 'Дополнить готовый текст' },
    { modelValue: '', photo: 'blob:synthetic-photo' },
    { modelValue: 'Подпись к фото', photo: 'blob:synthetic-photo' },
  ])('keeps dictation next to sending with an existing draft or photo: %o', async (props) => {
    const wrapper = render(props)
    const microphone = wrapper.get('[data-testid="dictation-start"]')
    const send = wrapper.get('[data-testid="composer-send"]')
    expect((microphone.element as HTMLButtonElement).disabled).toBe(false)
    expect((send.element as HTMLButtonElement).disabled).toBe(false)
    expect(microphone.attributes('type')).toBe('button')
    expect(send.attributes('type')).toBe('submit')
    await microphone.trigger('click')
    expect(dictation.value.start).toHaveBeenCalledOnce()
    expect(wrapper.emitted('send')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.get('textarea').element.value).toBe(props.modelValue)
    if (props.photo) expect(wrapper.get('img').attributes('src')).toBe(props.photo)
  })

  it('holds the place of a photo being prepared and waits for it before sending', async () => {
    const wrapper = render({ modelValue: 'Подпись к фото', photo: 'blob:old', photoPending: true })
    expect(wrapper.get('[data-testid="photo-pending"]').attributes('role')).toBe('status')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('[data-testid="composer-send"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ photoPending: false })
    expect(wrapper.find('[data-testid="photo-pending"]').exists()).toBe(false)
    expect(wrapper.get('img').attributes('src')).toBe('blob:old')
    expect(wrapper.get('[data-testid="composer-send"]').attributes('disabled')).toBeUndefined()
  })

  it('shows the photo uploading and lets the send wait for it', async () => {
    const wrapper = render({ modelValue: '', photo: 'blob:meal', photoUploading: true })
    expect(wrapper.get('img').attributes('src')).toBe('blob:meal')
    expect(wrapper.get('[data-testid="photo-uploading"]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-testid="composer-send"]').attributes('disabled')).toBeUndefined()
    await wrapper.setProps({ photoUploading: false })
    expect(wrapper.find('[data-testid="photo-uploading"]').exists()).toBe(false)
  })

  it('replaces the normal actions with compact recording controls inside the composer', async () => {
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'recording'
    ;(dictation.value.active as ReturnType<typeof ref>).value = true
    const wrapper = render()
    const controls = wrapper.get('form').get('[data-testid="dictation-controls"]')
    expect(controls.findAll('button')).toHaveLength(2)
    expect(wrapper.find('[data-testid="dictation-start"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="composer-send"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="assistant.attachPhoto"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="dictation-timer"]').text()).toBe('0:00')
    const stop = wrapper.get('[data-testid="dictation-stop"]')
    expect(stop.text()).toBe('')
    expect(stop.attributes('aria-label')).toBe('dictation.stopDictation')
    expect(stop.attributes('type')).toBe('button')
    await stop.trigger('click')
    expect(dictation.value.stop).toHaveBeenCalledOnce()
    expect(dictation.value.start).not.toHaveBeenCalled()
    expect(wrapper.emitted('send')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('send')).toBeUndefined()
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'finalizing'
    await flushPromises()
    expect(wrapper.find('[data-testid="dictation-stop"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="dictation-cancel"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="dictation-timer"]').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('send')).toBeUndefined()
    await wrapper.get('[data-testid="dictation-cancel"]').trigger('click')
    expect(dictation.value.cancel).toHaveBeenCalledOnce()
  })

  it('leaves the field editable during dictation and cancellation remains an explicit action', async () => {
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'recording'
    ;(dictation.value.active as ReturnType<typeof ref>).value = true
    const wrapper = render()
    await wrapper.get('textarea').setValue('Ручная поправка')
    expect(wrapper.emitted('update:modelValue')).toEqual([['Ручная поправка']])
    const cancel = wrapper.get('[data-testid="dictation-cancel"]')
    expect(cancel.attributes('aria-label')).toBe('dictation.cancel')
    expect(cancel.text()).toBe('')
    await cancel.trigger('click')
    expect(dictation.value.cancel).toHaveBeenCalledOnce()
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it.each(['permission', 'recording', 'finalizing'])(
    'Escape cancels %s without sending or stopping the assistant',
    async (state) => {
      ;(dictation.value.state as ReturnType<typeof ref>).value = state
      ;(dictation.value.active as ReturnType<typeof ref>).value = true
      const wrapper = render({ modelValue: '', stoppable: true })
      expect(wrapper.find('[data-testid="composer-stop"]').exists()).toBe(false)
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
      expect(dictation.value.cancel).toHaveBeenCalledOnce()
      expect(wrapper.emitted('send')).toBeUndefined()
      expect(wrapper.emitted('stop')).toBeUndefined()
    },
  )

  it('announces recording state without announcing each tick of the timer', async () => {
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'recording'
    ;(dictation.value.active as ReturnType<typeof ref>).value = true
    const wrapper = render()
    const status = wrapper.get('[data-testid="dictation-status"]')
    expect(status.attributes('role')).toBe('status')
    expect(status.text()).toBe('dictation.recording')
    ;(dictation.value.elapsed as ReturnType<typeof ref>).value = 65
    await flushPromises()
    expect(wrapper.get('[data-testid="dictation-timer"]').text()).toBe('1:05')
    expect(status.text()).toBe('dictation.recording')
    expect(status.find('[data-testid="dictation-timer"]').exists()).toBe(false)
  })

  it('keeps focus on cancellation while finishing, then returns it to the microphone', async () => {
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'recording'
    ;(dictation.value.active as ReturnType<typeof ref>).value = true
    const wrapper = render({}, true)
    ;(wrapper.get('[data-testid="dictation-stop"]').element as HTMLButtonElement).focus()
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'finalizing'
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="dictation-cancel"]').element)
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'review'
    ;(dictation.value.active as ReturnType<typeof ref>).value = false
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="dictation-start"]').element)
  })

  it('does not take focus from text being edited when recognition finishes', async () => {
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'recording'
    ;(dictation.value.active as ReturnType<typeof ref>).value = true
    const wrapper = render({}, true)
    wrapper.get('textarea').element.focus()
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'finalizing'
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('textarea').element)
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'review'
    ;(dictation.value.active as ReturnType<typeof ref>).value = false
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('textarea').element)
  })

  it('keeps keyboard focus in the edited draft when dictation starts', async () => {
    const wrapper = render({}, true)
    wrapper.get('textarea').element.focus()
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'permission'
    ;(dictation.value.active as ReturnType<typeof ref>).value = true
    await flushPromises()
    expect(wrapper.find('[data-testid="dictation-controls"]').exists()).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('textarea').element)
  })

  it('requires explicit sending after dictation and passes the transcription identity', async () => {
    ;(dictation.value.state as ReturnType<typeof ref>).value = 'review'
    ;(dictation.value.transcriptionId as ReturnType<typeof ref>).value = 'transcription-1'
    const wrapper = render({ modelValue: 'Черновик и продиктованное дополнение' })
    expect(wrapper.text()).not.toContain('dictation.review')
    expect(wrapper.find('[data-testid="dictation-controls"]').exists()).toBe(false)
    expect(wrapper.emitted('send')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('send')).toEqual([
      [
        {
          text: 'Черновик и продиктованное дополнение',
          transcriptionId: 'transcription-1',
        },
      ],
    ])
  })
})

describe('stopping a running request', () => {
  beforeEach(() => {
    dictation.value = {
      state: ref('idle'),
      problem: ref(null),
      failure: ref({}),
      active: ref(false),
      elapsed: ref(0),
      limitReached: ref(false),
      incomplete: ref(false),
      transcriptionId: ref(null),
      reset: vi.fn(),
    }
  })

  it('puts the stop where the send button is while the field is empty', async () => {
    const wrapper = render({ modelValue: '', stoppable: true })
    expect(wrapper.find('[data-testid="composer-send"]').exists()).toBe(false)
    const stop = wrapper.get('[data-testid="composer-stop"]')
    expect(stop.attributes('aria-label')).toBe('assistant.controls.stop')
    expect(stop.attributes('type')).toBe('button')
    await stop.trigger('click')
    expect(wrapper.emitted('stop')).toEqual([[]])
    expect(wrapper.emitted('send')).toBeUndefined()

    // Written text is sent and waits its turn; the stop comes back once the field is empty.
    await wrapper.setProps({ modelValue: 'Ещё вопрос' })
    expect(wrapper.find('[data-testid="composer-stop"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="composer-send"]').attributes('disabled')).toBeUndefined()
    await wrapper.setProps({ modelValue: '' })
    expect(wrapper.find('[data-testid="composer-stop"]').exists()).toBe(true)
  })

  it('shows a stop on its way and does not send it twice', () => {
    const wrapper = render({ modelValue: '', stoppable: true, stopping: true })
    const stop = wrapper.get('[data-testid="composer-stop"]')
    expect(stop.attributes('aria-label')).toBe('assistant.controls.stopping')
    expect(stop.attributes('disabled')).toBeDefined()
  })
})

describe('one microphone at a time', () => {
  it('leaves dictation to the tab bar on a phone while the tab bar shows', () => {
    dictation.value = {
      state: ref('idle'),
      problem: ref(null),
      failure: ref(null),
      active: ref(false),
      elapsed: ref(0),
      limitReached: ref(false),
      incomplete: ref(false),
      transcriptionId: ref(null),
      reset: vi.fn(),
    }
    layout.tabBarHidden.value = false
    expect(render().find('[data-testid="dictation-start"]').exists()).toBe(false)
    layout.wide.value = true
    expect(render().find('[data-testid="dictation-start"]').exists()).toBe(true)
    layout.wide.value = false
    layout.tabBarHidden.value = true
    expect(render().find('[data-testid="dictation-start"]').exists()).toBe(true)
  })
})
