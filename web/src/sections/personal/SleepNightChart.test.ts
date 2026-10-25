import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import { russianPlural } from '@/i18n'
import ru from '@/locales/ru.json'
import { SleepSession } from './resolutionContract'
import { hourTicks, laneOf, nightLanes, stageLane, turnMarks, type Part } from './sleepChart'
import SleepNightChart from './SleepNightChart.vue'
import SleepSessions from './SleepSessions.vue'

const global = {
  plugins: [
    createI18n({
      legacy: false,
      locale: 'ru',
      messages: { ru },
      pluralRules: { ru: russianPlural },
    }),
  ],
}
// A strap's night: asleep from 02:48 to 08:20 Moscow time without waking.
const START = '2026-10-13T23:48:00Z'
const END = '2026-10-14T05:20:00Z'
const rows = (root: Element) =>
  [...root.querySelectorAll('[data-testid^="sleep-lane-"]')].map((row) =>
    [...row.querySelectorAll('span')].map((part) => part.textContent?.trim()),
  )

describe('sleep night chart', () => {
  it('shows a night without waking as sleep with an empty waking row', () => {
    const lanes = nightLanes(START, END, [{ start_at: START, end_at: END, lane: 'sleep' }])
    expect(lanes.map((row) => [row.lane, row.minutes, row.parts.length])).toEqual([
      ['awake', 0, 0],
      ['sleep', 332, 1],
    ])
    const view = mount(SleepNightChart, {
      props: {
        start: START,
        end: END,
        parts: [{ start_at: START, end_at: END, lane: 'sleep' }],
        timezone: 'Europe/Moscow',
        today: '2026-10-14',
      },
      global,
    })
    expect(rows(view.element)).toEqual([
      ['Бодрствование', '0 мин'],
      ['Сон', '5 ч 32 мин'],
    ])
    expect(view.get('rect[data-state="sleep"]').attributes()).toMatchObject({
      x: '0',
      width: '1000',
      class: 'fill-primary',
    })
    const axis = view.findAll('[data-testid="sleep-axis-label"]').map((label) => label.text())
    expect(axis[0]).toBe('02:48')
    expect(axis.at(-1)).toBe('08:20')
    expect(axis).toContain('06:00')
    expect(view.find('[style]').exists()).toBe(false)
    // Drawings sit inset in rounded tracks, so no block is cut into a rounded end; the axis
    // keeps the same inset.
    const tracks = view.findAll('[data-testid="sleep-track"]')
    expect(tracks).toHaveLength(2)
    for (const track of tracks) {
      expect(track.classes()).toContain('p-0.5')
      expect(track.get('svg').classes()).not.toContain('rounded-md')
    }
    expect(
      view.get('[data-testid="sleep-axis-label"]').element.closest('div')!.classList,
    ).toContain('px-0.5')
  })

  it('shows lying awake in bed and the turns on rows of their own', () => {
    const bed = '2026-10-13T23:36:00Z'
    const turns = ['2026-10-13T23:37:00Z', '2026-10-14T00:52:00Z', '2026-10-14T02:06:00Z']
    const view = mount(SleepNightChart, {
      props: {
        start: bed,
        end: END,
        parts: [
          { start_at: bed, end_at: START, lane: 'in_bed' },
          { start_at: START, end_at: END, lane: 'sleep' },
        ],
        // An instant outside the night is no mark.
        changes: [...turns, '2026-10-14T06:00:00Z'],
        timezone: 'Europe/Moscow',
        today: '2026-10-14',
      },
      global,
    })
    expect(rows(view.element)).toEqual([
      ['Бодрствование', '0 мин'],
      ['В постели', '12 мин'],
      ['Сон', '5 ч 32 мин'],
      ['Повороты', '3 раза'],
    ])
    expect(view.findAll('[data-testid="sleep-turn"]').map((mark) => mark.text())).toEqual([
      '02:37',
      '03:52',
      '05:06',
    ])
    expect(view.get('rect[data-state="in_bed"]').classes()).toContain('fill-warning-fill/50')
    expect(turnMarks(bed, END, ['later'])).toEqual([])
  })

  it('places waking inside the night on its own row', () => {
    const parts: Part[] = [
      { start_at: '2026-10-13T22:00:00Z', end_at: '2026-10-14T01:00:00Z', lane: 'sleep' },
      { start_at: '2026-10-14T01:00:00Z', end_at: '2026-10-14T01:20:00Z', lane: 'awake' },
      { start_at: '2026-10-14T01:20:00Z', end_at: '2026-10-14T06:00:00Z', lane: 'sleep' },
    ]
    const lanes = nightLanes('2026-10-13T22:00:00Z', '2026-10-14T06:00:00Z', parts)
    expect(lanes.map((row) => [row.lane, row.minutes])).toEqual([
      ['awake', 20],
      ['sleep', 460],
    ])
    expect(lanes[0]!.parts[0]).toMatchObject({ x: 375 })
    expect(lanes[0]!.parts[0]!.width).toBeCloseTo(41.67, 1)
  })

  it('keeps stages in reading order and unknown time out of the waking total', () => {
    const lanes = nightLanes('2026-10-07T00:00:00Z', '2026-10-07T04:00:00Z', [
      { start_at: '2026-10-07T00:00:00Z', end_at: '2026-10-07T01:00:00Z', lane: 'light' },
      { start_at: '2026-10-07T01:00:00Z', end_at: '2026-10-07T02:00:00Z', lane: 'deep' },
      { start_at: '2026-10-07T02:00:00Z', end_at: '2026-10-07T03:00:00Z', lane: 'unknown' },
      { start_at: '2026-10-07T03:00:00Z', end_at: '2026-10-07T04:00:00Z', lane: 'rem' },
    ])
    expect(lanes.map((row) => row.lane)).toEqual(['rem', 'light', 'deep', 'unknown'])
  })

  it('maps analysed states and reported stages to rows', () => {
    expect(laneOf('sleep', 'deep')).toBe('deep')
    expect(laneOf('sleep', 'sleeping')).toBe('sleep')
    expect(laneOf('sleep', null)).toBe('sleep')
    expect(laneOf('awake', 'awake_in_bed')).toBe('in_bed')
    expect(laneOf('awake', 'awake')).toBe('awake')
    expect(stageLane('awake_in_bed')).toBe('in_bed')
    expect(laneOf('conflict', null)).toBe('conflict')
    expect(laneOf('unknown', null)).toBe('unknown')
    expect(stageLane('out_of_bed')).toBe('awake')
    expect(stageLane('sleeping')).toBe('sleep')
    expect(stageLane('rem')).toBe('rem')
    expect(stageLane('unknown')).toBe('unknown')
  })

  it('marks whole local hours, even in a zone offset by half an hour', () => {
    expect(hourTicks(START, END, 'Europe/Moscow').map((tick) => tick.at)).toEqual([
      '2026-10-14T01:00:00.000Z',
      '2026-10-14T03:00:00.000Z',
      '2026-10-14T05:00:00.000Z',
    ])
    expect(
      hourTicks('2026-10-13T17:00:00Z', '2026-10-13T20:00:00Z', 'Asia/Kolkata').map((t) => t.at),
    ).toEqual(['2026-10-13T17:30:00.000Z', '2026-10-13T18:30:00.000Z', '2026-10-13T19:30:00.000Z'])
  })

  it('draws the reported stages of a session in its details', () => {
    const session = SleepSession.parse({
      id: 'whoop-night',
      start_at: START,
      end_at: END,
      asleep_minutes: '332',
      source_name: 'WHOOP',
      stages: [{ start_at: START, end_at: END, stage: 'sleeping' }],
    })
    const view = mount(SleepSessions, {
      props: {
        sessions: [session],
        timezone: 'Europe/Moscow',
        today: '2026-10-14',
        detailed: true,
      },
      global,
    })
    expect(rows(view.element)).toEqual([
      ['Бодрствование', '0 мин'],
      ['Сон', '5 ч 32 мин'],
    ])
  })
})
