import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import RecordDetails from './RecordDetails.vue'

enableAutoUnmount(afterEach)

describe('record quality and provenance', () => {
  it('explains stored flags while preserving the source, original fields and unknown code', () => {
    const flags = ['stress_scale_unknown', 'future_quality']
    const wrapper = mount(RecordDetails, {
      props: {
        record: {
          source_name: 'Synthetic ring',
          start_at: '2026-10-05T10:00:00Z',
          value: '42',
          unit: 'index',
          origin: { provider: 'herzring', firmware: 'synthetic-firmware' },
          quality: flags,
        },
        timezone: 'UTC',
        today: '2026-10-05',
      },
      global: { plugins: [i18n] },
    })
    expect(wrapper.text()).toContain('Synthetic ring')
    expect(wrapper.text()).toContain('synthetic-firmware')
    const explanations = wrapper.get('[data-testid="record-quality-explanations"]')
    expect(explanations.text()).toContain('Шкала стресса устройства ещё не подтверждена.')
    expect(explanations.text()).toContain(
      'Смысл дополнительной отметки качества ещё не подтверждён',
    )
    expect(explanations.text()).not.toContain('stress_scale_unknown')
    expect(
      wrapper
        .get('[data-testid="record-quality-codes"]')
        .findAll('code')
        .map((code) => code.text()),
    ).toEqual(flags)
    const original = JSON.parse(wrapper.get('pre').text())
    expect(original.quality).toEqual(flags)
    expect(original.value).toBe('42')
    expect(original.unit).toBe('index')
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
