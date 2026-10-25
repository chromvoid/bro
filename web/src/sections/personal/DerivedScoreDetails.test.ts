import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import DerivedScoreDetails from './DerivedScoreDetails.vue'
import { DerivedResultVersion, DerivedScore } from './derivedScores'
import {
  producerCompactHomeArchiveItem,
  producerReferences,
  producerRhrScore,
  producerWindowScore,
} from './derivedScores.fixtures'

describe('captured calculation inputs', () => {
  it('displays booleans and structured series separately from scalars and synthetic defaults', () => {
    const score = DerivedScore.parse({
      ...producerRhrScore(),
      components: [
        { id: 'hr_7d', value: [55, 60], unit: 'bpm', quality: [], synthetic: false },
        { id: 'female', value: false, unit: '', quality: [], synthetic: false },
        {
          id: 'sleep_segments',
          value: [
            { stage: 'rem', minutes: 20 },
            { stage: 'awake', minutes: 2 },
          ],
          unit: 'min',
          quality: [],
          synthetic: false,
        },
        {
          id: 'breath_reference',
          value: 16,
          unit: '',
          synthetic: true,
          reason: 'soul_reference_default',
        },
      ],
      input_revisions: producerReferences(),
    })
    const wrapper = mount(DerivedScoreDetails, {
      props: { score, timezone: 'UTC', today: '2026-10-09' },
      global: { plugins: [i18n] },
    })
    const components = wrapper.findAll('[data-testid="derived-component"]')
    expect(components[0]!.text()).toContain('Числовой ряд · 2 значений')
    expect(components[0]!.get('pre').text()).toContain('55')
    expect(components[1]!.text()).toContain('Женский пол в профиле: Нет')
    expect(components[1]!.get('code').text()).toBe('female')
    expect(components[2]!.text()).toContain('Стадии сна · 2 интервалов')
    expect(components[2]!.get('pre').text()).toContain('"stage": "rem"')
    expect(components[3]!.text()).toContain('Справочное значение формулы Soul; это не измерение.')
    expect(components[3]!.text()).toContain('soul_reference_default')
    expect(wrapper.text()).toContain('Профиль владельца · profile')
    expect(wrapper.text()).toContain('Контекст программного алгоритма')
    expect(wrapper.text()).toContain('Идентичность источника')
    expect(wrapper.find('[data-testid="derived-input-windows"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Окна измерений')
    expect(wrapper.text()).not.toContain('NaN')
    wrapper.unmount()
  })
  it('displays measurement windows with their metric, count, interval and saved evidence', () => {
    const score = DerivedScore.parse(producerWindowScore())
    const wrapper = mount(DerivedScoreDetails, {
      props: { score, timezone: 'UTC', today: '2026-10-09' },
      global: { plugins: [i18n] },
    })
    const windows = wrapper.get('[data-testid="derived-input-windows"]')
    expect(windows.get('summary').text()).toBe('Окна измерений')
    expect(windows.findAll('li').map((window) => window.text())).toEqual([
      'heart_rate · 3 показаний · 2026-10-02T09:00:00Z — 2026-10-09T09:00:00Z',
      'hrv · 1 показаний · 2026-10-02T09:00:00Z — 2026-10-09T09:00:00Z',
    ])
    expect(JSON.parse(windows.get('pre').text())).toEqual(score.input_windows)
    expect(
      windows.element.previousElementSibling?.querySelector('summary')?.textContent?.trim(),
    ).toBe('Версии исходных записей и контекста')
    wrapper.unmount()
  })
  it('renders measurement windows and compact night baselines from a real Home archive', () => {
    const archive = producerCompactHomeArchiveItem()
    const captured = JSON.stringify(archive)
    const result = DerivedResultVersion.parse(archive)
    const wrapper = mount(DerivedScoreDetails, {
      props: {
        score: result.score,
        definition: result.definition_snapshot,
        timezone: result.time_zone,
        today: result.date,
      },
      global: { plugins: [i18n] },
    })
    const windows = wrapper.get('[data-testid="derived-input-windows"]')
    expect(windows.get('summary').text()).toBe('Окна измерений')
    expect(windows.findAll('li')).toHaveLength(result.score.input_windows.length)
    expect(JSON.parse(windows.get('pre').text())).toEqual(archive.score.input_windows)
    const renderedBaselines = wrapper.get('[data-testid="derived-baseline"]').findAll('li')
    for (const [id, count] of [
      ['night_hr', 30],
      ['night_hrv', 4],
    ] as const) {
      const baseline = result.score.baseline!.components.find((component) => component.id === id)
      expect(baseline).toMatchObject({
        input_count: count,
        input_digest: expect.stringMatching(/^[0-9a-f]{64}$/),
      })
      expect(baseline).not.toHaveProperty('input_revisions')
      const rendered = renderedBaselines.find((component) => component.get('p').text() === id)
      expect(rendered).toBeDefined()
      expect(JSON.parse(rendered!.get('pre').text())).toEqual(baseline)
    }
    expect(JSON.stringify(archive)).toBe(captured)
    wrapper.unmount()
  })
  it('keeps component quality and age references with immutable evidence', async () => {
    const score = DerivedScore.parse({
      ...producerRhrScore(),
      status: 'partial',
      quality: ['protocol_unverified', 'future_quality'],
      components: [
        {
          id: 'ide_hrv_14d',
          value: null,
          unit: 'ms',
          synthetic: false,
          quality: ['hrv_statistic_unknown'],
        },
        {
          id: 'ide_hrv_14d',
          value: 40,
          unit: 'ms',
          synthetic: true,
          reason: 'soul_age_reference',
        },
        {
          id: '<img src=x onerror=alert(1)>',
          value: false,
          unit: '',
          synthetic: false,
          quality: ['future_component_quality'],
        },
      ],
      input_revisions: producerReferences(),
    })
    const captured = JSON.stringify(score)
    const wrapper = mount(DerivedScoreDetails, {
      props: { score, timezone: 'UTC', today: '2026-10-09' },
      global: { plugins: [i18n] },
    })
    const components = wrapper.findAll('[data-testid="derived-component"]')
    expect(components).toHaveLength(3)
    expect(components[0]!.text()).toContain('База HRV за 14 дней для ИДЭ: Нет данных')
    expect(components[0]!.get('[data-testid="record-quality-explanations"]').text()).toContain(
      'Определение показателя HRV',
    )
    expect(components[1]!.text()).toContain('Возрастная опора формулы Soul; это не измерение.')
    expect(components[1]!.find('[data-testid="record-quality"]').exists()).toBe(false)
    expect(components[1]!.findAll('code').map((code) => code.text())).toEqual([
      'ide_hrv_14d',
      'soul_age_reference',
    ])
    expect(components[2]!.text()).toContain('Дополнительный вход или компонент расчёта: Нет')
    expect(components[2]!.get('[data-testid="derived-component-codes"] code').text()).toBe(
      '<img src=x onerror=alert(1)>',
    )
    expect(wrapper.find('img').exists()).toBe(false)
    const explanations = wrapper.findAll('[data-testid="record-quality-explanations"]')
    expect(explanations.at(-1)!.text()).toContain('Формат данных этого источника ещё не сверен.')
    expect(explanations.at(-1)!.text()).toContain('Смысл дополнительной отметки качества')
    expect(wrapper.text()).toContain('future_quality')
    expect(wrapper.text()).toContain('future_component_quality')
    expect(wrapper.text()).toContain('Контекст программного алгоритма')
    expect(JSON.stringify(score)).toBe(captured)
    expect(wrapper.find('[style]').exists()).toBe(false)
    await wrapper.setProps({
      score: DerivedScore.parse({
        ...score,
        components: [{ id: 'ide_hrv_14d', value: 35, unit: 'ms', quality: [], synthetic: false }],
      }),
    })
    const refreshed = wrapper.findAll('[data-testid="derived-component"]')
    expect(refreshed).toHaveLength(1)
    expect(refreshed[0]!.text()).toContain('База HRV за 14 дней для ИДЭ: 35 ms')
    expect(refreshed[0]!.text()).not.toContain('Синтетический компонент')
    expect(wrapper.text()).not.toContain('soul_age_reference')
    expect(JSON.stringify(score)).toBe(captured)
    wrapper.unmount()
  })
})
