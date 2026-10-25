import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import ru from '@/locales/ru.json'
import DailyResolution from './DailyResolution.vue'
import { Resolution } from './resolution'
import { decision } from './resolverFixtures'

function show(input: unknown = decision(), kind = 'spo2') {
  return mount(DailyResolution, {
    props: {
      date: '2026-10-04',
      kind,
      value: null,
      unit: kind === 'temperature' ? '°C' : '%',
      resolution: Resolution.parse(input),
      timezone: 'Europe/Moscow',
      today: '2026-10-05',
    },
    global: { plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })] },
  })
}
describe('daily resolution statistics', () => {
  it('labels a reported range and separates verification from coverage', () => {
    const wrapper = show()
    expect(wrapper.text()).toContain('Минимум: 94 %')
    expect(wrapper.text()).toContain('Максимум: 99 %')
    expect(wrapper.text()).toContain('Среднее не сообщено')
    expect(wrapper.text()).not.toContain('96,5')
    expect(wrapper.text()).toContain('Это не означает отсутствие части измерений')
    expect(wrapper.text()).toContain('Сообщённые интервалы: 1 ч 0 мин')
  })
  it('shows point and duration-weighted means as distinct statistics', () => {
    const input = decision()
    input.statistics.points = {
      count: 3,
      mean: '96',
      min: '94',
      max: '99',
    } as unknown as typeof input.statistics.points
    input.statistics.interval_means = {
      count: 2,
      mean: '97',
      min: '95',
      max: '98',
      covered_seconds: 3600,
    } as unknown as typeof input.statistics.interval_means
    const wrapper = show(input)
    expect(wrapper.get('[data-testid="statistics-points"]').text()).toContain(
      'Среднее полученных измерений: 96 %',
    )
    expect(wrapper.get('[data-testid="statistics-interval-means"]').text()).toContain(
      'Среднее с весом по длительности: 97 %',
    )
    expect(wrapper.text()).toContain('Экстремумы относятся к интервальным средним')
  })
  it('preserves separate semantic groups and accepts decimal-encoded durations', () => {
    const wrapper = show(
      {
        ...decision(),
        statistics: {
          groups: [
            {
              measurement_kind: 'measured',
              sensor_location: 'skin',
              temperature_basis: 'absolute',
              points: { count: 1, mean: 33.1, min: 33.1, max: 33.1 },
            },
            {
              measurement_kind: 'measured',
              sensor_location: 'body',
              temperature_basis: 'absolute',
              interval_means: {
                count: 1,
                mean: 36.6,
                min: 36.6,
                max: 36.6,
                covered_seconds: '3600',
              },
            },
          ],
        },
      },
      'temperature',
    )
    const groups = wrapper.findAll('[data-testid="statistics-group"]')
    expect(groups).toHaveLength(2)
    expect(groups[0]!.text()).toContain('Температура кожи')
    expect(groups[1]!.text()).toContain('Температура тела')
    expect(wrapper.text()).toContain('Сообщённые интервалы: 1 ч 0 мин')
    expect(wrapper.text()).not.toContain('34,85')
  })
  it('does not describe point cadence as continuous temporal coverage', () => {
    const wrapper = show({
      ...decision(),
      coverage: { point_count: 2, intervals: [], covered_seconds: 0 },
    })
    expect(wrapper.text()).toContain('Временное покрытие неизвестно')
    expect(wrapper.get('[data-testid="resolution-explanation"]').text()).not.toContain(
      'Сообщённые интервалы: 0 мин',
    )
  })
  it('labels bounded explanations while preserving complete decision counts', () => {
    const wrapper = show({
      ...decision(),
      selected_count: 5000,
      alternatives_count: 1000,
      explanation_truncated: true,
    })
    const explanation = wrapper.get('[data-testid="resolution-explanation"]').text()
    expect(explanation).toContain('Учтённые показания: 5000')
    expect(explanation).toContain('Другие показания: 1000')
    expect(explanation).toContain('Показана часть объяснения')
    expect(explanation).toContain('Все источники')
  })
  it('keeps workout sample windows separate from point counts and continuous duration', () => {
    const wrapper = show(
      {
        ...decision(),
        coverage: { point_count: 0, intervals: [], covered_seconds: 0 },
        statistics: {
          measurement_kind: 'sample_window_mean',
          context: 'recovery',
          sample_windows: {
            count: 2,
            usable_sample_count: 10,
            sample_count: 12,
            mean: '120',
            min: '100',
            max: '140',
            mean_basis: 'usable_sample_count_weighted',
            sampled_seconds: 10,
            target_one_second_bins: 300,
            coverage_basis: 'occupied_one_second_bins',
            sample_coverage_ratio: '0.0333333333',
            gap_count: 1,
            max_inter_sample_gap_ms: 15000,
          },
        },
      },
      'heart_rate',
    )
    const block = wrapper.get('[data-testid="statistics-sample-windows"]').text()
    expect(block).toContain('Пригодных показаний: 10 из 12')
    expect(block).toContain('Среднее с весом по числу пригодных показаний')
    expect(block).toContain('Секунд с пригодными показаниями: 10')
    expect(block).toContain('не означают непрерывную работу датчика')
    expect(wrapper.text()).toContain('После тренировки')
    expect(wrapper.find('[data-testid="statistics-points"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="statistics-interval-means"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
