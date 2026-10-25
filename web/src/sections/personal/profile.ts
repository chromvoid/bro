import { isIsoDay } from '@/core/dates'

/** Completed calendar years on the requested day, matching the server's measurement age. */
export function ageOn(birth: string | null | undefined, day: string): number | null {
  if (!isIsoDay(birth) || !isIsoDay(day) || birth > day) return null
  return (
    Number(day.slice(0, 4)) - Number(birth.slice(0, 4)) - (day.slice(5) < birth.slice(5) ? 1 : 0)
  )
}

export type ProfileField = 'birth' | 'gender' | 'height'

/** Deep links select a known form field, never a selector supplied by the URL. */
export function profileField(value: unknown): ProfileField | undefined {
  return value === 'birth' || value === 'gender' || value === 'height' ? value : undefined
}
