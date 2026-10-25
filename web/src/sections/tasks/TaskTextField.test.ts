import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TaskImage } from './api'
import TaskTextField from './TaskTextField.vue'

const mocks = vi.hoisted(() => ({ upload: vi.fn() }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('vue-sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))
vi.mock('./images', () => ({ uploadTaskImage: mocks.upload }))
enableAutoUnmount(afterEach)

function clipboard(text: string, files: File[]) {
  return { getData: (type: string) => (type === 'text/plain' ? text : ''), files, items: [] }
}

async function pictured(images: TaskImage[]) {
  const emitted: TaskImage[][] = []
  const wrapper = mount(TaskTextField, {
    attachTo: document.body,
    props: {
      id: 'task-description',
      label: 'Подробности',
      maxlength: 10000,
      multiline: true,
      modelValue: 'Текст',
      images,
      imageLimit: 3,
      imageSource: (image: TaskImage, preview: boolean) =>
        `/${image.id}${preview ? '/preview' : ''}`,
      'onUpdate:images': (value: TaskImage[] | undefined) => {
        emitted.push(value ?? [])
        void wrapper.setProps({ images: value })
      },
    },
  })
  await wrapper.get('#task-description-edit').trigger('click')
  return { wrapper, emitted }
}

async function field(value: string, options: { multiline?: boolean; disabled?: boolean } = {}) {
  let draft = value
  const wrapper = mount(TaskTextField, {
    attachTo: document.body,
    props: {
      id: 'task-description',
      label: 'Подробности',
      maxlength: 10000,
      modelValue: value,
      ...options,
    },
  })
  await wrapper.setProps({
    'onUpdate:modelValue': (modelValue: string) => {
      draft = modelValue
      void wrapper.setProps({ modelValue })
    },
  })
  return { wrapper, draft: () => draft }
}

describe('task text fields', () => {
  it('shows full original text with inline links in reading mode', async () => {
    const text = 'До ссылки https://example.org/one, после.\nЕщё https://example.org/two.'
    const { wrapper } = await field(text, { multiline: true })
    expect(wrapper.get('[data-testid="task-description-text"]').element.textContent).toBe(text)
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      'https://example.org/one',
      'https://example.org/two',
    ])
    expect(wrapper.find('input, textarea').exists()).toBe(false)
    expect(wrapper.get('#task-description-edit').attributes('type')).toBe('button')
  })

  it('edits the parent draft immediately and keeps the editor open on blur', async () => {
    const { wrapper, draft } = await field('Исходный текст')
    await wrapper.get('#task-description-edit').trigger('click')
    const input = wrapper.get<HTMLInputElement>('#task-description')
    expect(document.activeElement).toBe(input.element)
    expect(input.element.value).toBe('Исходный текст')
    expect(input.attributes('maxlength')).toBe('10000')
    await input.setValue('Новый текст https://example.org/new')
    await flushPromises()
    expect(draft()).toBe('Новый текст https://example.org/new')
    expect(wrapper.emitted('update:modelValue')).toEqual([['Новый текст https://example.org/new']])
    await input.trigger('blur')
    expect(wrapper.find('#task-description').exists()).toBe(true)
  })

  it.each(['Check', 'Enter', 'Escape'])(
    '%s ends editing without changing the draft',
    async (key) => {
      const { wrapper, draft } = await field('Исходный текст')
      await wrapper.get('#task-description-edit').trigger('click')
      const input = wrapper.get<HTMLInputElement>('#task-description')
      const edited = 'Новый текст https://example.org/new'
      await input.setValue(edited)
      await flushPromises()
      if (key === 'Check') {
        const done = wrapper.get('button[aria-label="common.done: Подробности"]')
        expect(done.attributes('type')).toBe('button')
        await done.trigger('click')
      } else {
        await input.trigger('keydown', { key })
      }
      await flushPromises()
      expect(draft()).toBe(edited)
      expect(wrapper.emitted('update:modelValue')).toEqual([[edited]])
      expect(wrapper.find('input, textarea').exists()).toBe(false)
      expect(wrapper.get('[data-testid="task-description-text"]').element.textContent).toBe(edited)
      expect(wrapper.get('a').attributes('href')).toBe('https://example.org/new')
      expect(document.activeElement).toBe(wrapper.get('#task-description-edit').element)
    },
  )

  it.each(['ctrlKey', 'metaKey'])(
    'keeps multiline Enter in the editor; %s+Enter ends editing',
    async (modifier) => {
      const { wrapper, draft } = await field('Первая строка', { multiline: true })
      await wrapper.get('#task-description-edit').trigger('click')
      const textarea = wrapper.get<HTMLTextAreaElement>('textarea#task-description')
      const edited = 'Первая строка\nВторая строка'
      await textarea.setValue(edited)
      await textarea.trigger('keydown', { key: 'Enter' })
      expect(wrapper.find('textarea').exists()).toBe(true)
      await textarea.trigger('keydown', { key: 'Enter', [modifier]: true })
      await flushPromises()
      expect(wrapper.find('textarea').exists()).toBe(false)
      expect(draft()).toBe(edited)
      expect(wrapper.emitted('update:modelValue')).toEqual([[edited]])
    },
  )

  it('does not finish single-line editing while an input method is composing', async () => {
    const { wrapper } = await field('Текст')
    await wrapper.get('#task-description-edit').trigger('click')
    await wrapper.get('#task-description').trigger('keydown', { key: 'Enter', isComposing: true })
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('leaves viewer links available without an edit control', async () => {
    const text = 'Открыть https://example.org/view'
    const { wrapper } = await field(text, { disabled: true })
    expect(wrapper.find('button, input, textarea').exists()).toBe(false)
    expect(wrapper.get('a').attributes('href')).toBe('https://example.org/view')
    expect(wrapper.get('[data-testid="task-description-text"]').element.textContent).toBe(text)
  })

  it('returns to reading mode when editing becomes disabled, preserving the draft', async () => {
    const { wrapper, draft } = await field('Исходный текст')
    await wrapper.get('#task-description-edit').trigger('click')
    await wrapper.get('#task-description').setValue('Правка https://example.org/kept')
    await wrapper.setProps({ disabled: true })
    expect(wrapper.find('button, input, textarea').exists()).toBe(false)
    expect(wrapper.get('a').attributes('href')).toBe('https://example.org/kept')
    expect(draft()).toBe('Правка https://example.org/kept')
  })

  it('puts a pasted picture under the field and leaves pasted text to the field', async () => {
    const stored = { id: 'stored', width: 40, height: 30 }
    mocks.upload.mockReset().mockResolvedValue(stored)
    const { wrapper, emitted } = await pictured([])
    const shot = new File([new Uint8Array([1])], 'shot.png', { type: 'image/png' })
    const textarea = wrapper.get('#task-description')
    const text = new Event('paste', { cancelable: true })
    Object.assign(text, { clipboardData: clipboard('Просто текст', [shot]) })
    textarea.element.dispatchEvent(text)
    expect(text.defaultPrevented).toBe(false)
    expect(mocks.upload).not.toHaveBeenCalled()
    const picture = new Event('paste', { cancelable: true })
    Object.assign(picture, { clipboardData: clipboard('', [shot]) })
    textarea.element.dispatchEvent(picture)
    expect(picture.defaultPrevented).toBe(true)
    await flushPromises()
    expect(mocks.upload).toHaveBeenCalledWith(shot)
    expect(emitted).toEqual([[stored]])
    expect(wrapper.get('[data-testid="task-images"] img').attributes('src')).toBe('/stored/preview')
  })

  it('removes a picture while editing and keeps the field within its limit', async () => {
    const images = ['a', 'b', 'c'].map((id) => ({ id, width: 40, height: 30 }))
    mocks.upload.mockReset()
    const { wrapper, emitted } = await pictured(images)
    const shot = new File([new Uint8Array([1])], 'shot.png', { type: 'image/png' })
    const full = new Event('paste', { cancelable: true })
    Object.assign(full, { clipboardData: clipboard('', [shot]) })
    wrapper.get('#task-description').element.dispatchEvent(full)
    await flushPromises()
    expect(mocks.upload).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="tasks.images.remove"]').trigger('click')
    expect(emitted).toEqual([images.slice(1)])
  })
})
