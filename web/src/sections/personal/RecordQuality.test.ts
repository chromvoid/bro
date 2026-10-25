import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import RecordQuality from './RecordQuality.vue'

enableAutoUnmount(afterEach)
const show = (flags?: string[] | null) =>
  mount(RecordQuality, { props: { flags }, global: { plugins: [i18n] } })

describe('record quality explanations', () => {
  it('keeps confirmed protocol separate from measurement accuracy and unqualified semantics', () => {
    const flags = ['protocol_verified', 'hrv_statistic_unknown', 'stress_scale_unknown']
    const wrapper = show(flags)
    const explanations = wrapper.get('[data-testid="record-quality-explanations"]')
    expect(explanations.text()).toContain(
      'Формат данных источника подтверждён; точность измерения оценивается отдельно.',
    )
    expect(explanations.text()).toContain('Определение показателя HRV')
    expect(explanations.text()).toContain('Шкала стресса устройства ещё не подтверждена.')
    expect(explanations.text()).not.toContain('protocol_verified')
    expect(explanations.text()).not.toContain('RMSSD')
    expect(explanations.text()).not.toContain('SDNN')
    const codes = wrapper.get('[data-testid="record-quality-codes"]')
    expect(codes.attributes('open')).toBeUndefined()
    expect(codes.findAll('code').map((code) => code.text())).toEqual(flags)
  })

  it('keeps unknown and prototype-like codes readable as escaped detail without guessing meaning', () => {
    const flags = ['future_quality', '__proto__', 'constructor', '<img src=x onerror=alert(1)>']
    const wrapper = show(flags)
    const explanations = wrapper.get('[data-testid="record-quality-explanations"]')
    expect(explanations.findAll('li')).toHaveLength(flags.length)
    for (const explanation of explanations.findAll('li')) {
      expect(explanation.text()).toContain(
        'Смысл дополнительной отметки качества ещё не подтверждён',
      )
      expect(explanation.text()).not.toContain('подтверждён; точность')
    }
    expect(wrapper.find('img').exists()).toBe(false)
    expect(
      wrapper
        .get('[data-testid="record-quality-codes"]')
        .findAll('code')
        .map((code) => code.text()),
    ).toEqual(flags)
    expect(wrapper.find('[style]').exists()).toBe(false)
  })

  it('separates incomplete coverage, unverified format and invalid values', () => {
    const wrapper = show(['partial_day', 'protocol_unverified', 'invalid'])
    const explanations = wrapper.get('[data-testid="record-quality-explanations"]').text()
    expect(explanations).toContain('Данные охватывают часть суток.')
    expect(explanations).toContain('Формат данных этого источника ещё не сверен.')
    expect(explanations).toContain('Показание не прошло проверку пригодности.')
    expect(explanations).not.toContain('Формат данных источника подтверждён')
  })

  it('does not turn an incomplete recording into proof of missing sensor coverage', () => {
    const wrapper = show(['incomplete', 'identity_collision'])
    const explanations = wrapper.get('[data-testid="record-quality-explanations"]').text()
    expect(explanations).toContain('Запись помечена как неполная.')
    expect(explanations).toContain('По одному идентификатору получены расходящиеся данные')
    expect(explanations).not.toContain('Покрытие данных неполное')
    expect(explanations).not.toContain('Смысл дополнительной отметки')
  })

  it.each([{ flags: undefined }, { flags: null }, { flags: [] }])(
    'does not invent quality when no flags are supplied: $flags',
    ({ flags }) => {
      const wrapper = show(flags)
      expect(wrapper.find('[data-testid="record-quality"]').exists()).toBe(false)
    },
  )
})
