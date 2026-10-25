import { api } from '@/core/client'
import { z } from '@/core/zod'

/** The movement history by day (docs/location-weather-spec.md §5). */
export const Days = z.object({
  timezone: z.string(),
  days: z.array(z.object({ date: z.string(), points: z.number().int() })),
})
export type Days = z.infer<typeof Days>

export const Stay = z.object({
  from: z.string(),
  to: z.string(),
  minutes: z.number().int(),
  latitude: z.number(),
  longitude: z.number(),
  place: z.string().nullable(),
})
export type Stay = z.infer<typeof Stay>

export const Move = z.object({ from: z.string(), to: z.string(), distance_m: z.number().int() })
export type Move = z.infer<typeof Move>

export const Day = z.object({
  date: z.string(),
  timezone: z.string(),
  points: z.number().int(),
  labels_pending: z.number().int(),
  stays: z.array(Stay),
  moves: z.array(Move),
  track: z.array(z.tuple([z.number(), z.number(), z.string()])),
})
export type Day = z.infer<typeof Day>

export async function readDays(signal?: AbortSignal) {
  return Days.parse(await api.get('/v1/places/days?limit=60', signal))
}

export async function readDay(date: string, signal?: AbortSignal) {
  return Day.parse(await api.get(`/v1/places/days/${encodeURIComponent(date)}`, signal))
}

/** One timeline of a day: stays and the moves between them, in time order. */
export type Entry = { kind: 'stay'; stay: Stay } | { kind: 'move'; move: Move }

export function timeline(day: Day): Entry[] {
  const entries = [
    ...day.stays.map((stay): [string, Entry] => [stay.from, { kind: 'stay', stay }]),
    ...day.moves.map((move): [string, Entry] => [move.from, { kind: 'move', move }]),
  ]
  return entries.sort((a, b) => Date.parse(a[0]) - Date.parse(b[0])).map(([, entry]) => entry)
}
