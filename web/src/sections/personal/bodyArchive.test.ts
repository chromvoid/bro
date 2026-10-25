import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  SourceAnalysis,
  archiveFields,
  archiveLabelKey,
  archivePath,
  searchArchive,
} from './bodyArchive'

const signal = () => new AbortController().signal
afterEach(() => vi.unstubAllGlobals())

describe('full source analysis inspection', () => {
  it('preserves unknown nested JSON and distinguishes null, zero, false and empty containers', () => {
    const payload = {
      detail: { data: [{ future: [0, null, false, '', {}, []], 'literal.key': '  text  ' }] },
      summary: { custom: true },
    }
    const result = SourceAnalysis.parse({
      id: 'synthetic-archive',
      source_id: 'synthetic-source',
      source_name: 'Synthetic DDX',
      provider_record_id: '7',
      measured_at: '2026-10-01T07:00:00Z',
      received_at: '2026-10-02T07:00:00Z',
      revision: 2,
      payload,
    })
    expect(result.payload).toEqual(payload)
    const fields = [...archiveFields(result.payload)]
    expect(fields.map((field) => field.type)).toEqual([
      'number',
      'null',
      'boolean',
      'string',
      'object',
      'array',
      'string',
      'boolean',
    ])
    expect(fields[6]).toMatchObject({
      path: '$.detail.data[0]["literal.key"]',
      value: '  text  ',
    })
    expect(archivePath(['a[0]', 0, 'quote"key'])).toBe('$["a[0]"][0]["quote\\"key"]')
  })

  it('translates only known field meanings and keeps special keys inert', () => {
    expect(archiveLabelKey('total_weight')).toBe('personal.metric.weight')
    expect(archiveLabelKey('segment_up_center')).toBeUndefined()
    expect(archiveLabelKey('toString')).toBeUndefined()
    expect(archiveLabelKey('__proto__')).toBeUndefined()
  })

  it('preserves literal prototype-like fields at every level without changing prototypes', () => {
    const payload: unknown = JSON.parse(
      '{"__proto__":{"synthetic_marker":"top"},"detail":{"__proto__":{"synthetic_marker":"nested"},"constructor":"reported"}}',
    )
    const result = SourceAnalysis.parse({
      id: 'synthetic-archive',
      source_id: 'synthetic-source',
      provider_record_id: '7',
      measured_at: '2026-10-01T07:00:00Z',
      received_at: '2026-10-02T07:00:00Z',
      revision: 1,
      payload,
    })
    expect(result.payload).toBe(payload)
    expect(JSON.stringify(result.payload)).toBe(JSON.stringify(payload))
    expect([...archiveFields(result.payload)].map((field) => field.path)).toEqual([
      '$.__proto__.synthetic_marker',
      '$.detail.__proto__.synthetic_marker',
      '$.detail.constructor',
    ])
    expect(Object.getPrototypeOf(result.payload)).toBe(Object.prototype)
    expect(Object.hasOwn(Object.prototype, 'synthetic_marker')).toBe(false)
    const invalid: Record<string, unknown> = JSON.parse('{"__proto__":{"value":0}}')
    const child = invalid.__proto__ as Record<string, unknown>
    child.value = Infinity
    expect(SourceAnalysis.safeParse({ ...result, payload: invalid }).success).toBe(false)
    child.value = new Date()
    expect(SourceAnalysis.safeParse({ ...result, payload: invalid }).success).toBe(false)
    child.value = invalid
    expect(SourceAnalysis.safeParse({ ...result, payload: invalid }).success).toBe(false)
  })

  it('searches translated ancestor names, raw paths and complete values', async () => {
    const payload = {
      detail: { total_weight: { changed_format: 'synthetic' }, note: 'far-away needle' },
    }
    const options = {
      signal: signal(),
      label: (key: string) => (key === 'total_weight' ? 'Вес' : key),
    }
    expect((await searchArchive(payload, { ...options, query: 'вес' })).rows[0]?.path).toBe(
      '$.detail.total_weight.changed_format',
    )
    expect((await searchArchive(payload, { ...options, query: 'WEIGHT' })).total).toBe(1)
    expect((await searchArchive(payload, { ...options, query: 'needle' })).rows[0]?.value).toBe(
      'far-away needle',
    )
    expect((await searchArchive(payload, { ...options, query: 'absent' })).total).toBe(0)
  })

  it('keeps one bounded page while yielding during a large archive search', async () => {
    const payload = { detail: Array.from({ length: 1100 }, (_, index) => ({ value: index })) }
    const yieldControl = vi.fn(async () => {})
    const result = await searchArchive(payload, {
      signal: signal(),
      page: 2,
      pageSize: 40,
      yieldControl,
    })
    expect(result.total).toBe(1100)
    expect(result.rows).toHaveLength(40)
    expect(result.rows[0]?.value).toBe(80)
    expect(result.rows.at(-1)?.value).toBe(119)
    expect(yieldControl).toHaveBeenCalledTimes(4)
  })

  it('abandons work on a superseded query before scanning all fields', async () => {
    const controller = new AbortController()
    const yieldControl = vi.fn(async () => controller.abort())
    await expect(
      searchArchive(
        { values: Array.from({ length: 1000 }, (_, index) => index) },
        {
          signal: controller.signal,
          yieldControl,
        },
      ),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(yieldControl).toHaveBeenCalledTimes(1)
  })

  it('cancels a default native continuation when an archive query is superseded', async () => {
    const controller = new AbortController()
    const nativeYield = vi.fn(async () => controller.abort())
    vi.stubGlobal('scheduler', { yield: nativeYield })
    await expect(
      searchArchive(
        { values: Array.from({ length: 1000 }, (_, index) => index) },
        { signal: controller.signal },
      ),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(nativeYield).toHaveBeenCalledOnce()
  })
})
