import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { ApiError } from '@/core/http'
import personalFormsRu from '@/locales/personal-forms-ru.json'
import ru from '@/locales/ru.json'
import { Observation } from './api'
import RecordPanel from './RecordPanel.vue'

const expectedText = {
  include: 'Вернуть в расчёты',
  exclude: 'Не учитывать в расчётах',
  reset: 'Сбросить поправку',
  valueRequired: 'Укажите значение числом.',
  timeRequired: 'Проверьте дату и время измерения.',
  conflict: 'Данные уже изменились. Ваш ввод сохранён — проверьте актуальные значения и повторите.',
}

const mocks = vi.hoisted(() => ({ operate: vi.fn(), failed: vi.fn(), writable: true }))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'Europe/Moscow' }, can: () => mocks.writable }),
}))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))

const panelStub = {
  template:
    '<section><div data-testid="panel-content"><slot /></div><footer data-testid="panel-footer"><slot name="footer" /></footer></section>',
}
function globals() {
  return {
    plugins: [
      createI18n({ legacy: false, locale: 'ru', messages: { ru: { ...ru, ...personalFormsRu } } }),
    ],
    stubs: { Panel: panelStub },
  }
}
function observation(extra: Partial<Observation> = {}) {
  return Observation.parse({
    id: 'synthetic-weight-record',
    source_id: 'synthetic-scale',
    kind: 'weight',
    start_at: '2026-10-05T06:15:00Z',
    end_at: '2026-10-05T06:15:00Z',
    timezone: 'Europe/Moscow',
    method: 'device_report',
    revision: 7,
    override_revision: 3,
    data: { value: '84.2' },
    ...extra,
  })
}
function show(record = observation(), attachTo?: Element) {
  return mount(RecordPanel, {
    props: { record, today: '2026-10-05', open: true },
    attachTo,
    global: globals(),
  })
}

enableAutoUnmount(afterEach)
beforeEach(() => {
  mocks.writable = true
  mocks.operate.mockReset().mockResolvedValue({})
  mocks.failed.mockReset()
})

describe('observation correction form', () => {
  it('shows original provenance and sleep stages without correction rights', async () => {
    mocks.writable = false
    const wrapper = show(
      observation({
        kind: 'sleep',
        source_name: 'Synthetic ring',
        data: {
          value: null,
          stages: [
            { start_at: '2026-10-05T06:15:00Z', end_at: '2026-10-05T06:30:00Z', stage: 'unknown' },
          ],
        },
      }),
    )
    await flushPromises()
    expect(wrapper.get('[data-testid="record-details"]').text()).toContain('Synthetic ring')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Стадии сна')
    expect(mocks.operate).not.toHaveBeenCalled()
  })
  it('corrects the chosen instant with its existing exclusion and captured revisions', async () => {
    const wrapper = show(
      observation({ corrected: true, excluded: true, original: { data: { value: '85.2' } } }),
    )
    await wrapper.get('[data-testid="record-value"]').setValue('83,5')
    await wrapper.get('[data-testid="record-start"]').setValue('2026-10-05T10:10')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'record.correct',
      {
        record_id: 'synthetic-weight-record',
        override_revision: 3,
        excluded: true,
        observation: {
          kind: 'weight',
          start_at: '2026-10-05T10:10:00+03:00',
          end_at: '2026-10-05T10:10:00+03:00',
          timezone: 'Europe/Moscow',
          method: 'self_report',
          data: { value: '83.5' },
        },
      },
      { 'synthetic-weight-record': 7 },
    )
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(wrapper.find('[data-testid="record-end"]').exists()).toBe(false)
    expect(wrapper.findAll('input[type="datetime-local"]')).toHaveLength(1)
  })

  it.each([false, true])(
    'changes exclusion from %s without replacing the existing correction',
    async (excluded) => {
      const wrapper = show(observation({ corrected: true, excluded }))
      const label = excluded ? expectedText.include : expectedText.exclude
      const action = wrapper.findAll('button').find((button) => button.text() === label)!
      await action.trigger('click')
      await flushPromises()
      expect(mocks.operate).toHaveBeenCalledWith(
        'record.correct',
        {
          record_id: 'synthetic-weight-record',
          override_revision: 3,
          excluded: !excluded,
          observation: {
            kind: 'weight',
            start_at: '2026-10-05T06:15:00Z',
            end_at: '2026-10-05T06:15:00Z',
            timezone: 'Europe/Moscow',
            method: 'self_report',
            data: { value: '84.2' },
          },
        },
        { 'synthetic-weight-record': 7 },
      )
    },
  )

  it('resets a correction while preserving the record exclusion', async () => {
    const wrapper = show(observation({ corrected: true, excluded: true }))
    const reset = wrapper.findAll('button').find((button) => button.text() === expectedText.reset)!
    await reset.trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'record.correct',
      {
        record_id: 'synthetic-weight-record',
        override_revision: 3,
        excluded: true,
        observation: null,
      },
      { 'synthetic-weight-record': 7 },
    )
  })

  it('keeps unknown sleep duration null instead of deriving it from the received interval', async () => {
    const wrapper = show(
      observation({
        id: 'synthetic-sleep-record',
        kind: 'sleep',
        start_at: '2026-10-04T21:00:00Z',
        end_at: '2026-10-05T05:00:00Z',
        data: { value: null },
      }),
    )
    expect(wrapper.get<HTMLInputElement>('[data-testid="record-value"]').element.value).toBe('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'record.correct',
      {
        record_id: 'synthetic-sleep-record',
        override_revision: 3,
        excluded: false,
        observation: {
          kind: 'sleep',
          start_at: '2026-10-05T00:00:00+03:00',
          end_at: '2026-10-05T08:00:00+03:00',
          timezone: 'Europe/Moscow',
          method: 'self_report',
          data: { value: null },
        },
      },
      { 'synthetic-sleep-record': 7 },
    )
  })

  it.each(['', 'не число'])(
    'rejects an invalid weight %s and focuses its described error',
    async (value) => {
      const wrapper = show(observation(), document.body)
      const field = wrapper.get<HTMLInputElement>('[data-testid="record-value"]')
      await field.setValue(value)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(field.attributes('aria-invalid')).toBe('true')
      const errorId = field.attributes('aria-describedby')!
      expect(wrapper.get(`[id="${errorId}"]`).text()).toBe(expectedText.valueRequired)
      expect(document.activeElement).toBe(field.element)
      expect(mocks.operate).not.toHaveBeenCalled()
    },
  )

  it('rejects a missing instant and focuses its time field without issuing a correction', async () => {
    const wrapper = show(observation(), document.body)
    const field = wrapper.get<HTMLInputElement>('[data-testid="record-start"]')
    await field.setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(field.attributes('aria-invalid')).toBe('true')
    const descriptions = field.attributes('aria-describedby')!.split(' ')
    expect(descriptions.map((id) => wrapper.get(`[id="${id}"]`).text())).toContain(
      expectedText.timeRequired,
    )
    expect(document.activeElement).toBe(field.element)
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it('stacks interval fields on mobile and focuses an end before its start', async () => {
    const wrapper = show(
      observation({
        kind: 'sleep',
        start_at: '2026-10-04T21:00:00Z',
        end_at: '2026-10-05T05:00:00Z',
        data: { value: '420' },
      }),
      document.body,
    )
    const times = wrapper.get('[data-testid="record-times"]')
    expect(times.classes()).toContain('grid-cols-1')
    expect(times.classes()).not.toContain('grid-cols-2')
    const end = wrapper.get<HTMLInputElement>('[data-testid="record-end"]')
    await end.setValue('2026-10-04T23:00')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(end.attributes('aria-invalid')).toBe('true')
    const descriptions = end.attributes('aria-describedby')!.split(' ')
    expect(descriptions.map((id) => wrapper.get(`[id="${id}"]`).text())).toContain(
      expectedText.timeRequired,
    )
    expect(document.activeElement).toBe(end.element)
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it('associates the footer save with its form and blocks repeated writes while saving', async () => {
    let complete!: (value: unknown) => void
    const pending = new Promise((resolve) => {
      complete = resolve
    })
    mocks.operate.mockReturnValue(pending)
    const wrapper = show()
    const form = wrapper.get('form')
    const submit = wrapper.get<HTMLButtonElement>(
      '[data-testid="panel-footer"] button[type="submit"]',
    )
    expect(form.attributes('id')).toBeTruthy()
    expect(submit.attributes('form')).toBe(form.attributes('id'))
    expect(form.find('button[type="submit"]').exists()).toBe(false)
    await form.trigger('submit')
    expect(submit.element.disabled).toBe(true)
    expect(wrapper.get<HTMLFieldSetElement>('fieldset').element.disabled).toBe(true)
    expect(submit.text()).toBe('Сохраняю…')
    expect(
      wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
    await form.trigger('submit')
    expect(mocks.operate).toHaveBeenCalledTimes(1)
    complete({})
    await flushPromises()
    expect(submit.element.disabled).toBe(false)
    expect(wrapper.get<HTMLFieldSetElement>('fieldset').element.disabled).toBe(false)
  })

  it('keeps the typed correction and captured revisions after refusal and a same-record refresh', async () => {
    const error = new ApiError(409, null, null, null, null)
    mocks.operate.mockRejectedValue(error)
    const record = observation({ excluded: true })
    const wrapper = show(record)
    const value = wrapper.get<HTMLInputElement>('[data-testid="record-value"]')
    const start = wrapper.get<HTMLInputElement>('[data-testid="record-start"]')
    await value.setValue('83,5')
    await start.setValue('2026-10-05T10:10')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const failure = wrapper.get('[data-testid="record-server-error"]')
    expect(failure.get('[role="alert"]').text()).toBe(expectedText.conflict)
    expect(failure.text()).toContain('Введённые значения остались в форме.')
    expect(mocks.failed).toHaveBeenCalledWith(error)
    expect(wrapper.emitted('update:open')).toBeUndefined()
    const refused = mocks.operate.mock.calls[0]
    await wrapper.setProps({
      record: observation({ revision: 8, override_revision: 4, data: { value: '90' } }),
    })
    expect(value.element.value).toBe('83,5')
    expect(start.element.value).toBe('2026-10-05T10:10')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledTimes(2)
    expect(mocks.operate.mock.calls[1]).toEqual(refused)
    expect(mocks.operate.mock.calls[1]![1]).toMatchObject({
      excluded: true,
      override_revision: 3,
    })
    expect(mocks.operate.mock.calls[1]![2]).toEqual({ 'synthetic-weight-record': 7 })
  })

  it('loads a newly chosen record instead of reusing another record draft or revisions', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="record-value"]').setValue('83,5')
    await wrapper.setProps({
      record: observation({
        id: 'another-synthetic-record',
        revision: 11,
        override_revision: 5,
        data: { value: '75.1' },
      }),
    })
    expect(wrapper.get<HTMLInputElement>('[data-testid="record-value"]').element.value).toBe('75,1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate.mock.calls[0]![1]).toMatchObject({
      record_id: 'another-synthetic-record',
      override_revision: 5,
      observation: { data: { value: '75.1' } },
    })
    expect(mocks.operate.mock.calls[0]![2]).toEqual({ 'another-synthetic-record': 11 })
  })

  it('uses unique field and form ids for simultaneous correction panels', () => {
    const props = { record: observation(), today: '2026-10-05', open: true }
    const wrapper = mount(
      defineComponent({
        setup: () => () => h('div', [h(RecordPanel, props), h(RecordPanel, props)]),
      }),
      { global: globals() },
    )
    const ids = wrapper.findAll('[id]').map((element) => element.attributes('id'))
    expect(ids).toHaveLength(new Set(ids).size)
    for (const panel of wrapper.findAllComponents(RecordPanel)) {
      for (const field of panel.findAll('input'))
        expect(panel.find(`label[for="${field.attributes('id')}"]`).exists()).toBe(true)
      const form = panel.get('form')
      expect(panel.get('button[type="submit"]').attributes('form')).toBe(form.attributes('id'))
    }
  })
})
