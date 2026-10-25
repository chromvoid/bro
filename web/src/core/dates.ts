import { i18n } from '@/i18n'

/**
 * Calendar days belong to the account's time zone (the diary's, not the device's): a day is an
 * ISO date string (YYYY-MM-DD) and is never derived from a UTC timestamp by slicing.
 */

export type IsoDay = string

// Building a formatter costs tens of microseconds (more on a phone), and lists format every row:
// one formatter per zone is reused.
function perZone(build: (timeZone: string) => Intl.DateTimeFormat) {
  const formats = new Map<string, Intl.DateTimeFormat>()
  return (timeZone: string) => {
    let format = formats.get(timeZone)
    if (!format) formats.set(timeZone, (format = build(timeZone)))
    return format
  }
}
const dayParts = perZone(
  (timeZone) =>
    new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }),
)
const hourMinute = perZone(
  (timeZone) => new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', timeZone }),
)
const wallClockParts = perZone(
  (timeZone) =>
    new Intl.DateTimeFormat('en-CA', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }),
)

/** The calendar day of [instant] in [timeZone]. */
export function dayIn(timeZone: string, instant: Date = new Date()): IsoDay {
  const parts = dayParts(timeZone).formatToParts(instant)
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}`
}

/** The hour of [instant] on the wall clock of [timeZone], 0 to 23. */
export function hourIn(timeZone: string, instant: Date = new Date()): number {
  return Number(wallClock(timeZone, instant).time.slice(0, 2))
}

/** The minute of the day of [instant] on the wall clock of [timeZone], 0 to 1439. */
export function minuteOfDayIn(timeZone: string, instant: Date): number {
  const time = wallClock(timeZone, instant).time
  return Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
}

/** Moves a calendar day by whole days, independent of any time zone. */
export function shiftDay(day: IsoDay, days: number): IsoDay {
  const [year, month, date] = day.split('-').map(Number) as [number, number, number]
  return new Date(Date.UTC(year, month - 1, date + days)).toISOString().slice(0, 10)
}

export function isIsoDay(value: unknown): value is IsoDay {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  return shiftDay(value, 0) === value
}

const dayFormat = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  timeZone: 'UTC',
})
const dayFormatYear = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})
const weekday = new Intl.DateTimeFormat('ru-RU', { weekday: 'long', timeZone: 'UTC' })

function asUtc(day: IsoDay): Date {
  return new Date(`${day}T00:00:00Z`)
}

/** «23 сентября», with the year when it is not [today]'s. */
export function dateLabel(day: IsoDay, today: IsoDay): string {
  return (day.slice(0, 4) === today.slice(0, 4) ? dayFormat : dayFormatYear).format(asUtc(day))
}

/** «Сегодня», «Вчера», «Завтра», otherwise «23 сентября» (with the year when it differs). */
export function dayLabel(day: IsoDay, today: IsoDay): string {
  if (day === today) return i18n.global.t('day.today')
  if (day === shiftDay(today, -1)) return i18n.global.t('day.yesterday')
  if (day === shiftDay(today, 1)) return i18n.global.t('day.tomorrow')
  return dateLabel(day, today)
}

/**
 * «среда, 23 сентября 2026 г.»: an unambiguous absolute date for headings. Given [today], a day of
 * the same year drops the year («среда, 23 сентября») so the heading fits one line on a phone.
 */
export function fullDayLabel(day: IsoDay, today?: IsoDay): string {
  const format = today && day.slice(0, 4) === today.slice(0, 4) ? dayFormat : dayFormatYear
  return `${weekday.format(asUtc(day))}, ${format.format(asUtc(day))}`
}

const dayMonthShort = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
  timeZone: 'UTC',
})
const dayMonthShortYear = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
})
/** A period as short words: «2–8 окт.», «8 сент. – 7 окт.», across a year with the years. */
export function rangeLabel(from: IsoDay, to: IsoDay): string {
  const [start, end] = [asUtc(from), asUtc(to)]
  if (from.slice(0, 4) !== to.slice(0, 4))
    return `${dayMonthShortYear.format(start)} – ${dayMonthShortYear.format(end)}`
  if (from.slice(0, 7) === to.slice(0, 7))
    return `${Number(from.slice(8))}–${dayMonthShort.format(end)}`
  return `${dayMonthShort.format(start)} – ${dayMonthShort.format(end)}`
}

/** «12 окт.», with the year when it is not [today]'s: a date where room is short. */
export function shortDateLabel(day: IsoDay, today: IsoDay): string {
  const format = day.slice(0, 4) === today.slice(0, 4) ? dayMonthShort : dayMonthShortYear
  return format.format(asUtc(day))
}

/** Local wall time of an instant in the account's zone, e.g. «09:17». */
export function timeIn(timeZone: string, instant: string | Date): string {
  return hourMinute(timeZone).format(new Date(instant))
}

function wallClock(timeZone: string, instant: Date) {
  const parts = wallClockParts(timeZone).formatToParts(instant)
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? '00'
  return {
    day: `${get('year')}-${get('month')}-${get('day')}`,
    time: `${get('hour')}:${get('minute')}`,
    seconds: Number(get('second')),
  }
}

/** The value of a datetime-local field for [instant] as seen in [timeZone]: «2026-09-23T08:15». */
export function zonedInput(instant: string | Date, timeZone: string): string {
  const clock = wallClock(timeZone, new Date(instant))
  return `${clock.day}T${clock.time}`
}

/** Minutes [timeZone] is ahead of UTC at [instant]. */
function offsetAt(timeZone: string, instant: number): number {
  const clock = wallClock(timeZone, new Date(instant))
  const [year, month, day] = clock.day.split('-').map(Number) as [number, number, number]
  const [hour, minute] = clock.time.split(':').map(Number) as [number, number]
  const wall = Date.UTC(year, month - 1, day, hour, minute, clock.seconds)
  return Math.round((wall - Math.floor(instant / 1000) * 1000) / 60_000)
}

/**
 * The instant a wall-clock time (a datetime-local value) means in [timeZone], as ISO 8601 with
 * the zone's offset. A time skipped by a clock change lands just after the change.
 */
export function zonedInstant(local: string, timeZone: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local)
  if (!match) return null
  const [, year, month, day, hour, minute] = match.map(Number) as number[]
  const wall = Date.UTC(year!, month! - 1, day!, hour!, minute!)
  let offset = offsetAt(timeZone, wall)
  offset = offsetAt(timeZone, wall - offset * 60_000)
  const sign = offset < 0 ? '-' : '+'
  const absolute = Math.abs(offset)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${local}:00${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
}

/** «23 сентября, 08:15» (with «Сегодня»/«Вчера») for an instant in the account's zone. */
export function momentLabel(instant: string, timeZone: string, today: IsoDay): string {
  return `${dayLabel(dayIn(timeZone, new Date(instant)), today)}, ${timeIn(timeZone, instant)}`
}

/** [momentLabel] inside a sentence: «Измерено: сегодня, 09:17», not «…: Сегодня, 09:17». */
export function momentPhrase(instant: string, timeZone: string, today: IsoDay): string {
  const label = momentLabel(instant, timeZone, today)
  return label.charAt(0).toLocaleLowerCase('ru-RU') + label.slice(1)
}

/** Whether the browser knows [zone] as an IANA time zone (aliases like «UTC» included). */
export function isTimeZone(zone: string): boolean {
  if (!zone.trim()) return false
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone })
    return true
  } catch {
    return false
  }
}

/**
 * The account's time zone in words, when it differs from this device's: «Москва», else «GMT+3».
 * Null when they agree, so the screen says nothing; never the raw «Europe/Moscow».
 */
export function zoneNote(accountZone: string, deviceZone?: string): string | null {
  const device = deviceZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone
  if (accountZone === device) return null
  for (const style of ['longGeneric', 'shortOffset'] as const) {
    try {
      const name = new Intl.DateTimeFormat('ru', { timeZone: accountZone, timeZoneName: style })
        .formatToParts(new Date())
        .find((part) => part.type === 'timeZoneName')?.value
      if (name && !name.includes('/')) return name
    } catch {
      // An engine without this style or zone: try the next.
    }
  }
  return null
}
