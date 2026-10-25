import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import DerivedMissingInputs from './DerivedMissingInputs.vue'

describe('saved calculation missing inputs', () => {
  it('explains source thresholds and validation without changing the saved codes', () => {
    const codes = ['night_hr:four_samples', 'hrv_current:future', 'sleep_goal_minutes:invalid']
    const original = [...codes]
    const wrapper = mount(DerivedMissingInputs, { props: { codes }, global: { plugins: [i18n] } })
    const explanations = wrapper.get('[data-testid="derived-missing-explanations"]').text()
    expect(explanations).toContain('Не менее четырёх пригодных точек пульса в ночном окне')
    expect(explanations).toContain('Время входа «Текущий вход HRV» позже момента расчёта.')
    expect(explanations).toContain('Вход «Цель длительности сна» не прошёл проверку пригодности.')
    expect(explanations).not.toContain('hrv_current')
    const details = wrapper.get('[data-testid="derived-missing-codes"]')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.findAll('code').map((code) => code.text())).toEqual(original)
    expect(codes).toEqual(original)
    wrapper.unmount()
  })

  it('keeps unknown and prototype-like identifiers escaped without guessing a meaning', () => {
    const codes = [
      'future_input',
      '__proto__',
      'hrv_current:new_rule',
      '<img src=x onerror=alert(1)>',
    ]
    const wrapper = mount(DerivedMissingInputs, { props: { codes }, global: { plugins: [i18n] } })
    const explanations = wrapper.get('[data-testid="derived-missing-explanations"]')
    for (const message of explanations.findAll('li'))
      expect(message.text()).toBe('Назначение дополнительного условия ещё не описано.')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[style]').exists()).toBe(false)
    expect(wrapper.findAll('code').map((code) => code.text())).toEqual(codes)
    wrapper.unmount()
  })

  it('does not invent missing inputs when the stored list is empty', () => {
    const wrapper = mount(DerivedMissingInputs, {
      props: { codes: [] },
      global: { plugins: [i18n] },
    })
    expect(wrapper.find('[data-testid="derived-missing"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
