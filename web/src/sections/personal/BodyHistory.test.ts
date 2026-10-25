import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { History, Profile } from './api'
import BodyHistory from './BodyHistory.vue'

const history = History.parse({
  from: '2026-01-01',
  to: '2026-09-30',
  daily: [],
  records: [
    {
      id: 'fat-old',
      source_id: 'source-a',
      source_name: 'Тестовый анализатор',
      kind: 'body_fat_percent',
      start_at: '2026-08-01T09:00:00Z',
      end_at: '2026-08-01T09:00:00Z',
      timezone: 'UTC',
      method: 'device_report',
      origin: { record_id: 'synthetic-old' },
      data: { value: '23.4' },
      revision: 1,
    },
    {
      id: 'fat-new',
      source_id: 'source-a',
      source_name: 'Тестовый анализатор',
      kind: 'body_fat_percent',
      start_at: '2026-09-20T09:00:00Z',
      end_at: '2026-09-20T09:00:00Z',
      timezone: 'UTC',
      method: 'device_report',
      origin: { record_id: 'synthetic-new' },
      data: { value: '22.7' },
      revision: 1,
    },
  ],
  sources: [
    {
      id: 'source-a',
      name: 'Тестовый анализатор',
      kinds: ['body_fat_percent'],
      status: 'connected',
      revision: 1,
      error: 'history_restricted',
    },
  ],
})

const profile = Profile.parse({
  user_id: 'owner',
  timezone: 'UTC',
  height_cm: '180',
  gender: 'female',
  gender_origin: 'manual',
  revision: 1,
})
function show(data = history, canWrite = false) {
  return mount(BodyHistory, {
    props: { history: data, canWrite, profile, today: '2026-09-30', timezone: 'Europe/Moscow' },
    global: {
      plugins: [
        createPinia(),
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        [VueQueryPlugin, { queryClient: new QueryClient() }],
      ],
      stubs: { BodyFigure: true },
    },
  })
}

describe('body history presentation', () => {
  it('shows scan dates, dated neutral comparisons and expandable details to read-only users', () => {
    const wrapper = show()
    expect(wrapper.text()).toContain('Полная история DDX недоступна')
    const latest = wrapper.findAll('[data-testid="body-analysis"]')[0]!
    expect(latest.text()).toContain('20 сентября, 12:00')
    expect(latest.text()).toContain('−0,7 п. п.')
    expect(latest.text()).toContain('к измерению 1 августа, 12:00')
    expect(latest.get('summary').text()).toContain('Все показатели')
    expect(latest.find('button').exists()).toBe(false)
    expect(latest.text()).toContain('значение не передано')
    expect(wrapper.get('svg[role="img"]').attributes('aria-label')).toContain('Доля жира')
  })

  it('opens the exact measurement for correction when permitted', async () => {
    const wrapper = show(history, true)
    const latest = wrapper.findAll('[data-testid="body-analysis"]')[0]!
    const details = latest.get<HTMLDetailsElement>('details')
    expect(details.find('ul').exists()).toBe(false)
    details.element.open = true
    await details.trigger('toggle')
    await latest.get('button').trigger('click')
    expect(wrapper.emitted('edit')?.[0]?.[0]).toMatchObject({ id: 'fat-new' })
    details.element.open = false
    await details.trigger('toggle')
    expect(details.find('ul').exists()).toBe(false)
  })

  it('bounds the mounted cards while keeping every scan selectable and older pages accessible', async () => {
    const records = Array.from({ length: 31 }, (_, index) => ({
      ...history.records[1]!,
      id: `scan-${index}`,
      origin: { record_id: `scan-${index}` },
      start_at: new Date(Date.UTC(2026, 8, index + 1, 9)).toISOString(),
      end_at: new Date(Date.UTC(2026, 8, index + 1, 9)).toISOString(),
    }))
    const wrapper = show({ ...history, records })
    const cards = () => wrapper.findAll('[data-testid="body-analysis"]')
    const nav = wrapper.get('nav')
    expect(cards()).toHaveLength(12)
    expect(wrapper.get('#body-analysis-select').findAll('option')).toHaveLength(31)
    expect(cards()[0]!.get('time').attributes('datetime')).toBe(records[30]!.start_at)
    expect(nav.get('[role="status"]').text()).toBe('1 из 3')
    expect(nav.findAll('button')[0]!.attributes('disabled')).toBeDefined()
    await nav.findAll('button')[1]!.trigger('click')
    expect(cards()).toHaveLength(12)
    expect(cards()[0]!.get('time').attributes('datetime')).toBe(records[18]!.start_at)
    await nav.findAll('button')[1]!.trigger('click')
    expect(cards()).toHaveLength(7)
    expect(cards().at(-1)!.get('time').attributes('datetime')).toBe(records[0]!.start_at)
    expect(nav.get('[role="status"]').text()).toBe('3 из 3')
    expect(nav.findAll('button')[1]!.attributes('disabled')).toBeDefined()
    // The figure's selection is independent of the paged list.
    await wrapper.get('#body-analysis-select').setValue(JSON.stringify(['source-a', 'scan-0']))
    expect(nav.get('[role="status"]').text()).toBe('3 из 3')
    expect(wrapper.get('[data-testid="body-atlas"] time').attributes('datetime')).toBe(
      records[0]!.start_at,
    )
    await wrapper.setProps({ history: { ...history, records: [...records] } })
    expect(nav.get('[role="status"]').text()).toBe('3 из 3')
    await wrapper.setProps({ history: { ...history, records, from: '2026-09-01' } })
    expect(nav.get('[role="status"]').text()).toBe('1 из 3')
    await nav.findAll('button')[1]!.trigger('click')
    await wrapper.setProps({ history })
    expect(cards()).toHaveLength(2)
    expect(wrapper.find('nav').exists()).toBe(false)
  })

  it('keeps source restrictions visible even when no scan falls inside the period', () => {
    const wrapper = show({ ...history, records: [] })
    expect(wrapper.text()).toContain('Полная история DDX недоступна')
    expect(wrapper.text()).toContain('За этот период анализов состава тела нет')
    expect(wrapper.text()).toContain('перейдите к более ранним датам')
    expect(wrapper.find('[data-testid="body-analysis"]').exists()).toBe(false)
    expect(wrapper.find('svg[role="img"]').exists()).toBe(false)
  })
  it('switches exact same-day scans and keeps different sources separate', async () => {
    const source = {
      id: 'snapshot-a',
      source_id: 'source-a',
      source_name: 'First DDX',
      provider_record_id: 'synthetic-new',
      measured_at: '2026-09-20T09:00:00Z',
      received_at: '2026-10-01T09:00:00Z',
      revision: 1,
    }
    const data = History.parse({
      ...history,
      source_analyses: [
        source,
        {
          ...source,
          id: 'snapshot-raw',
          provider_record_id: 'raw-only',
          measured_at: '2026-09-20T18:00:00Z',
        },
        { ...source, id: 'snapshot-other', source_id: 'source-b', source_name: 'Second DDX' },
      ],
    })
    const wrapper = show(data)
    expect(wrapper.get('[data-testid="body-atlas"]').text()).toContain('значение не передано')
    expect(wrapper.get('#body-analysis-select').findAll('option')).toHaveLength(3)
    await wrapper
      .get('#body-analysis-select')
      .setValue(JSON.stringify(['source-a', 'synthetic-new']))
    expect(wrapper.get('[data-testid="body-atlas"]').text()).toContain('22,7 %')
    await wrapper.get('#body-analysis-source').setValue('source-b')
    expect(wrapper.get('[data-testid="body-atlas"]').text()).toContain('Second DDX')
    expect(wrapper.get('[data-testid="body-atlas"]').text()).not.toContain('22,7 %')
    expect(wrapper.get('#body-analysis-select').findAll('option')).toHaveLength(1)
  })

  it('does not advertise a DDX archive for an unrelated measurement source', () => {
    expect(show().find('[data-testid="body-source-details"]').exists()).toBe(false)
  })
  it('passes the profile, resets comparison on source changes and forwards explicit profile edits', async () => {
    const other = history.records.map((record) => ({
      ...record,
      id: record.id + '-b',
      source_id: 'source-b',
      source_name: 'Other source',
    }))
    const wrapper = show({ ...history, records: [...history.records, ...other] }, true)
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    expect(figure.props('visual').template).toBe('female')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сравнить анализы')!
      .trigger('click')
    expect(wrapper.find('#body-compare-progress').exists()).toBe(true)
    const select = wrapper.get<HTMLSelectElement>('#body-analysis-source')
    await select.setValue(select.element.value === 'source-a' ? 'source-b' : 'source-a')
    expect(wrapper.find('#body-compare-progress').exists()).toBe(false)
    await wrapper.setProps({
      profile: { ...profile, gender: null, gender_origin: 'unset', height_cm: null },
    })
    expect(figure.props('visual').template).toBe('male')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Указать гендер')!
      .trigger('click')
    expect(wrapper.emitted('profile')).toEqual([[]])
  })
})
