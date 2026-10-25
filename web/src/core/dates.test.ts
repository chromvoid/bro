import { describe, expect, it } from 'vitest'
import {
  dayIn,
  dayLabel,
  fullDayLabel,
  isIsoDay,
  momentPhrase,
  rangeLabel,
  shiftDay,
  shortDateLabel,
  timeIn,
  zonedInput,
  zonedInstant,
  zoneNote,
} from './dates'

describe('dates', () => {
  it('takes the calendar day in the account zone, not UTC', () => {
    const instant = new Date('2026-09-25T22:30:00Z')
    expect(dayIn('Europe/Moscow', instant)).toBe('2026-09-26')
    expect(dayIn('America/New_York', instant)).toBe('2026-09-25')
    expect(dayIn('Europe/Moscow', instant)).toBe('2026-09-26')
    expect(timeIn('Europe/Moscow', instant)).toBe('01:30')
    expect(timeIn('America/New_York', instant)).toBe('18:30')
  })

  it('moves across months and years without time zone drift', () => {
    expect(shiftDay('2026-03-01', -1)).toBe('2026-02-28')
    expect(shiftDay('2026-12-31', 1)).toBe('2027-01-01')
  })

  it('names nearby days and dates without ambiguity', () => {
    expect(dayLabel('2026-09-25', '2026-09-25')).toBe('Сегодня')
    expect(dayLabel('2026-09-24', '2026-09-25')).toBe('Вчера')
    expect(dayLabel('2026-09-26', '2026-09-25')).toBe('Завтра')
    expect(dayLabel('2026-09-01', '2026-09-25')).toBe('1 сентября')
    expect(dayLabel('2025-12-31', '2026-09-25')).toBe('31 декабря 2025 г.')
    expect(fullDayLabel('2026-09-23')).toBe('среда, 23 сентября 2026 г.')
    expect(fullDayLabel('2026-09-23', '2026-09-25')).toBe('среда, 23 сентября')
    expect(fullDayLabel('2025-12-31', '2026-09-25')).toBe('среда, 31 декабря 2025 г.')
  })

  it('names a period briefly, with years only across a new year', () => {
    expect(rangeLabel('2026-10-01', '2026-10-07')).toBe('1–7 окт.')
    expect(rangeLabel('2026-09-08', '2026-10-07')).toBe('8 сент. – 7 окт.')
    expect(rangeLabel('2025-12-29', '2026-01-04')).toBe('29 дек. 2025 г. – 4 янв. 2026 г.')
  })

  it('writes a short date with the year only when it differs', () => {
    expect(shortDateLabel('2026-10-12', '2026-10-12')).toBe('12 окт.')
    expect(shortDateLabel('2025-12-31', '2026-01-02')).toBe('31 дек. 2025 г.')
  })

  it('writes a moment inside a sentence in lower case', () => {
    expect(momentPhrase('2026-09-25T06:17:00Z', 'UTC', '2026-09-25')).toBe('сегодня, 06:17')
    expect(momentPhrase('2026-09-24T06:17:00Z', 'UTC', '2026-09-25')).toBe('вчера, 06:17')
    expect(momentPhrase('2026-09-01T06:17:00Z', 'UTC', '2026-09-25')).toBe('1 сентября, 06:17')
  })

  it('validates ISO days', () => {
    expect(isIsoDay('2026-02-28')).toBe(true)
    expect(isIsoDay('2026-02-30')).toBe(false)
    expect(isIsoDay('26-02-01')).toBe(false)
  })
})

describe('zoned date-times', () => {
  it('shows an instant as the wall clock of the account zone', () => {
    expect(zonedInput('2001-03-04T07:15:00+00:00', 'Europe/Belgrade')).toBe('2001-03-04T08:15')
    expect(zonedInput('2026-09-23T21:30:00Z', 'Asia/Tokyo')).toBe('2026-09-24T06:30')
  })

  it('turns a wall clock into an instant with the offset of that date', () => {
    expect(zonedInstant('2026-09-23T08:15', 'Europe/Belgrade')).toBe('2026-09-23T08:15:00+02:00')
    expect(zonedInstant('2026-01-10T08:15', 'Europe/Belgrade')).toBe('2026-01-10T08:15:00+01:00')
    expect(zonedInstant('2026-01-10T08:15', 'America/St_Johns')).toBe('2026-01-10T08:15:00-03:30')
    expect(zonedInstant('2026-01-10T08:15', 'UTC')).toBe('2026-01-10T08:15:00+00:00')
    expect(zonedInstant('08:15', 'UTC')).toBeNull()
  })

  it('round-trips, including around a clock change', () => {
    for (const local of ['2026-03-29T01:30', '2026-03-29T03:30', '2026-10-25T04:00']) {
      const instant = zonedInstant(local, 'Europe/Belgrade')!
      expect(zonedInput(instant, 'Europe/Belgrade')).toBe(local)
    }
    // 02:30 does not exist that night; it lands just after the change.
    const skipped = zonedInstant('2026-03-29T02:30', 'Europe/Belgrade')!
    expect(new Date(skipped).toISOString()).toBe('2026-03-29T01:30:00.000Z')
  })
})

describe('zone note', () => {
  it('says nothing when the account and the device share a zone', () => {
    expect(zoneNote('Europe/Moscow', 'Europe/Moscow')).toBeNull()
  })

  it('names a different zone in words, never by its identifier', () => {
    const note = zoneNote('Europe/Moscow', 'Europe/Belgrade')
    expect(note).toBeTruthy()
    expect(note).not.toContain('/')
  })
})
