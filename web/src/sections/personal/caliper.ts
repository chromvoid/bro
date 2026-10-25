/** Raw caliper readings and the server's versioned estimate stay together. */
import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { api } from '@/core/client'
import { dayIn } from '@/core/dates'
import { z } from '@/core/zod'
import type { Profile } from './api'
import { ageOn } from './profile'

export const FOLD_SITES = ['abdomen', 'suprailiac', 'chest', 'thigh', 'triceps'] as const
export type FoldSite = (typeof FOLD_SITES)[number]
const PROTOCOLS: Record<'male' | 'female', readonly FoldSite[]> = {
  male: ['chest', 'abdomen', 'thigh'],
  female: ['triceps', 'suprailiac', 'thigh'],
}

const Decimal = z.union([z.string(), z.number()]).transform(String)
export const CaliperMeasurement = z.looseObject({
  id: z.string(),
  source_id: z.string(),
  measured_at: z.string(),
  timezone: z.string(),
  folds_mm: z.partialRecord(z.enum(FOLD_SITES), Decimal),
  gender: z.enum(['male', 'female']).nullable(),
  age_years: z.number().int().nullable(),
  note: z.string().nullish(),
  body_fat_percent: Decimal.nullable(),
  calculation: z.looseObject({
    status: z.enum(['calculated', 'incomplete', 'unsupported']),
    method: z.literal('jackson_pollock_3_siri'),
    missing_fields: z.array(z.string()),
    algorithm_version: z.string(),
    reason: z.string().nullish(),
  }),
  revision: z.number().int().positive(),
  body_fat_record_id: z.string().nullable(),
})
export type CaliperMeasurement = z.infer<typeof CaliperMeasurement>
export const CaliperHistory = z.looseObject({
  records: z.array(CaliperMeasurement),
  truncated: z.boolean(),
  limit: z.number().int().positive(),
})

/** Calculation metadata names are not translated as arbitrary localization paths. */
export function missingCaliperKey(field: string): string | null {
  if ((FOLD_SITES as readonly string[]).includes(field)) return `personal.caliper.sites.${field}`
  if (field === 'gender') return 'personal.caliper.gender'
  if (field === 'age_years') return 'personal.caliper.age'
  return null
}

/** Completion fills only absent snapshot facts; later profile edits never replace saved facts. */
export function caliperCompletion(record: CaliperMeasurement, profile: Profile) {
  const metadata =
    record.calculation.status === 'incomplete'
      ? record.calculation.missing_fields.filter(
          (field) => field === 'gender' || field === 'age_years',
        )
      : []
  const gender = record.gender ?? profile.gender ?? null
  const profileAge = ageOn(profile.birth_date, dayIn(record.timezone, new Date(record.measured_at)))
  const age = record.age_years ?? profileAge
  const available =
    record.body_fat_percent === null &&
    metadata.length > 0 &&
    metadata.every((field) =>
      field === 'gender' ? gender !== null : age !== null && age >= 0 && age <= 120,
    )
  const missingFolds = gender
    ? PROTOCOLS[gender].filter((site) => record.folds_mm[site] === undefined)
    : []
  return { metadata, available, missingFolds }
}

export function useCaliperHistory(day: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['personal', 'caliper', day.value]),
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({ from: '1900-01-01', to: day.value, limit: '100' })
      return CaliperHistory.parse(await api.get(`/v1/personal/caliper?${query}`, signal))
    },
  })
}
