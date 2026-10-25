import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { Metric, Observation } from './api'
import DailyResolution from './DailyResolution.vue'
import MetricCard from './MetricCard.vue'
import RecordPanel from './RecordPanel.vue'
import { sleepDecision } from './resolverFixtures'
import { Resolution } from './resolutionContract'

vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'Europe/Moscow' }, can: () => true }),
}))
vi.mock('./usePersonal', () => ({ usePersonal: () => ({ operate: vi.fn(), failed: vi.fn() }) }))

const global = {
  plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
  stubs: { RouterLink: RouterLinkStub, Panel: { template: '<aside><slot /></aside>' } },
}
function card(extra: Record<string, unknown> = {}) {
  return mount(MetricCard, {
    props: {
      metric: Metric.parse({
        kind: 'sleep',
        status: 'available',
        value: null,
        unit: 'min',
        resolution: sleepDecision(),
        ...extra,
      }),
      today: '2026-10-05',
    },
    global,
  })
}

describe('incomplete sleep presentation', () => {
  it('shows both received intervals on the dashboard and keeps known episode durations in details', () => {
    const wrapper = card()
    const summaries = wrapper.findAll('[data-testid="sleep-sessions"]')
    expect(summaries).toHaveLength(2)
    expect(summaries[0]!.findAll('[data-testid="sleep-session"]')).toHaveLength(2)
    expect(summaries[0]!.text()).toContain('00:35 — 08:33')
    expect(summaries[0]!.text()).toContain('13:00 — 13:40')
    expect(wrapper.text()).toContain('Длительность сна пока не определена')
    expect(wrapper.text()).not.toContain('Нет данных')
    expect(wrapper.text()).not.toContain('7 ч 58 мин')
    const details = wrapper.get('[data-testid="resolution-explanation"]')
    expect(details.text()).toContain('30 мин')
    expect(details.text()).toContain('Лёгкий сон')
    expect(details.text()).toContain('Неизвестная стадия')
    expect(details.text()).toContain('Бодрствование')
    expect(details.text()).toContain('В отчёте есть неизвестные стадии сна')
    expect(details.text()).not.toContain('unknown:1')
    expect(details.text()).toContain('Заменено обновлённым отчётом сна')
  })

  it('preserves the numerical summary of a complete night', () => {
    const resolution = sleepDecision()
    const wrapper = card({
      value: '420',
      resolution: {
        ...resolution,
        status: 'available',
        sessions: [{ ...resolution.sessions[0], asleep_minutes: 420, stages: [] }],
        quality: { flags: [] },
      },
    })
    expect(wrapper.text()).toContain('7 ч 0 мин')
    expect(wrapper.text()).not.toContain('Длительность сна пока не определена')
    expect(wrapper.get('[data-testid="resolution-explanation"]').text()).toContain('7 ч 0 мин')
  })

  it('states the sleep target in hours and minutes, like the night beside it', () => {
    const resolution = sleepDecision()
    const wrapper = card({
      value: '420',
      target: '480',
      resolution: {
        ...resolution,
        status: 'available',
        sessions: [{ ...resolution.sessions[0], asleep_minutes: 420, stages: [] }],
        quality: { flags: [] },
      },
    })
    expect(wrapper.text()).toContain('Цель: 8 ч 0 мин')
    expect(wrapper.text()).not.toContain('480 мин')
  })

  it('shows a legacy received interval without a resolver response', () => {
    const wrapper = card({
      resolution: null,
      records: [
        {
          id: 'legacy',
          kind: 'sleep',
          start_at: '2026-10-04T20:00:00Z',
          end_at: '2026-10-05T04:00:00Z',
          timezone: 'Europe/Moscow',
          method: 'device_report',
          revision: 1,
          data: { value: null },
        },
      ],
    })
    expect(wrapper.text()).toContain('Вчера, 23:00 — Сегодня, 07:00')
    expect(wrapper.text()).toContain('Длительность сна пока не определена')
    expect(wrapper.text()).not.toContain('Нет данных')
  })

  it.each([
    ['same_source_overlap', 'Пересекаются записи одного кольца'],
    ['sleep_report_conflict', 'Обновления отчёта сна не согласованы'],
    ['sleep_report_epoch_conflict', 'Нужен обновлённый отчёт сна после переподключения кольца'],
    ['sleep_report_correction_conflict', 'Ваша поправка конфликтует с обновлённым отчётом сна'],
  ])('states %s without a choice', (reason, message) => {
    const wrapper = card({
      status: 'conflict',
      resolution: {
        ...sleepDecision(),
        status: 'conflict',
        sessions: [],
        selected: [],
        alternatives: [{ id: 'conflict', reason }],
      },
    })
    expect(wrapper.text()).toContain(message)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('states any other conflict of the night plainly, without a choice', () => {
    const wrapper = card({
      status: 'conflict',
      resolution: {
        ...sleepDecision(),
        status: 'conflict',
        sessions: [],
        selected: [],
        alternatives: [
          { id: 'ring', reason: 'equal_priority_overlap' },
          { id: 'watch', reason: 'equal_priority_overlap' },
        ],
      },
    })
    expect(wrapper.text()).toContain('Показания не согласованы')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('shows the same incomplete sessions in a daily history result', () => {
    const wrapper = mount(DailyResolution, {
      props: {
        date: '2026-10-05',
        kind: 'sleep',
        value: null,
        unit: 'min',
        resolution: Resolution.parse(sleepDecision()),
        timezone: 'Europe/Moscow',
        today: '2026-10-05',
      },
      global,
    })
    expect(wrapper.findAll('[data-testid="sleep-sessions"]')[0]!.text()).toContain('00:35 — 08:33')
    expect(wrapper.text()).toContain('13:00 — 13:40')
    expect(wrapper.text()).toContain('Длительность сна пока не определена')
    expect(wrapper.text()).not.toContain('Нет данных')
  })

  it.each([
    ['same_source_overlap', 'Пересекаются записи одного кольца'],
    ['sleep_report_correction_conflict', 'Ваша поправка конфликтует с обновлённым отчётом сна'],
    ['equal_priority_overlap', 'Показания не согласованы'],
  ])(
    'gives the daily history a conflict explanation for %s without a way to choose',
    (reason, message) => {
      const wrapper = mount(DailyResolution, {
        props: {
          date: '2026-10-05',
          kind: 'sleep',
          value: null,
          unit: 'min',
          resolution: Resolution.parse({
            ...sleepDecision(),
            status: 'conflict',
            sessions: [],
            selected: [],
            alternatives: [{ id: 'conflict', reason }],
          }),
          timezone: 'Europe/Moscow',
          today: '2026-10-05',
        },
        global,
      })
      expect(wrapper.text()).toContain(message)
      expect(wrapper.findComponent(RouterLinkStub).exists()).toBe(false)
    },
  )

  it('shows the received interval and known stages in the correction details without filling the duration', () => {
    const session = sleepDecision().sessions[0]!
    const wrapper = mount(RecordPanel, {
      props: {
        open: true,
        today: '2026-10-05',
        record: Observation.parse({
          ...session,
          kind: 'sleep',
          method: 'device_report',
          revision: 1,
          timezone: 'Europe/Moscow',
          data: { value: null, stages: session.stages },
        }),
      },
      global,
    })
    expect(wrapper.get('[data-testid="sleep-sessions"]').text()).toContain('00:35 — 08:33')
    expect(wrapper.text()).toContain('Длительность сна пока не определена')
    const stages = wrapper.get('[data-testid="sleep-sessions"]')
    expect(stages.text()).toContain('Неизвестная стадия')
    expect(stages.text()).not.toContain('unknown:1')
    expect(wrapper.get('[data-testid="record-details"]').text()).toContain('unknown:1')
    expect(wrapper.get('[data-testid="record-value"]').element).toHaveProperty('value', '')
  })
})
