import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import { History } from '@/sections/personal/api'
import MovementDaySummary from './MovementDaySummary.vue'

const TODAY = '2026-10-06'
function screen(
  daily: unknown[],
  options: { date?: string; kind?: 'steps' | 'active_minutes' } = {},
) {
  return mount(MovementDaySummary, {
    props: {
      history: History.parse({ from: '2026-10-01', to: TODAY, records: [], daily }),
      kind: options.kind ?? 'steps',
      date: options.date ?? TODAY,
      today: TODAY,
      timezone: 'Europe/Moscow',
    },
    global: { plugins: [i18n] },
  })
}
const row = (date: string, value: string | number | null, status = 'available') => ({
  date,
  kind: 'steps',
  value,
  status,
})
const resolution = (status: string) => ({
  status,
  fingerprint: 'synthetic-inputs',
  resolver_version: 'personal-resolution-v1',
})

describe('selected movement day summary', () => {
  it('labels the exact selected day with its amount and an absolute calendar date', () => {
    const wrapper = screen([row('2026-10-05', '700'), row(TODAY, '2400')])
    expect(wrapper.get('[data-testid="movement-day-date"]').text()).toContain('Сегодня')
    expect(wrapper.get('[data-testid="movement-day-date"]').text()).toContain('6 октября 2026')
    expect(wrapper.get('[data-testid="movement-day-value"]').text().replaceAll('\u00a0', ' ')).toBe(
      '2 400 шагов',
    )
  })

  it('keeps an absent selected day separate from the latest earlier amount', () => {
    const wrapper = screen([row('2026-10-05', '700')])
    expect(wrapper.find('[data-testid="movement-day-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="movement-day-status"]').text()).toBe('Нет данных')
    expect(wrapper.text()).not.toContain('700')
  })

  it('preserves measured zero and supports older history without resolution metadata', () => {
    const wrapper = screen([row(TODAY, 0)])
    expect(wrapper.get('[data-testid="movement-day-value"]').text()).toBe('0 шагов')
    expect(wrapper.find('[data-testid="movement-day-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="resolution-explanation"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Измерено:')
    expect(wrapper.text()).not.toContain('Формат протокола')
  })

  it.each([null, ''])('does not turn an unknown amount %s into zero', (value) => {
    const wrapper = screen([row(TODAY, value)])
    expect(wrapper.find('[data-testid="movement-day-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="movement-day-status"]').attributes('data-status')).toBe(
      'no_data',
    )
  })

  it('shows a conflict instead of presenting its nonnull amount as the daily result', () => {
    const wrapper = screen([
      { ...row(TODAY, '900', 'conflict'), resolution: resolution('conflict') },
    ])
    expect(wrapper.find('[data-testid="movement-day-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="movement-day-status"]').text()).toBe(
      'Показания не согласованы',
    )
    expect(wrapper.get('[data-testid="resolution-explanation"]').text()).toContain(
      'Как получен результат',
    )
  })

  it('shows source unavailability explicitly', () => {
    const wrapper = screen([row(TODAY, null, 'unavailable')])
    expect(wrapper.get('[data-testid="movement-day-status"]').text()).toBe('Источник недоступен')
  })

  it('changes the summary with the selected end day and does not mix metrics', async () => {
    const wrapper = screen(
      [
        row(TODAY, '900'),
        { ...row(TODAY, '35'), kind: 'active_minutes' },
        { ...row('2026-10-05', '20'), kind: 'active_minutes' },
      ],
      { kind: 'active_minutes' },
    )
    expect(wrapper.get('[data-testid="movement-day-value"]').text()).toBe('35 мин')
    await wrapper.setProps({ date: '2026-10-05' })
    expect(wrapper.get('[data-testid="movement-day-date"]').text()).toContain('Вчера')
    expect(wrapper.get('[data-testid="movement-day-date"]').text()).toContain('5 октября 2026')
    expect(wrapper.get('[data-testid="movement-day-value"]').text()).toBe('20 мин')
  })

  it('uses supplied measurement time in the account zone without assuming completeness', async () => {
    const wrapper = screen([
      {
        ...row(TODAY, '300'),
        resolution: {
          ...resolution('partial'),
          selected: [{ id: 'synthetic-fact', source_name: 'Example ring', contribution: '300' }],
          protocol: { verified: false, flags: ['protocol_unverified'] },
          freshness: { measured_at: '2026-10-07T02:30:00Z' },
        },
      },
    ])
    await wrapper.setProps({ timezone: 'America/Los_Angeles' })
    expect(wrapper.get('[data-testid="movement-day-value"]').text()).toBe('300 шагов')
    expect(wrapper.text()).toContain('Измерено: сегодня, 19:30')
    expect(wrapper.text()).toContain('Формат протокола ещё не подтверждён')
    expect(wrapper.get('[data-testid="resolution-explanation"]').text()).toContain(
      'Временное покрытие неизвестно',
    )
  })
})
