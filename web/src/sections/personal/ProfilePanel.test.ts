import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { ApiError } from '@/core/http'
import { russianPlural } from '@/i18n'
import ru from '@/locales/ru.json'
import { Profile } from './api'
import ProfileForm from './ProfileForm.vue'
import ProfilePanel from './ProfilePanel.vue'
import type { ProfileField } from './profile'

const expectedText = {
  saving: 'Сохраняю…',
  draftKept: 'Введённые значения остались в форме.',
  conflict: 'Данные уже изменились. Ваш ввод сохранён — проверьте актуальные значения и повторите.',
}

const mocks = vi.hoisted(() => ({ operate: vi.fn(), load: vi.fn(), failed: vi.fn() }))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'UTC' }, load: mocks.load }),
}))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))
const profile = Profile.parse({
  user_id: 'owner',
  timezone: 'UTC',
  height_cm: '170.0',
  birth_date: null,
  gender: null,
  gender_origin: 'unset',
  revision: 3,
})
const panelStub = {
  name: 'Panel',
  props: ['autofocus'],
  template: '<section><div><slot /></div><footer><slot name="footer" /></footer></section>',
}
function globals() {
  return {
    plugins: [
      createI18n({
        legacy: false,
        locale: 'ru',
        messages: { ru },
        pluralRules: { ru: russianPlural },
      }),
    ],
    stubs: {
      Panel: panelStub,
      RouterLink: { template: '<a><slot /></a>' },
    },
  }
}
function show(value = profile, focusField?: ProfileField, readOnly = false) {
  return mount(ProfilePanel, {
    props: { profile: value, today: '2026-10-03', open: true, focusField, readOnly },
    attachTo: document.body,
    global: globals(),
  })
}
enableAutoUnmount(afterEach)
beforeEach(() => {
  mocks.operate.mockReset().mockImplementation(async (_kind, changes, revisions) => ({
    profile: { ...profile, ...changes, revision: revisions.owner + 1 },
  }))
  mocks.failed.mockReset()
})

describe('explicit profile gender', () => {
  it('shows imperial height but keeps an explicitly labeled centimeter edit canonical', async () => {
    const wrapper = show({ ...profile, height_cm: '180', display_units: 'imperial' })
    expect(wrapper.get('[data-testid="profile-height-display"]').text()).toBe('5 ft 10,9 in')
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '180',
    )
    expect(wrapper.text()).toContain('Ввод роста: сантиметры (см)')
    await wrapper.get('[data-testid="profile-height"]').setValue('181')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('profile.update', { height_cm: '181' }, { owner: 3 })
  })
  it('leaves gender unset and sends no write when an unchanged form is saved', async () => {
    const wrapper = show()
    expect(wrapper.get<HTMLSelectElement>('[data-testid="profile-gender"]').element.value).toBe('')
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).not.toHaveBeenCalled()
  })
  it('writes only an explicitly selected gender and preserves the captured revision', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-gender"]').setValue('female')
    await wrapper.setProps({ profile: { ...profile, height_cm: '180', revision: 4 } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('profile.update', { gender: 'female' }, { owner: 3 })
  })
  it('updates another field without writing the default figure gender', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('171')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('profile.update', { height_cm: '171' }, { owner: 3 })
  })
  it('supports explicit removal and keeps invalid height out of the operation', async () => {
    const wrapper = show({ ...profile, gender: 'male', gender_origin: 'manual' })
    await wrapper.get('[data-testid="profile-gender"]').setValue('')
    await wrapper.get('[data-testid="profile-height"]').setValue('0')
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="profile-height"]').setValue('170')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('profile.update', { gender: null }, { owner: 3 })
  })
  it('captures the current profile again when the edit panel is reopened', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('172')
    await wrapper.setProps({ open: false, profile: { ...profile, height_cm: '180', revision: 4 } })
    await wrapper.setProps({ open: true })
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '180',
    )
    await wrapper.get('[data-testid="profile-height"]').setValue('181')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('profile.update', { height_cm: '181' }, { owner: 4 })
  })
  it('closes the existing modal after a successful save through the shared editor', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-birth"]').setValue('1990-10-03')
    expect(wrapper.get('[data-testid="profile-age"]').text()).toBe('36 лет')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'profile.update',
      { birth_date: '1990-10-03' },
      { owner: 3 },
    )
    expect(wrapper.emitted('update:open')).toEqual([[false]])
  })
  it('focuses the requested missing profile field when opened from another form', async () => {
    const wrapper = show(profile, 'gender')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="profile-gender"]').element)
    expect(wrapper.get('[data-testid="profile-age"]').text()).toBe('—')
  })
})

describe('profile form completion', () => {
  it('associates the footer submit with its form and blocks duplicate saves while pending', async () => {
    let complete: (value: unknown) => void = () => undefined
    mocks.operate.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve
        }),
    )
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('172')
    const button = wrapper.get<HTMLButtonElement>('footer button[type="submit"]')
    expect(button.element.form).toBe(wrapper.get('form').element)
    expect(wrapper.get('form').find('button[type="submit"]').exists()).toBe(false)
    button.element.click()
    await wrapper.vm.$nextTick()
    expect(button.element.disabled).toBe(true)
    expect(button.text()).toBe(expectedText.saving)
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).toHaveBeenCalledOnce()
    complete({ profile: { ...profile, height_cm: '172', revision: 4 } })
    await flushPromises()
    expect(wrapper.emitted('update:open')).toEqual([[false]])
  })

  it('links errors to each field and focuses the first invalid field in form order', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-birth"]').setValue('2026-10-04')
    await wrapper.get('[data-testid="profile-height"]').setValue('20')
    await wrapper.get('[data-testid="profile-zone"]').setValue('Unknown/Zone')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).not.toHaveBeenCalled()
    for (const field of ['birth', 'height', 'zone']) {
      const input = wrapper.get(`[data-testid="profile-${field}"]`)
      expect(input.attributes('aria-invalid')).toBe('true')
      const descriptionIds = (input.attributes('aria-describedby') ?? '').split(' ')
      const error = wrapper.findAll('[role="alert"]').find((node) => {
        const id = node.attributes('id')
        return !!id && descriptionIds.includes(id)
      })
      expect(error).toBeDefined()
      expect(error!.text()).not.toBe('')
    }
    expect(document.activeElement).toBe(wrapper.get('[data-testid="profile-birth"]').element)
  })

  it('keeps refusal, draft and captured revision together until the user retries or resets', async () => {
    const failure = new ApiError(409, null, null, null, null, 'conflicting revision')
    mocks.operate.mockRejectedValueOnce(failure)
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('175')
    await wrapper.setProps({ profile: { ...profile, height_cm: '180', revision: 4 } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const error = wrapper.get('[data-testid="profile-server-error"]')
    expect(error.get('[role="alert"]').text()).toBe(expectedText.conflict)
    expect(error.text()).toContain(expectedText.draftKept)
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '175',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenNthCalledWith(
      2,
      'profile.update',
      { height_cm: '175' },
      { owner: 3 },
    )
  })

  it('has separate field and hint IDs when more than one profile editor is mounted', () => {
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h('div', [
            h(ProfileForm, { profile, today: '2026-10-03' }),
            h(ProfileForm, { profile, today: '2026-10-03' }),
          ]),
      }),
      { global: globals() },
    )
    const ids = wrapper.findAll('[id]').map((node) => node.attributes('id'))
    expect(new Set(ids).size).toBe(ids.length)
    for (const form of wrapper.findAll('form')) {
      const birth = form.get('[data-testid="profile-birth"]')
      expect(form.get('label').attributes('for')).toBe(birth.attributes('id'))
      expect(
        form
          .findAll('[id]')
          .some((node) => node.attributes('id') === birth.attributes('aria-describedby')),
      ).toBe(true)
    }
  })

  it('does not focus a field or offer footer actions when the profile is read-only', async () => {
    const wrapper = show(profile, 'gender', true)
    await flushPromises()
    expect(wrapper.getComponent(panelStub).props('autofocus')).toBe(false)
    expect(document.activeElement).not.toBe(wrapper.get('[data-testid="profile-gender"]').element)
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.find('footer button').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).not.toHaveBeenCalled()
  })
})
