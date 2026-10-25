import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { russianPlural } from '@/i18n'
import ru from '@/locales/ru.json'
import { Metric } from './api'
import MetricCard from './MetricCard.vue'
import DailyResolution from './DailyResolution.vue'
import SleepAnalysisSummary from './SleepAnalysisSummary.vue'
import SleepAnalysisDetails from './SleepAnalysisDetails.vue'
import { SleepAnalysis, Resolution } from './resolutionContract'
import fixture from './sleepAnalysis.fixture.json'
import { sleepDecision } from './resolverFixtures'

vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'UTC' }, can: () => true }),
}))
const global = {
  plugins: [
    createI18n({
      legacy: false,
      locale: 'ru',
      messages: { ru },
      pluralRules: { ru: russianPlural },
    }),
  ],
  stubs: { RouterLink: RouterLinkStub },
}
const analysis = () => SleepAnalysis.parse(structuredClone(fixture))
// A chart row's name and its total.
const lane = (root: Element, name: string) =>
  [
    ...(root.querySelector(`[data-testid="sleep-lane-${name}"]`)?.querySelectorAll('span') ?? []),
  ].map((part) => part.textContent?.trim())
function unclassifiedAnalysis() {
  const value = analysis()
  Object.assign(value.summary, {
    asleep_minutes: 0,
    awake_minutes: 0,
    unknown_minutes: 480,
    classification_coverage: 0,
    forecast_asleep_minutes: null,
  })
  const episode = value.episodes[0]!
  Object.assign(episode, {
    asleep_minutes: 0,
    awake_minutes: 0,
    unknown_minutes: 480,
    classification_coverage: 0,
    bounds: { min_minutes: 0, max_minutes: 480 },
    stage_minutes: {},
    segments: [{ ...episode.segments[1]!, start_at: episode.start_at, end_at: episode.end_at }],
    forecast: {
      ...episode.forecast,
      status: 'unavailable',
      estimated_asleep_minutes: null,
      estimated_missing_minutes: null,
      training_nights: 0,
      validation: null,
      reason: 'insufficient_history',
    },
  })
  return value
}

describe('automatic sleep analysis', () => {
  it('shows evidence separately from the experimental forecast in both read views', () => {
    const resolution = Resolution.parse({
      ...sleepDecision(),
      status: 'conflict',
      sleep_analysis: analysis(),
    })
    const metric = Metric.parse({
      kind: 'sleep',
      status: 'conflict',
      value: null,
      unit: 'min',
      target: 480,
      resolution,
    })
    const card = mount(MetricCard, { props: { metric, today: '2026-10-07' }, global })
    const day = mount(DailyResolution, {
      props: {
        kind: 'sleep',
        date: '2026-10-07',
        value: null,
        unit: 'min',
        resolution,
        timezone: 'UTC',
        today: '2026-10-07',
      },
      global,
    })
    // The panel around the card already names the metric.
    expect(card.find('h2, h3').exists()).toBe(false)
    for (const view of [card, day]) {
      expect(view.text()).toContain('Распознано сна: 4 ч')
      expect(view.text()).toContain('Без классификации: 4 ч')
      expect(view.text()).toContain('Экспериментальный прогноз: около 7 ч 30 мин')
      expect(view.text()).toContain('Классифицировано 50%')
      expect(view.find('[role="progressbar"]').exists()).toBe(false)
    }
  })

  it('uses only spans in the summary embedded in the dashboard button', () => {
    const view = mount(SleepAnalysisSummary, {
      props: { analysis: analysis(), timezone: 'UTC', today: '2026-10-07', strip: true },
      global,
    })
    expect(view.find('button, details, p, div, a').exists()).toBe(false)
    // The outline of the night: known sleep, then unknown, then sleep again.
    expect(
      view.findAll('[data-testid="sleep-strip"] rect').map((part) => part.attributes('data-state')),
    ).toEqual(['light', 'deep', 'unknown'])
  })

  it('shows each received interval when no part of the report can be classified', () => {
    const value = unclassifiedAnalysis()
    const nap = structuredClone(value.episodes[0]!)
    Object.assign(nap, {
      start_at: '2026-10-07T10:00:00Z',
      end_at: '2026-10-07T10:45:00Z',
      window_minutes: 45,
      unknown_minutes: 45,
      bounds: { min_minutes: 0, max_minutes: 45 },
    })
    nap.segments = [{ ...nap.segments[0]!, start_at: nap.start_at, end_at: nap.end_at }]
    value.episodes.push(nap)
    value.summary.window_minutes = 525
    value.summary.unknown_minutes = 525
    const view = mount(SleepAnalysisSummary, {
      props: { analysis: value, timezone: 'Europe/Moscow', today: '2026-10-07' },
      global,
    })
    expect(view.text()).toContain('Длительность сна пока не определена')
    expect(view.findAll('[data-testid="sleep-interval"]').map((item) => item.text())).toEqual([
      '03:00 — 11:00',
      '13:00 — 13:45',
    ])
    expect(view.text()).toContain('Без классификации: 8 ч 45 мин')
    expect(view.text()).not.toContain('Распознано сна: 0 мин')
    expect(view.text()).not.toContain('Нет данных')
    expect(view.find('button, details, p, div, a, [style]').exists()).toBe(false)
  })

  it('uses account dates as well as times for an interval crossing local midnight', () => {
    const view = mount(SleepAnalysisSummary, {
      props: {
        analysis: unclassifiedAnalysis(),
        timezone: 'America/New_York',
        today: '2026-10-07',
      },
      global,
    })
    expect(view.get('[data-testid="sleep-interval"]').text()).toBe('Вчера, 20:00 — Сегодня, 04:00')
  })

  it('keeps an entirely unknown report distinct from zero sleep in both detail and history views', () => {
    const resolution = Resolution.parse({
      ...sleepDecision(),
      sleep_analysis: unclassifiedAnalysis(),
    })
    const metric = Metric.parse({
      kind: 'sleep',
      status: 'available',
      value: null,
      unit: 'min',
      resolution,
    })
    const card = mount(MetricCard, { props: { metric, today: '2026-10-07' }, global })
    const day = mount(DailyResolution, {
      props: {
        kind: 'sleep',
        date: '2026-10-07',
        value: null,
        unit: 'min',
        resolution,
        timezone: 'Europe/Moscow',
        today: '2026-10-07',
      },
      global,
    })
    expect(card.get('[data-testid="sleep-interval"]').text()).toBe('00:00 — 08:00')
    expect(day.get('[data-testid="sleep-interval"]').text()).toBe('03:00 — 11:00')
    for (const view of [card, day]) {
      expect(view.get('[data-testid="sleep-analysis-summary"]').text()).toContain(
        'Длительность сна пока не определена',
      )
      const details = view.get('[data-testid="sleep-analysis-details"]')
      // One row, all of it unknown: no sleep or waking row claims zero minutes.
      expect(details.findAll('[data-testid^="sleep-lane-"]')).toHaveLength(1)
      expect(lane(details.element, 'unknown')).toEqual(['Неизвестно', '8 ч 0 мин'])
      expect(details.get('svg rect[data-state="unknown"]').attributes('width')).toBe('1000')
    }
  })

  it.each([true, false])(
    'preserves zero recognized sleep when waking is known (complete=%s)',
    (complete) => {
      const value = unclassifiedAnalysis()
      const awake = complete ? 480 : 30
      Object.assign(value.summary, {
        awake_minutes: awake,
        unknown_minutes: 480 - awake,
        classification_coverage: awake / 480,
        complete_asleep_minutes: complete ? 0 : null,
      })
      const view = mount(SleepAnalysisSummary, {
        props: { analysis: value, timezone: 'UTC', today: '2026-10-07' },
        global,
      })
      expect(view.text()).toContain('0 мин')
      expect(view.text().includes('Распознано сна: 0 мин')).toBe(!complete)
      expect(view.text()).not.toContain('Длительность сна пока не определена')
      // The interval is a note under the total, not the headline it is for an unknown report.
      expect(view.get('[data-testid="sleep-interval"]').classes()).toContain(
        'text-muted-foreground',
      )
    },
  )

  it('keeps unknown intervals visible without inline styles or invented phases', () => {
    const view = mount(SleepAnalysisDetails, {
      props: { analysis: analysis(), timezone: 'UTC', today: '2026-10-07' },
      global,
    })
    // A row per state in reading order; unknown has its own row, not only a paler colour.
    expect(
      view.findAll('[data-testid^="sleep-lane-"]').map((lane) => lane.attributes('data-testid')),
    ).toEqual(['sleep-lane-light', 'sleep-lane-deep', 'sleep-lane-unknown'])
    const unknown = view.get('svg rect[data-state="unknown"]')
    expect([unknown.attributes('x'), unknown.attributes('width')]).toEqual(['250', '500'])
    expect(lane(view.element, 'unknown')).toEqual(['Неизвестно', '4 ч 0 мин'])
    expect(lane(view.element, 'light')).toEqual(['Лёгкий сон', '2 ч 0 мин'])
    // Unknown time is not counted as waking: there is no waking row to read zero from.
    expect(view.find('[data-testid="sleep-lane-awake"]').exists()).toBe(false)
    expect(view.find('[style]').exists()).toBe(false)
    expect(view.text()).toContain('Стадии не восстанавливаются')
    expect(view.text()).toContain('средняя ошибка 0 мин')
  })

  it('does not count conflict twice, and does not invent a missing forecast or bounds', () => {
    const value = analysis()
    value.summary.forecast_asleep_minutes = null
    value.summary.conflict_minutes = 30
    const episode = value.episodes[0]!
    episode.conflict_minutes = 30
    episode.segments[1]!.state = 'conflict'
    episode.bounds = null
    episode.forecast.status = 'unavailable'
    episode.forecast.reason = 'conflicting_evidence'
    episode.forecast.estimated_asleep_minutes = null
    const view = mount(SleepAnalysisDetails, {
      props: { analysis: value, timezone: 'UTC', today: '2026-10-07' },
      global,
    })
    expect(lane(view.element, 'conflict')).toEqual(['Показания расходятся', '4 ч 0 мин'])
    expect(view.find('[data-testid="sleep-lane-unknown"]').exists()).toBe(false)
    expect(view.text()).toContain('Из неизвестного времени показания расходятся: 30 мин')
    expect(view.get('svg rect[data-state="conflict"]').attributes('fill')).toMatch(/^url\(#/)
    expect(view.text()).not.toContain('В пределах отчёта:')
    expect(view.find('[data-testid="sleep-forecast"]').exists()).toBe(false)
  })

  it('lists the night figures the evidence carries and leaves out the rest', () => {
    const value = analysis()
    value.episodes[0]!.night = {
      onset_at: '2026-10-07T00:15:00Z',
      final_wake_at: '2026-10-07T08:00:00Z',
      midpoint_at: '2026-10-07T04:07:30Z',
      latency_minutes: 12,
      efficiency: 0.965,
      waso_minutes: 14,
      awakenings: 2,
      turns_per_hour: 2.04,
      heart_rate: { lowest: 49.6, mean: 55.2, readings: 66 },
      hrv: null,
    }
    const view = mount(SleepAnalysisDetails, {
      props: { analysis: value, timezone: 'UTC', today: '2026-10-07' },
      global,
    })
    const figure = (key: string) => view.get(`[data-testid="sleep-night-${key}"]`).text()
    expect(view.findAll('[data-testid="sleep-night"] dt').map((term) => term.text())).toEqual([
      'Засыпание',
      'Эффективность сна',
      'Пробуждения',
      'Повороты',
      'Пульс во сне',
      'Середина сна',
    ])
    expect(figure('latency')).toBe('12 мин')
    expect(figure('efficiency')).toBe('97%')
    expect(figure('awakenings')).toBe('2 раза · 14 мин')
    expect(figure('turns')).toBe('2 в час')
    expect(figure('heartRate')).toBe('мин. 50, средний 55 уд/мин')
    expect(figure('midpoint')).toBe('04:07')
    // An undisturbed night says so in words.
    value.episodes[0]!.night!.awakenings = 0
    value.episodes[0]!.night!.waso_minutes = 0
    const calm = mount(SleepAnalysisDetails, {
      props: { analysis: value, timezone: 'UTC', today: '2026-10-07' },
      global,
    })
    expect(calm.get('[data-testid="sleep-night-awakenings"]').text()).toBe('нет')
  })

  it('says under the chart when the stages are an estimate', () => {
    const value = analysis()
    value.episodes[0]!.flags = ['protocol_unverified', 'estimated_stages']
    const view = mount(SleepAnalysisDetails, {
      props: { analysis: value, timezone: 'UTC', today: '2026-10-07' },
      global,
    })
    expect(view.text()).toContain('Стадии — оценка по пульсу и движению браслета')
    // Only the estimate is a caption; the protocol note stays in the expandable block.
    expect(view.get('details').text()).toContain('Протокол источника пока проверен не полностью')
  })

  it('labels generic sleep and identifies the device that filled a gap', () => {
    const value = analysis()
    const segment = value.episodes[0]!.segments[1]!
    segment.state = 'sleep'
    segment.stage = null
    segment.reason = 'gap_filled'
    segment.selected_refs = [{ id: 'band', source_name: 'Браслет', contribution: null }]
    const view = mount(SleepAnalysisDetails, {
      props: { analysis: value, timezone: 'UTC', today: '2026-10-07' },
      global,
    })
    expect(lane(view.element, 'sleep')).toEqual(['Сон, стадия не определена', '4 ч 0 мин'])
    expect(view.text()).toContain('Пропуск дополнен другим источником · Браслет')
  })
})
