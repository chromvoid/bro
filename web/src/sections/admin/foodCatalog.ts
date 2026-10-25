import { z } from '@/core/zod'

/**
 * The Open Food Facts catalog as GET /v1/nutrition/catalog sends it to a system administrator
 * (server/plugins/nutrition/catalog_imports.py): the active release, earlier ones and the current
 * or last refresh job.
 */
const count = z.number().int()

export const CatalogJob = z.looseObject({
  id: z.string(),
  state: z.enum(['running', 'succeeded', 'failed', 'cancelled', 'interrupted']),
  stage: z.enum(['download', 'units', 'load', 'done']),
  progress: z.looseObject({
    bytes: count.nullish(),
    bytes_total: count.nullish(),
    lines: count.nullish(),
    kept: count.nullish(),
    release: z.string().nullish(),
    units: z
      .looseObject({
        from_quantity_text: count.optional(),
        to_read: count.optional(),
        asked: count.optional(),
        read: count.optional(),
        failed: count.optional(),
        // The model gateway's refusal kind when a call failed: no_account_access, quota_exhausted, ...
        error: z.string().optional(),
      })
      .optional(),
    loaded: count.optional(),
  }),
  error: z.string().nullish(),
  attempt: count.default(1),
  import_version_id: z.string().nullish(),
  created_at: z.string(),
  finished_at: z.string().nullish(),
})
export type CatalogJob = z.infer<typeof CatalogJob>

export const CatalogActive = z.looseObject({
  import_version_id: z.string(),
  release: z.string(),
  imported_at: z.string(),
  products: count,
  ml: count,
  with_package: count,
  units: z.looseObject({
    declared: count,
    from_quantity_text: count,
    read_by_model: count,
    undeclared: count,
  }),
})
export type CatalogActive = z.infer<typeof CatalogActive>

export const CatalogRelease = z.looseObject({
  import_version_id: z.string(),
  release: z.string(),
  imported_at: z.string(),
  products: count,
})
export type CatalogRelease = z.infer<typeof CatalogRelease>

export const FoodCatalog = z.looseObject({
  active: CatalogActive.nullable(),
  releases: z.array(CatalogRelease),
  job: CatalogJob.nullable(),
})
export type FoodCatalog = z.infer<typeof FoodCatalog>

/** A restart starts a running refresh again, this many attempts in all (catalog_imports.ATTEMPTS). */
export const ATTEMPTS = 3

/** Catalog operations; their events refresh the ['catalog'] queries (core/topics.ts). */
export const CATALOG = {
  endpoint: '/v1/nutrition/catalog/operations',
  lookupPrefix: '/v1/nutrition/catalog/operations/by-key/',
  domain: 'catalog',
}

const releaseFormat = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})

/** «10 октября 2026»: the export's day, never «Сегодня». */
export const releaseDay = (day: string) => releaseFormat.format(new Date(`${day}T00:00:00Z`))

export const UNIT_SOURCES = [
  'declared',
  'from_quantity_text',
  'read_by_model',
  'undeclared',
] as const

/** How far the running stage is, in percent, or null while its total is unknown. */
export function stagePercent(job: CatalogJob): number | null {
  const { progress } = job
  const share = (done: number | null | undefined, total: number | null | undefined) =>
    total ? Math.min(100, Math.round((100 * (done ?? 0)) / total)) : null
  if (job.stage === 'download') return share(progress.bytes, progress.bytes_total)
  if (job.stage === 'units') return share(progress.units?.asked, progress.units?.to_read)
  if (job.stage === 'load') return share(progress.loaded, progress.kept)
  return null
}
