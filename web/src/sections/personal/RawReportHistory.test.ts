import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import { RawReportPage, RawRingReport } from './rawReports'
import RawReportHistory from './RawReportHistory.vue'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner' }, can: (scope: string) => scope === 'personal.read' }),
}))
const raw = (revision: number) => ({
  id: 'workout-raw',
  user_id: 'owner',
  source_id: 'ring-a',
  source_name: 'Ring A',
  source_status: 'connected',
  external_id: 'core13',
  source_version: revision,
  revision,
  source_hash: 'synthetic-hash',
  received_at: '2026-10-09T08:00:00Z',
  metric: 'workouts',
  local_date: '2026-10-09',
  timezone: 'UTC',
  observed_at: '2026-10-09T08:00:00Z',
  namespace: 'core',
  opcode: 19,
  layout: 'oudmon-core-13-device-workout-v1',
  payload_hex: '010203',
  details: {
    type: 'raw_device_workout',
    date_basis: 'phone_capture_local_date',
    raw_start_time: 123,
    duration_raw: 25,
    sport_type_raw: 7,
    steps_raw: 11,
    distance_raw: 250,
    calories_raw: 50,
    heart_rate_count: 2,
    heart_rate_bytes: [90, 95],
  },
  origin: { hardware: 'synthetic', firmware: 'synthetic' },
  quality: ['time_basis_unconfirmed'],
  is_current: revision === 2,
  current_revision: 2,
  qualification: 'source_only',
  reason: 'workout_time_basis_unconfirmed',
  time_basis: 'first_phone_capture_day',
  interpretation: null,
  interpretation_reason: 'source_time_and_units_unconfirmed',
})
beforeEach(() => {
  vi.clearAllMocks()
  transport.get.mockResolvedValue({
    from: '2026-10-08',
    to: '2026-10-09',
    items: [raw(1), raw(2)],
    count: 2,
    next_cursor: null,
    fingerprint: 'workout-raw-history',
  })
})

describe('ring workout source-only archive', () => {
  it('preserves raw versions without fabricating session bounds, quantities or a phone plan', async () => {
    const record = RawRingReport.parse(raw(1))
    expect(record).not.toHaveProperty('start_at')
    expect(record).not.toHaveProperty('end_at')
    expect(record).not.toHaveProperty('value')
    expect(record).not.toHaveProperty('sport_plan')
    expect(
      RawReportPage.parse({
        from: '2026-10-08',
        to: '2026-10-09',
        items: [raw(1)],
        count: 2,
        next_cursor: 'next',
        fingerprint: 'f',
      }).items[0]?.details,
    ).toEqual(raw(1).details)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(RawReportHistory, {
      props: {
        metric: 'workouts',
        from: '2026-10-08',
        to: '2026-10-09',
        timezone: 'UTC',
        today: '2026-10-09',
      },
      global: {
        plugins: [
          createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
          [VueQueryPlugin, { queryClient: client }],
        ],
      },
    })
    await flushPromises()
    expect(wrapper.findAll('[data-testid="raw-workout-version"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('Дата получения на телефоне: 2026-10-09')
    expect(wrapper.text()).toContain('Время и единицы полей источника не подтверждены')
    expect(wrapper.text()).toContain('"duration_raw": 25')
    expect(wrapper.find('[data-testid="soul-temperature-interpretation"]').exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(false)
    const params = new URL(transport.get.mock.calls[0]![0], 'https://example.test').searchParams
    expect(params.get('metric')).toBe('workouts')
    wrapper.unmount()
    client.clear()
  })
})

describe('old temperature capture with invalid time proof', () => {
  it('preserves exact raw and legacy JSON while explicitly declining the current projection', async () => {
    const { time_basis: timeBasis, ...base } = raw(1)
    void timeBasis
    const reference = {
      id: 'old-temperature-raw',
      revision: 1,
      source_id: 'ring-a',
      hash: 'synthetic-old-full-context-hash',
    }
    const legacy = {
      definition_id: 'soul-temperature-legacy25',
      formula_version: 'soul-1.0.1214-dex-z0-v1',
      layout: 'vendor_byte_div10_plus32',
      quality: ['source_transform', 'layout_unconfirmed'],
      status: 'empty',
      input_refs: [reference],
      interval_minutes: 30,
      day_start: '2026-10-09T00:00:00+00:00',
      as_of: '2026-10-09T08:00:00+00:00',
      plausible_counts: { vendor: 0, spot: 0 },
      candidate_counts: { vendor: 0, spot: 0 },
      points: [],
      time_basis: 'phone_capture_local_midnight',
      ring_local_date: '2026-10-09',
      capture_local_date: '2026-10-09',
      trailing_bytes_ignored_by_soul: 2,
    }
    const captured = {
      ...base,
      id: reference.id,
      external_id: 'old-bulk25',
      source_hash: reference.hash,
      metric: 'temperature',
      namespace: 'bulk',
      opcode: 37,
      layout: 'oudmon-temperature-25-ambiguous',
      payload_hex: 'bc2504008a82001e6481',
      details: { soul_time_context: { version: 'unknown-old-format', sync_started_at: 'invalid' } },
      quality: ['protocol_unverified', 'ambiguous_layout'],
      reason: 'ambiguous_layout',
      interpretation_reason: null,
      interpretation: {
        definition_id: 'soul1230-temperature-legacy25',
        formula_version: 'soul-1.0.1230-dex-B0-time-v1',
        layout: null,
        quality: ['source_transform', 'layout_unconfirmed', 'sync_time_context_invalid'],
        status: 'unavailable',
        missing: ['temperature_sync_start_context_invalid'],
        input_refs: [reference],
        interval_minutes: null,
        day_start: null,
        as_of: null,
        capture_local_date: null,
        parse_local_date: null,
        time_context: null,
        time_basis: 'unavailable',
        points: [],
        plausible_counts: null,
        candidate_counts: null,
        trailing_bytes_ignored_by_soul: 2,
        ring_local_date: '2026-10-09',
        legacy_capture_projection: legacy,
      },
    }
    const original = JSON.stringify(captured)
    expect(RawRingReport.parse(captured)).toEqual(captured)
    for (const missing of [
      [],
      ['unknown-context-state'],
      captured.interpretation.missing.concat('temperature_sync_start_context'),
    ])
      expect(
        RawRingReport.safeParse({
          ...captured,
          interpretation: { ...captured.interpretation, missing },
        }).success,
      ).toBe(false)
    transport.get.mockResolvedValue({
      from: '2026-10-08',
      to: '2026-10-09',
      items: [captured],
      count: 1,
      next_cursor: null,
      fingerprint: 'invalid-old-capture',
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(RawReportHistory, {
      props: {
        metric: 'temperature',
        from: '2026-10-08',
        to: '2026-10-09',
        timezone: 'UTC',
        today: '2026-10-09',
      },
      global: {
        plugins: [
          createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
          [VueQueryPlugin, { queryClient: client }],
        ],
      },
    })
    await flushPromises()
    const projection = wrapper.get('[data-testid="soul-temperature-interpretation"]')
    expect(projection.text()).toContain('повреждены или имеют неизвестный формат')
    expect(projection.text()).not.toContain('не сохранено время начала запроса')
    expect(projection.findAll('ul li')).toHaveLength(0)
    expect(wrapper.get('[data-testid="soul1214-temperature-capture-projection"]').text()).toContain(
      reference.hash,
    )
    expect(wrapper.get('[data-testid="raw-temperature-version"]').text()).toContain(
      captured.payload_hex,
    )
    expect(wrapper.text()).toContain('unknown-old-format')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(JSON.stringify(captured)).toBe(original)
    wrapper.unmount()
    client.clear()
  })
})
