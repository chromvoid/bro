/**
 * Measurement sources as the person sees them: the devices and services that deliver data, and
 * for each kind of measurement where today's value came from. The server ranks competing sources
 * itself (wear, the owner's pin, measurement quality, connection order); this module only reads
 * that decision and writes the pin — «Всегда X» or automatic — as a dated policy.
 */
import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { api } from '@/core/client'
import { dayIn, shiftDay } from '@/core/dates'
import { z } from '@/core/zod'
import { BODY_KINDS, KINDS, amountText, type Kind } from './api'
import { DdxConnection } from './ddx'
import type { DisplayUnits } from './displayUnits'
import { ResolvedPage, type ResolutionPolicy } from './resolutionContract'
import { sensorAmount } from './sensors'

export const DEVICE_KINDS = ['ring', 'whoop', 'ddx', 'caliper', 'custom', 'collector'] as const
export type DeviceKind = (typeof DEVICE_KINDS)[number]

export const SourceDevice = z.looseObject({
  kind: z.enum(DEVICE_KINDS).catch('custom'),
  profile: z.string().nullish(),
  collector_id: z.string().nullish(),
  collector_enabled: z.boolean().nullish(),
})
export const SourceEntry = z.looseObject({
  id: z.string(),
  name: z.string(),
  kinds: z.array(z.string()),
  status: z.enum(['connected', 'unavailable', 'disabled']),
  last_received_at: z.string().nullish(),
  /** The latest moment it measured, which a late sync delivers long after. */
  last_measured_at: z.string().nullish(),
  created_at: z.string().nullish(),
  error: z.string().nullish(),
  revision: z.number(),
  metric_keys: z.array(z.string()).default([]),
  device: SourceDevice.default({ kind: 'custom' }),
  connection: DdxConnection.nullish(),
})
export type SourceEntry = z.infer<typeof SourceEntry>
export const SourcesList = z.looseObject({ sources: z.array(SourceEntry) })

const Decimal = z.union([z.string(), z.number()]).transform(Number)
export const Contribution = z.looseObject({
  source_id: z.string().nullable(),
  source_name: z.string().nullish(),
  count: z.number(),
  value: Decimal.nullish(),
  spans: z.array(z.looseObject({ start_at: z.string(), end_at: z.string() })).default([]),
})
export type Contribution = z.infer<typeof Contribution>
export const Comparison = z.looseObject({
  source_ids: z.tuple([z.string(), z.string()]),
  count: z.number(),
  totals: z.tuple([Decimal, Decimal]),
  mean_difference: Decimal,
  mean_absolute_difference: Decimal,
  outside_reference: z.number().default(0),
})
export type Comparison = z.infer<typeof Comparison>

/** The rows of «Что откуда берётся»: one choice may cover several stored metrics. */
export const SOURCE_GROUPS = [
  {
    key: 'activity',
    rows: [
      { key: 'steps', metrics: ['steps'] },
      { key: 'active_minutes', metrics: ['active_minutes'] },
      { key: 'active_energy', metrics: ['active_energy'] },
      { key: 'distance', metrics: ['distance'] },
      { key: 'workouts', metrics: ['workouts', 'running_workouts', 'running_steps'] },
    ],
  },
  { key: 'sleep', rows: [{ key: 'sleep', metrics: ['sleep'] }] },
  {
    key: 'vitals',
    rows: [
      { key: 'heart_rate', metrics: ['heart_rate'] },
      { key: 'hrv', metrics: ['hrv'] },
      { key: 'spo2', metrics: ['spo2'] },
      { key: 'temperature', metrics: ['temperature'] },
      { key: 'stress', metrics: ['stress'] },
    ],
  },
  {
    key: 'body',
    rows: [
      { key: 'weight', metrics: ['weight'] },
      { key: 'body', metrics: [...BODY_KINDS] },
    ],
  },
] as const
export type SourceRowKey = (typeof SOURCE_GROUPS)[number]['rows'][number]['key']
export interface SourceRowSpec {
  key: SourceRowKey
  metrics: readonly string[]
}
export const SOURCE_ROWS: SourceRowSpec[] = SOURCE_GROUPS.flatMap((group) => [...group.rows])
/** Rows whose today has a time split worth drawing; weight and body follow whole analyses. */
export const TIMED_ROWS = new Set<SourceRowKey>([
  'steps',
  'active_minutes',
  'heart_rate',
  'spo2',
  'stress',
  'sleep',
  'hrv',
  'temperature',
])

/** A source that still delivers: a turned-off source or collector cannot be chosen. */
export const activeSource = (source: SourceEntry) =>
  source.status !== 'disabled' && source.device.collector_enabled !== false

export const sourcesOf = (row: SourceRowSpec, sources: SourceEntry[]) =>
  sources.filter(
    (source) =>
      activeSource(source) && row.metrics.some((metric) => source.metric_keys.includes(metric)),
  )

/** The newest policy in effect on a day, as the server picks it. */
export function policyOn(policies: ResolutionPolicy[], metric: string, day: string) {
  return (
    policies
      .filter((policy) => policy.metric === metric && policy.effective_from <= day)
      .sort(
        (a, b) =>
          a.effective_from.localeCompare(b.effective_from) || (a.revision ?? 0) - (b.revision ?? 0),
      )
      .at(-1) ?? null
  )
}

/** The pinned source of a row on a day, or null when the server chooses automatically. */
export function pinOf(row: SourceRowSpec, policies: ResolutionPolicy[], day: string) {
  for (const metric of row.metrics) {
    const pinned = policyOn(policies, metric, day)?.source_ids[0]
    if (pinned) return pinned
  }
  return null
}

type Daily = ResolvedPage['daily'][number]
export const contributionsOf = (daily: Daily | undefined): Contribution[] =>
  z
    .array(Contribution)
    .catch([])
    .parse(daily?.resolution.contributions ?? [])
export const comparisonsOf = (daily: Daily | undefined): Comparison[] =>
  z
    .array(Comparison)
    .catch([])
    .parse(daily?.resolution.comparisons ?? [])

/** A decision's contributors, the largest first: the value's share, else how many readings it gave. */
export function rankedContributions(value: unknown): Contribution[] {
  return z
    .array(Contribution)
    .catch([])
    .parse(value ?? [])
    .sort((a, b) => (b.value ?? 0) - (a.value ?? 0) || b.count - a.count)
}

/** Today's contributors, the largest first. */
export function contributors(daily: Daily | undefined): Contribution[] {
  return rankedContributions(daily?.resolution.contributions)
}

export function rowDaily(row: SourceRowSpec, page: ResolvedPage | undefined, day: string) {
  return row.metrics
    .map((metric) => page?.daily.find((item) => item.date === day && item.kind === metric))
    .find((item) => item && contributionsOf(item).length)
}

/** How a source served a row today: the main contributor, a filler of gaps, or not at all. */
export function roleOf(
  sourceId: string,
  row: SourceRowSpec,
  page: ResolvedPage | undefined,
  day: string,
): 'primary' | 'backup' | null {
  const ranked = contributors(rowDaily(row, page, day))
  const index = ranked.findIndex((item) => item.source_id === sourceId)
  return index < 0 ? null : index === 0 ? 'primary' : 'backup'
}

/** Two devices over the last days: summed per pair, weighted by how many readings matched. */
export function combinedComparisons(page: ResolvedPage | undefined): Comparison[] {
  const pairs = new Map<string, Comparison>()
  for (const daily of page?.daily ?? []) {
    for (const item of comparisonsOf(daily)) {
      const key = item.source_ids.join('|')
      const seen = pairs.get(key)
      if (!seen) {
        pairs.set(key, { ...item })
        continue
      }
      const count = seen.count + item.count
      pairs.set(key, {
        ...seen,
        count,
        totals: [seen.totals[0] + item.totals[0], seen.totals[1] + item.totals[1]],
        mean_difference:
          (seen.mean_difference * seen.count + item.mean_difference * item.count) / count,
        mean_absolute_difference:
          (seen.mean_absolute_difference * seen.count +
            item.mean_absolute_difference * item.count) /
          count,
        outside_reference: seen.outside_reference + item.outside_reference,
      })
    }
  }
  return [...pairs.values()].filter((item) => item.count > 0)
}

/** The orders to save: a pin for the metrics the chosen source reports, automatic for all. */
export function policyEntries(
  row: SourceRowSpec,
  choice: string | null,
  sources: SourceEntry[],
  policies: ResolutionPolicy[],
  effectiveFrom: string,
) {
  const chosen = sources.find((source) => source.id === choice)
  const metrics = choice
    ? row.metrics.filter((metric) => chosen?.metric_keys.includes(metric))
    : row.metrics.filter(
        (metric) =>
          policyOn(policies, metric, effectiveFrom) ||
          sources.some((source) => source.metric_keys.includes(metric)),
      )
  return metrics.map((metric) => {
    const existing = policies.find(
      (policy) => policy.metric === metric && policy.effective_from === effectiveFrom,
    )
    return {
      metric,
      source_ids: choice ? [choice] : [],
      revision: existing?.revision ?? 0,
      existing,
    }
  })
}

export function useSources(enabled?: Ref<boolean>) {
  return useQuery({
    enabled,
    queryKey: ['personal', 'sources'],
    queryFn: async ({ signal }) => SourcesList.parse(await api.get('/v1/personal/sources', signal)),
  })
}

/** One day of every metric's decision, without its series: the screen reads choices and shares. */
export function useSourcesDay(day: Ref<string>, enabled?: Ref<boolean>) {
  return useQuery({
    enabled,
    queryKey: computed(() => ['personal', 'sources-day', day.value]),
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({ from: day.value, to: day.value, limit: '1' })
      return ResolvedPage.parse(await api.get(`/v1/personal/resolved?${query}`, signal))
    },
  })
}

/** A week of one metric's decisions, for the comparison between devices. */
export function useSourcesWeek(metric: Ref<string | null>, day: Ref<string>) {
  return useQuery({
    enabled: computed(() => metric.value !== null),
    queryKey: computed(() => ['personal', 'sources-week', metric.value, day.value]),
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({
        from: shiftDay(day.value, -6),
        to: day.value,
        metric: metric.value!,
        limit: '1',
      })
      return ResolvedPage.parse(await api.get(`/v1/personal/resolved?${query}`, signal))
    },
  })
}

const MAX_SOURCE_DAY_PAGES = 40

/** The day a device's data opens on: its latest measured day, today at most. */
export function latestDay(source: SourceEntry | null, zone: string, today: string) {
  const last = source?.last_measured_at ?? source?.last_received_at
  const day = last ? dayIn(zone, new Date(last)) : today
  return day < today ? day : today
}

/**
 * What one device reported over a day, resolved on its own: none of the other devices, no pin,
 * every page of its readings, so a device left out of the combined day still shows its values.
 */
export function useSourceDay(source: Ref<string | null>, day: Ref<string>) {
  return useQuery({
    enabled: computed(() => source.value !== null),
    queryKey: computed(() => ['personal', 'source-day', source.value, day.value]),
    queryFn: async ({ signal }) => {
      const id = source.value!
      const date = day.value
      const pages: ResolvedPage[] = []
      let cursor: string | null = null
      do {
        const query: URLSearchParams = new URLSearchParams({
          from: date,
          to: date,
          source_id: id,
          limit: '500',
          ...(cursor ? { cursor } : {}),
        })
        const page = ResolvedPage.parse(await api.get(`/v1/personal/resolved?${query}`, signal))
        pages.push(page)
        cursor = page.next_cursor
      } while (cursor && pages.length < MAX_SOURCE_DAY_PAGES)
      return { daily: pages[0]?.daily ?? [], series: pages.flatMap((page) => page.series) }
    },
  })
}

export type DayItem = ResolvedPage['daily'][number]

/** A metric reported that day: a value, or a night of sleep known only by its periods. */
export const reported = (item: DayItem) =>
  item.value !== null || (item.kind === 'sleep' && (item.resolution.sessions?.length ?? 0) > 0)

/** The row's metrics a device reported on a day, in the row's order. */
export const rowItems = (row: SourceRowSpec, daily: DayItem[], day: string) =>
  row.metrics
    .map((metric) => daily.find((item) => item.date === day && item.kind === metric))
    .filter((item): item is DayItem => item !== undefined && reported(item))

export const VITAL_METRICS = new Set(['heart_rate', 'hrv', 'spo2', 'temperature', 'stress'])

export interface Reading {
  id: string
  at: number
  value: number
  unit: string
}

/** A vital's separate readings of the day, in time order; a range without a value is left out. */
export function readingsOf(series: Record<string, unknown>[], metric: string): Reading[] {
  return series
    .flatMap((row) => {
      const at = typeof row.start_at === 'string' ? Date.parse(row.start_at) : NaN
      const value = row.value === null || row.value === undefined ? NaN : Number(row.value)
      return row.metric === metric && Number.isFinite(at) && Number.isFinite(value)
        ? [{ id: String(row.id), at, value, unit: String(row.unit ?? '') }]
        : []
    })
    .sort((a, b) => a.at - b.at)
}

type Count = (key: string, count: number) => string

/** A day's value as the dashboard writes it: sleep as a duration, mass in the person's units. */
export function dayAmount(
  count: Count,
  item: Pick<DayItem, 'kind' | 'value' | 'unit'>,
  units: DisplayUnits,
): string | null {
  if (item.value === null) return null
  if ((KINDS as readonly string[]).includes(item.kind))
    return amountText(item.kind as Kind, item.value, units)
  if (item.unit === 'workouts') return count('sources.workouts', Number(item.value))
  if (item.unit === 'steps') return amountText('steps', item.value, units)
  return sensorAmount(item.value, item.unit, units)
}

type Translate = (key: string, named?: Record<string, unknown>) => string
const NAMED_DEVICES = new Set<DeviceKind>(['ring', 'whoop', 'ddx', 'caliper'])

/** «Кольцо HerzRing», «DDX InBody»; a source the person registered keeps its own name. */
export const deviceTitle = (t: Translate, source: SourceEntry) =>
  NAMED_DEVICES.has(source.device.kind) ? t(`sources.device.${source.device.kind}`) : source.name

/** The device's own name under its title, such as the ring's «R20_5AF2». */
export const deviceDetail = (source: SourceEntry) =>
  source.device.kind === 'ring' || source.device.kind === 'whoop' ? source.name : null

/**
 * Short names inside sentences («кольцо», «WHOOP»). Two sources of one kind, such as a ring paired
 * again, differ by the day they were connected.
 */
export function shortNames(
  t: Translate,
  sources: SourceEntry[],
  connected: (source: SourceEntry) => string,
): Map<string, string> {
  const base = (source: SourceEntry) =>
    NAMED_DEVICES.has(source.device.kind) ? t(`sources.short.${source.device.kind}`) : source.name
  // Only connections still in use need telling apart; a switched-off one keeps its plain name.
  const counts = new Map<string, number>()
  for (const source of sources.filter(activeSource))
    counts.set(base(source), (counts.get(base(source)) ?? 0) + 1)
  return new Map(
    sources.map((source) => [
      source.id,
      (counts.get(base(source)) ?? 0) > 1 && activeSource(source) && source.created_at
        ? t('sources.since', { name: base(source), date: connected(source) })
        : base(source),
    ]),
  )
}
