import { api } from '@/core/client'
import { z } from '@/core/zod'

/** One position as devices send it (docs/location-weather-spec.md §3.1). */
export interface Fix {
  captured_at: string
  latitude: number
  longitude: number
  accuracy_m: number
}

export const Location = z.object({
  current: z
    .object({
      place: z.string().nullable(),
      country: z.string().nullable(),
      latitude: z.number(),
      longitude: z.number(),
      accuracy_m: z.number().int(),
      age_minutes: z.number().int(),
      device: z.string().nullable(),
      captured_at: z.string(),
    })
    .nullable(),
  history: z.object({ points: z.number().int(), since: z.string().nullable() }),
  background: z.object({ enabled: z.boolean() }),
})
export type Location = z.infer<typeof Location>

export const LOCATION_KEY = ['location'] as const

export async function readLocation(signal?: AbortSignal) {
  return Location.parse(await api.get('/v1/me/location', signal))
}

export async function sendFix(fix: Fix) {
  await api.send('POST', '/v1/me/location/points', JSON.stringify({ points: [fix] }))
}

export async function forgetHistory() {
  await api.send('DELETE', '/v1/me/location/history', undefined)
}
