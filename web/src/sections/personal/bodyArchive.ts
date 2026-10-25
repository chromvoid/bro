/** Original provider data has its own read-only representation, separate from corrected facts. */
import { yieldToMain } from '@/core/scheduling'
import { z } from '@/core/zod'

export const SourceAnalysisSummary = z.object({
  id: z.string(),
  source_id: z.string(),
  source_name: z.string().nullish(),
  provider_record_id: z.string(),
  measured_at: z.string(),
  revision: z.number().int().positive(),
  received_at: z.string(),
})
export type SourceAnalysisSummary = z.infer<typeof SourceAnalysisSummary>

type JsonValue = z.infer<ReturnType<typeof z.json>>
export type ArchivePayload = Record<string, JsonValue>
/** Inspect own keys, including __proto__, without assigning them to another object. */
function isJson(value: unknown, depth = 0, active = new Set<object>()): boolean {
  if (depth > 32) return false
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (typeof value !== 'object' || active.has(value)) return false
  const array = Array.isArray(value)
  const prototype: unknown = Object.getPrototypeOf(value)
  if (!array && prototype !== Object.prototype && prototype !== null) return false
  active.add(value)
  try {
    if (array) {
      for (let index = 0; index < value.length; index++)
        if (!isJson(value[index], depth + 1, active)) return false
    } else {
      for (const key of Object.keys(value))
        if (!isJson((value as Record<string, unknown>)[key], depth + 1, active)) return false
    }
    return true
  } finally {
    active.delete(value)
  }
}
// Zod's JSON parser omits literal __proto__ keys, even during validation. Keep
// the verified original object so every provider field survives this boundary.
const ArchivePayload = z.custom<ArchivePayload>(
  (value) => value !== null && typeof value === 'object' && !Array.isArray(value) && isJson(value),
)
export const SourceAnalysis = SourceAnalysisSummary.extend({
  payload: ArchivePayload,
})
export type SourceAnalysis = z.infer<typeof SourceAnalysis>
export const SourceAnalysisReply = z.object({ analysis: SourceAnalysis })

export type ArchivePath = (string | number)[]
export interface ArchiveField {
  path: string
  parts: ArchivePath
  key: string
  value: JsonValue
  type: 'string' | 'number' | 'boolean' | 'null' | 'object' | 'array'
}

/** Only verified field meanings are translated. Unknown keys remain exactly as DDX sent them. */
const FIELD_LABELS: Readonly<Record<string, string>> = {
  total_weight: 'personal.metric.weight',
  total_fat_percent: 'personal.metric.body_fat_percent',
  body_fat_weight: 'personal.metric.body_fat_mass',
  body_skeleton_muscle_weight: 'personal.metric.skeletal_muscle_mass',
  fat_free_mass: 'personal.metric.fat_free_mass',
  body_water: 'personal.metric.body_water',
  body_protein: 'personal.metric.body_protein',
  body_mineral: 'personal.metric.body_minerals',
  total_bmi: 'personal.metric.bmi',
  basal_metabolic_rate: 'personal.metric.basal_metabolic_rate',
  visceral_fat_level: 'personal.metric.visceral_fat_level',
  target_weight: 'personal.archive.fields.targetWeight',
  recommended_calories: 'personal.archive.fields.recommendedCalories',
  created_at: 'personal.archive.fields.createdAt',
  id: 'personal.archive.fields.id',
}

export function archiveLabelKey(key: string): string | undefined {
  return Object.hasOwn(FIELD_LABELS, key) ? FIELD_LABELS[key] : undefined
}

/** JSON-style paths distinguish literal dots/brackets in keys from nesting and array positions. */
export function archivePath(parts: ArchivePath): string {
  return parts.reduce<string>((path, part) => {
    if (typeof part === 'number') return `${path}[${part}]`
    return /^[A-Za-z_$][\w$]*$/.test(part) ? `${path}.${part}` : `${path}[${JSON.stringify(part)}]`
  }, '$')
}

/** Yield leaf values and empty containers; a path retains every parent and array index. */
export function* archiveFields(value: JsonValue, parts: ArchivePath = []): Generator<ArchiveField> {
  if (Array.isArray(value) && value.length) {
    for (let index = 0; index < value.length; index++)
      yield* archiveFields(value[index]!, [...parts, index])
    return
  }
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    const keys = Object.keys(value)
    if (keys.length) {
      for (const key of keys) yield* archiveFields(value[key]!, [...parts, key])
      return
    }
  }
  const last = parts.at(-1)
  yield {
    path: archivePath(parts),
    parts,
    key: typeof last === 'number' ? `[${last}]` : (last ?? '$'),
    value,
    type:
      value === null
        ? 'null'
        : Array.isArray(value)
          ? 'array'
          : (typeof value as ArchiveField['type']),
  }
}

export function archiveValueText(field: ArchiveField): string {
  if (typeof field.value === 'string') return field.value
  return JSON.stringify(field.value)
}

export const ARCHIVE_PAGE_SIZE = 40
export interface ArchivePage {
  rows: ArchiveField[]
  total: number
}

/**
 * Search yields between small chunks, keeps just one page, and cancels when the query changes.
 * A 4 MiB archive must not create a DOM node or an in-memory row for every field at once.
 */
export async function searchArchive(
  payload: ArchivePayload,
  options: {
    query?: string
    page?: number
    pageSize?: number
    signal: AbortSignal
    label?: (key: string) => string
    yieldControl?: () => Promise<void>
  },
): Promise<ArchivePage> {
  const pageSize = Math.min(100, Math.max(1, Math.floor(options.pageSize ?? ARCHIVE_PAGE_SIZE)))
  const start = Math.max(0, Math.floor(options.page ?? 0)) * pageSize
  const needle = (options.query ?? '').trim().toLocaleLowerCase('ru')
  const rows: ArchiveField[] = []
  let total = 0
  let work = 0
  const pause = options.yieldControl ?? (() => yieldToMain(options.signal))
  options.signal.throwIfAborted()
  for (const field of archiveFields(payload)) {
    const matches =
      !needle ||
      [
        field.path,
        archiveValueText(field),
        ...field.parts
          .filter((part) => typeof part === 'string')
          .map((part) => options.label?.(part) ?? ''),
      ].some((part) => part.toLocaleLowerCase('ru').includes(needle))
    if (matches) {
      if (total >= start && rows.length < pageSize) rows.push(field)
      total++
    }
    if (++work % 256 === 0) {
      await pause()
      options.signal.throwIfAborted()
    }
  }
  options.signal.throwIfAborted()
  return { rows, total }
}
