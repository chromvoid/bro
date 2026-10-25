import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { Metric, Observation, SourceMetric, type BodyKind } from './api'
import BodyComposition from './BodyComposition.vue'

function metric(kind: BodyKind, value: string, start: string) {
  return Metric.parse({
    kind,
    value,
    unit: kind === 'body_fat_percent' ? '%' : 'kg',
    status: 'available',
    records: [
      Observation.parse({
        id: `synthetic-${kind}`,
        kind,
        start_at: start,
        end_at: start,
        timezone: 'UTC',
        method: 'device_report',
        source_name: 'Тестовый анализатор',
        data: { value },
        revision: 1,
      }),
    ],
  })
}

function show(metrics: Partial<Record<BodyKind, Metric>>) {
  return mount(BodyComposition, {
    props: { metrics, today: '2026-09-23', timezone: 'Europe/Moscow' },
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

describe('body composition', () => {
  it('shows only reported values, with each measurement date and source', () => {
    const wrapper = show({
      body_fat_percent: metric('body_fat_percent', '22.7', '2026-09-23T06:00:00Z'),
      body_minerals: metric('body_minerals', '3.43', '2026-09-20T07:30:00Z'),
      body_water: Metric.parse({ kind: 'body_water', value: null, unit: 'l', status: 'no_data' }),
    })
    const fat = wrapper.get('[data-testid="metric-body_fat_percent"]')
    expect(fat.text()).toContain('22,7 %')
    expect(fat.text()).toContain('Сегодня, 09:00')
    expect(fat.text()).toContain('Тестовый анализатор · Данные устройства')
    const minerals = wrapper.get('[data-testid="metric-body_minerals"]')
    expect(minerals.text()).toContain('3,43 кг')
    expect(minerals.text()).toContain('20 сентября, 10:30')
    expect(wrapper.find('[data-testid="metric-body_water"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Цель')
    expect(fat.getComponent(RouterLinkStub).props('to')).toEqual({
      name: 'personal-history',
      query: {
        kind: 'body_fat_percent',
        end: '2026-09-23',
        returnDay: '2026-09-23',
        returnSection: 'body',
        returnKey: expect.any(String),
      },
    })
  })

  it('does not create an empty section before any body data is available', () => {
    expect(show({}).find('section').exists()).toBe(false)
  })

  it('states a conflicting decision without its value and without a choice', () => {
    const conflict = metric('body_fat_percent', '22.7', '2026-09-23T06:00:00Z')
    conflict.status = 'conflict'
    conflict.value = null
    const wrapper = show({ body_fat_percent: conflict })
    expect(wrapper.text()).toContain('Показания не согласованы')
    expect(wrapper.text()).not.toContain('22,7 %')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('shows InBody and caliper estimates separately even when the combined value selects one', () => {
    const inbody = metric('body_fat_percent', '22.75', '2026-09-23T06:00:00Z')
    const caliper = metric('body_fat_percent', '18.515298', '2026-09-22T06:00:00Z')
    caliper.records![0]!.method = 'derived'
    const wrapper = mount(BodyComposition, {
      props: {
        metrics: { body_fat_percent: caliper },
        fatSources: [
          SourceMetric.parse({ ...inbody, source_id: 'inbody', source_name: 'DDX InBody' }),
          SourceMetric.parse({ ...caliper, source_id: 'caliper', source_name: 'Калипер' }),
        ],
        today: '2026-09-23',
        timezone: 'Europe/Moscow',
      },
      global: {
        plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    const sources = wrapper.get('[data-testid="body-fat-sources"]')
    expect(sources.get('[data-source-id="inbody"]').text()).toContain('DDX InBody22,8 %')
    expect(sources.get('[data-source-id="inbody"]').text()).toContain('Данные устройства')
    expect(sources.get('[data-source-id="caliper"]').text()).toContain('Калипер18,5 %')
    expect(sources.get('[data-source-id="caliper"]').text()).toContain('Оценка')
  })
})
