import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { ApiError } from '@/core/http'
import ru from '@/locales/ru.json'
import personalFormsRu from '@/locales/personal-forms-ru.json'
import { Profile } from './api'
import CaliperPanel from './CaliperPanel.vue'

const ProfileStub = {
  name: 'ProfilePanel',
  props: ['profile', 'open', 'today', 'focusField'],
  emits: ['update:open'],
  template: '<div />',
}

const mocks = vi.hoisted(() => ({ operate: vi.fn(), failed: vi.fn() }))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))
const profile = Profile.parse({
  user_id: 'synthetic-owner',
  timezone: 'Europe/Moscow',
  birth_date: '1991-02-01',
  gender: 'male',
  revision: 3,
})
const panelStub = {
  template:
    '<section><div data-testid="panel-content"><slot /></div><footer data-testid="panel-footer"><slot name="footer" /></footer></section>',
}
function globals() {
  return {
    plugins: [
      createI18n({ legacy: false, locale: 'ru', messages: { ru: { ...ru, ...personalFormsRu } } }),
    ],
    stubs: { Panel: panelStub, CaliperMethod: true, ProfilePanel: ProfileStub },
  }
}
function show(value = profile, attachTo?: Element) {
  return mount(CaliperPanel, {
    props: { profile: value, day: '2026-10-05', today: '2026-10-05', open: true },
    attachTo,
    global: globals(),
  })
}
enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T06:00:00Z'))
  mocks.operate.mockReset().mockResolvedValue({})
  mocks.failed.mockReset()
})
afterEach(() => vi.useRealTimers())

describe('caliper recording form', () => {
  it('focuses on the three male protocol sites and saves them without extra folds', async () => {
    const wrapper = show()
    const primary = wrapper.get('[data-testid="caliper-primary"]')
    expect(primary.findAll('input').map((input) => input.attributes('data-testid'))).toEqual([
      'caliper-chest',
      'caliper-abdomen',
      'caliper-thigh',
    ])
    expect(wrapper.get<HTMLDetailsElement>('[data-testid="caliper-optional"]').element.open).toBe(
      false,
    )
    await primary.get('[data-testid="caliper-chest"]').setValue('15')
    await primary.get('[data-testid="caliper-abdomen"]').setValue('25')
    await primary.get('[data-testid="caliper-thigh"]').setValue('20')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { abdomen: '25', chest: '15', thigh: '20' },
      measured_at: '2026-10-05T09:00:00+03:00',
    })
  })

  it('keeps all sites visible until gender is selected', () => {
    const wrapper = show({ ...profile, gender: null })
    expect(wrapper.get('[data-testid="caliper-primary"]').findAll('input')).toHaveLength(5)
    expect(wrapper.find('[data-testid="caliper-optional"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="caliper-protocol"]').text()).toContain(
      'у мужчин грудь, живот и бедро',
    )
    expect(wrapper.get('[data-testid="caliper-protocol"]').text()).toContain(
      'у женщин трицепс, бок над тазом и бедро',
    )
  })

  it('preserves every entered fold and reveals values moved into optional fields when protocols switch', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="caliper-chest"]').setValue('15')
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('25')
    await wrapper.get('[data-testid="caliper-thigh"]').setValue('20')
    await wrapper.get('[data-testid="caliper-gender"]').setValue('female')
    const primary = () => wrapper.get('[data-testid="caliper-primary"]')
    const optional = () => wrapper.get<HTMLDetailsElement>('[data-testid="caliper-optional"]')
    expect(
      primary()
        .findAll('input')
        .map((input) => input.attributes('data-testid')),
    ).toEqual(['caliper-triceps', 'caliper-suprailiac', 'caliper-thigh'])
    expect(optional().element.open).toBe(true)
    expect(optional().get<HTMLInputElement>('[data-testid="caliper-chest"]').element.value).toBe(
      '15',
    )
    expect(optional().get<HTMLInputElement>('[data-testid="caliper-abdomen"]').element.value).toBe(
      '25',
    )
    expect(optional().get('summary').text()).toContain('Заполнено дополнительных точек: 2')
    await primary().get('[data-testid="caliper-triceps"]').setValue('21')
    await primary().get('[data-testid="caliper-suprailiac"]').setValue('18')
    optional().element.open = false
    await optional().trigger('toggle')
    await wrapper.get('[data-testid="caliper-gender"]').setValue('male')
    expect(optional().element.open).toBe(true)
    expect(optional().get<HTMLInputElement>('[data-testid="caliper-triceps"]').element.value).toBe(
      '21',
    )
    expect(
      optional().get<HTMLInputElement>('[data-testid="caliper-suprailiac"]').element.value,
    ).toBe('18')
    expect(primary().get<HTMLInputElement>('[data-testid="caliper-chest"]').element.value).toBe(
      '15',
    )
    await wrapper.get('[data-testid="caliper-gender"]').setValue('')
    expect(primary().findAll('input')).toHaveLength(5)
    expect(wrapper.find('[data-testid="caliper-optional"]').exists()).toBe(false)
    await wrapper.get('[data-testid="caliper-gender"]').setValue('female')
    expect(optional().element.open).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { abdomen: '25', suprailiac: '18', chest: '15', thigh: '20', triceps: '21' },
      measured_at: '2026-10-05T09:00:00+03:00',
      gender: 'female',
    })
  })

  it('saves abdomen and side without requiring the remaining method sites', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('20,5')
    await wrapper.get('[data-testid="caliper-suprailiac"]').setValue('15')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { abdomen: '20.5', suprailiac: '15' },
      measured_at: '2026-10-05T09:00:00+03:00',
    })
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(profile.revision).toBe(3)
  })

  it('writes explicit record demographics without updating the profile', async () => {
    const wrapper = show({ ...profile, gender: null, birth_date: null })
    await wrapper.get('[data-testid="caliper-triceps"]').setValue('21')
    await wrapper.get('[data-testid="caliper-suprailiac"]').setValue('18')
    await wrapper.get('[data-testid="caliper-thigh"]').setValue('22')
    await wrapper.get('[data-testid="caliper-gender"]').setValue('female')
    await wrapper.get('[data-testid="caliper-age"]').setValue('35')
    await wrapper.get('[data-testid="caliper-note"]').setValue('  После тренировки  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledTimes(1)
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { suprailiac: '18', thigh: '22', triceps: '21' },
      measured_at: '2026-10-05T09:00:00+03:00',
      gender: 'female',
      age_years: 35,
      note: 'После тренировки',
    })
  })

  it('rejects invalid folds and future times while keeping the typed data', async () => {
    const wrapper = show()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toContain('хотя бы одну')
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('101')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toContain('не больше 100')
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('20')
    await wrapper.get('[data-testid="caliper-measured"]').setValue('2026-10-06T09:00')
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).not.toHaveBeenCalled()
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-abdomen"]').element.value).toBe(
      '20',
    )
  })

  it('shows a server refusal inline and keeps the complete draft for a retry', async () => {
    const error = new ApiError(422, null, null, null, null, 'Синтетический отказ')
    mocks.operate.mockRejectedValue(error)
    const wrapper = show()
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('20')
    await wrapper.get('[data-testid="caliper-age"]').setValue('34')
    await wrapper.get('[data-testid="caliper-measured"]').setValue('2026-10-04T10:15')
    await wrapper.get('[data-testid="caliper-note"]').setValue('После тренировки')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.failed).toHaveBeenCalledWith(error)
    const failure = wrapper.get('[data-testid="caliper-server-error"]')
    expect(failure.get('[role="alert"]').text()).toBe('Синтетический отказ')
    expect(failure.text()).toContain('Введённые значения остались в форме.')
    expect(wrapper.emitted('update:open')).toBeUndefined()
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-abdomen"]').element.value).toBe(
      '20',
    )
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-age"]').element.value).toBe('34')
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-measured"]').element.value).toBe(
      '2026-10-04T10:15',
    )
    expect(wrapper.get<HTMLTextAreaElement>('[data-testid="caliper-note"]').element.value).toBe(
      'После тренировки',
    )
    const refused = mocks.operate.mock.calls[0]
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledTimes(2)
    expect(mocks.operate.mock.calls[1]).toEqual(refused)
  })

  it('associates the footer submit with the form and prevents duplicate writes while saving', async () => {
    let complete!: (value: unknown) => void
    const pending = new Promise((resolve) => {
      complete = resolve
    })
    mocks.operate.mockReturnValue(pending)
    const wrapper = show()
    const form = wrapper.get<HTMLFormElement>('form')
    const submit = wrapper.get<HTMLButtonElement>(
      '[data-testid="panel-footer"] button[type="submit"]',
    )
    expect(form.attributes('id')).toBeTruthy()
    expect(submit.attributes('form')).toBe(form.attributes('id'))
    expect(form.find('button[type="submit"]').exists()).toBe(false)
    await wrapper.get('[data-testid="caliper-chest"]').setValue('15')
    await form.trigger('submit')
    expect(submit.element.disabled).toBe(true)
    expect(wrapper.get<HTMLFieldSetElement>('fieldset').element.disabled).toBe(true)
    expect(submit.text()).toBe('Сохраняю…')
    await form.trigger('submit')
    expect(mocks.operate).toHaveBeenCalledTimes(1)
    complete({})
    await flushPromises()
    expect(submit.element.disabled).toBe(false)
    expect(wrapper.get<HTMLFieldSetElement>('fieldset').element.disabled).toBe(false)
  })

  it('links each invalid fold to its error and focuses the first invalid field in display order', async () => {
    const wrapper = show(profile, document.body)
    const chest = wrapper.get<HTMLInputElement>('[data-testid="caliper-chest"]')
    const abdomen = wrapper.get<HTMLInputElement>('[data-testid="caliper-abdomen"]')
    await chest.setValue('101')
    await abdomen.setValue('-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    for (const field of [chest, abdomen]) {
      expect(field.attributes('aria-invalid')).toBe('true')
      const descriptions = field.attributes('aria-describedby')!.split(' ')
      const linked = descriptions.map((id) => wrapper.get(`[id="${id}"]`).text())
      expect(linked.some((text) => text.includes('не больше 100'))).toBe(true)
    }
    const protocolId = wrapper.get('[data-testid="caliper-protocol"]').attributes('id')
    expect(chest.attributes('aria-describedby')!.split(' ')).toContain(protocolId)
    expect(document.activeElement).toBe(chest.element)
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it.each([
    ['caliper-triceps', 'caliper-optional', '101'],
    ['caliper-age', 'caliper-metadata-overrides', '121'],
  ])('reveals and focuses the invalid hidden field %s', async (fieldId, groupId, value) => {
    const wrapper = show(profile, document.body)
    await wrapper.get('[data-testid="caliper-chest"]').setValue('15')
    const field = wrapper.get<HTMLInputElement>(`[data-testid="${fieldId}"]`)
    await field.setValue(value)
    const group = wrapper.get<HTMLDetailsElement>(`[data-testid="${groupId}"]`)
    group.element.open = false
    await group.trigger('toggle')
    expect(group.element.open).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(group.element.open).toBe(true)
    expect(field.attributes('aria-invalid')).toBe('true')
    expect(document.activeElement).toBe(field.element)
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it('uses unique field, help and form ids for simultaneous instances', () => {
    const props = { profile, day: '2026-10-05', today: '2026-10-05', open: true }
    const wrapper = mount(
      defineComponent({
        setup: () => () => h('div', [h(CaliperPanel, props), h(CaliperPanel, props)]),
      }),
      { global: globals() },
    )
    const ids = wrapper.findAll('[id]').map((element) => element.attributes('id'))
    expect(ids).toHaveLength(new Set(ids).size)
    for (const panel of wrapper.findAllComponents(CaliperPanel)) {
      for (const field of panel.findAll('input, select, textarea')) {
        const id = field.attributes('id')
        expect(panel.find(`label[for="${id}"]`).exists()).toBe(true)
        for (const description of field.attributes('aria-describedby')?.split(' ') ?? [])
          expect(panel.find(`[id="${description}"]`).exists()).toBe(true)
      }
      const form = panel.get('form')
      expect(panel.get('button[type="submit"]').attributes('form')).toBe(form.attributes('id'))
    }
  })

  it('shows profile age for the selected date and keeps explicit overrides optional', async () => {
    const wrapper = show()
    expect(wrapper.get('[data-testid="caliper-profile-metadata"]').text()).toContain(
      'Возраст на дату замера: 35',
    )
    expect(
      wrapper.get<HTMLDetailsElement>('[data-testid="caliper-metadata-overrides"]').element.open,
    ).toBe(false)
    expect(wrapper.find('[data-testid="caliper-profile-notice"]').exists()).toBe(false)
    await wrapper.get('[data-testid="caliper-measured"]').setValue('2025-01-01T12:00')
    expect(wrapper.get('[data-testid="caliper-profile-metadata"]').text()).toContain(
      'Возраст на дату замера: 33',
    )
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it('opens just the missing profile fields without demanding folds, height or weight', async () => {
    const wrapper = show({ ...profile, birth_date: null, gender: null })
    const notice = wrapper.get('[data-testid="caliper-profile-notice"]')
    expect(notice.attributes('role')).toBe('alert')
    expect(notice.findAll('button').map((button) => button.text())).toEqual([
      'Дата рождения в профиле',
      'Пол в профиле',
    ])
    await notice.findAll('button')[0]!.trigger('click')
    expect(wrapper.getComponent(ProfileStub).props('focusField')).toBe('birth')
    await notice.findAll('button')[1]!.trigger('click')
    expect(wrapper.getComponent(ProfileStub).props('focusField')).toBe('gender')
    expect(notice.text()).not.toContain('Вес')
    expect(notice.text()).not.toContain('Рост')
  })

  it('keeps the measurement draft when profile editing opens and saves', async () => {
    const wrapper = show({ ...profile, birth_date: null })
    await wrapper.get('[data-testid="caliper-chest"]').setValue('15')
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('25')
    await wrapper.get('[data-testid="caliper-thigh"]').setValue('20')
    await wrapper.get('[data-testid="caliper-measured"]').setValue('2025-01-01T12:00')
    await wrapper.get('[data-testid="caliper-note"]').setValue('Historical draft')
    await wrapper.get('[data-testid="caliper-profile-notice"] button').trigger('click')
    const panel = wrapper.getComponent(ProfileStub)
    expect(panel.props('open')).toBe(true)
    expect(wrapper.emitted('update:open')).toBeUndefined()
    await wrapper.setProps({ profile: { ...profile, birth_date: '1990-06-15', revision: 4 } })
    panel.vm.$emit('update:open', false)
    await flushPromises()
    expect(wrapper.get('[data-testid="caliper-profile-metadata"]').text()).toContain(
      'Возраст на дату замера: 34',
    )
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-measured"]').element.value).toBe(
      '2025-01-01T12:00',
    )
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-note"]').element.value).toBe(
      'Historical draft',
    )
    expect(wrapper.find('[data-testid="caliper-profile-notice"]').exists()).toBe(false)
    expect(mocks.operate).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { chest: '15', abdomen: '25', thigh: '20' },
      measured_at: '2025-01-01T12:00:00+03:00',
      note: 'Historical draft',
    })
  })

  it('follows refreshed untouched gender and timezone defaults without changing the typed instant', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('20')
    await wrapper.setProps({
      profile: { ...profile, gender: 'female', timezone: 'America/New_York', revision: 4 },
    })
    expect(wrapper.get<HTMLSelectElement>('[data-testid="caliper-gender"]').element.value).toBe(
      'female',
    )
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-measured"]').element.value).toBe(
      '2026-10-05T02:00',
    )
    expect(wrapper.get('[data-testid="caliper-profile-metadata"]').text()).toContain('Женский')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { abdomen: '20' },
      measured_at: '2026-10-05T02:00:00-04:00',
    })
  })

  it('preserves deliberate demographics across successive profile refreshes', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="caliper-gender"]').setValue('female')
    await wrapper.get('[data-testid="caliper-age"]').setValue('28')
    await wrapper.get('[data-testid="caliper-triceps"]').setValue('21')
    await wrapper.setProps({
      profile: { ...profile, gender: 'female', birth_date: '1990-01-01', revision: 4 },
    })
    await wrapper.setProps({
      profile: { ...profile, gender: 'male', birth_date: '1990-01-01', revision: 5 },
    })
    expect(wrapper.get<HTMLSelectElement>('[data-testid="caliper-gender"]').element.value).toBe(
      'female',
    )
    expect(wrapper.get<HTMLInputElement>('[data-testid="caliper-age"]').element.value).toBe('28')
    expect(
      wrapper.get<HTMLDetailsElement>('[data-testid="caliper-metadata-overrides"]').element.open,
    ).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { triceps: '21' },
      gender: 'female',
      age_years: 28,
      measured_at: '2026-10-05T09:00:00+03:00',
    })
  })

  it('lets the server apply freshly saved profile defaults before an overview refresh arrives', async () => {
    const wrapper = show({ ...profile, gender: null, birth_date: null })
    await wrapper.get('[data-testid="caliper-chest"]').setValue('15')
    await wrapper.get('[data-testid="caliper-abdomen"]').setValue('25')
    await wrapper.get('[data-testid="caliper-thigh"]').setValue('20')
    await wrapper.get('[data-testid="caliper-profile-notice"] button').trigger('click')
    wrapper.getComponent(ProfileStub).vm.$emit('update:open', false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('caliper.add', {
      folds_mm: { chest: '15', abdomen: '25', thigh: '20' },
      measured_at: '2026-10-05T09:00:00+03:00',
    })
    expect(mocks.operate.mock.calls[0]![1]).not.toHaveProperty('gender')
    expect(mocks.operate.mock.calls[0]![1]).not.toHaveProperty('age_years')
  })
})
