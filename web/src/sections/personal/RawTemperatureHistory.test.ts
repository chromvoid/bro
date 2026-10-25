import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RawTemperatureHistory from './RawTemperatureHistory.vue'
import { RawRingReport } from './rawReports'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner' }, can: (scope: string) => scope === 'personal.read' }),
}))
const raw = (revision: number) => ({
  id: 'raw',
  user_id: 'owner',
  source_id: 'ring-a',
  source_name: 'Ring A',
  source_status: 'connected',
  external_id: 'bulk25',
  source_version: revision,
  revision,
  source_hash: 'synthetic-hash',
  received_at: '2026-10-09T08:00:00Z',
  metric: 'temperature',
  local_date: '2026-10-08',
  timezone: 'UTC',
  observed_at: '2026-10-08T23:59:00Z',
  namespace: 'bulk',
  opcode: 37,
  layout: 'unqualified',
  payload_hex: 'bc2501',
  details: {},
  origin: {},
  quality: ['layout_unconfirmed'],
  is_current: revision === 2,
  current_revision: 2,
  qualification: 'source_only',
  reason: 'ambiguous_layout',
  interpretation: {
    definition_id: 'soul-temperature-legacy25',
    formula_version: 'soul-1.0.1214-dex-z0-v1',
    layout: 'vendor_byte_div10_plus32',
    quality: ['source_transform', 'layout_unconfirmed'],
    status: 'source_only',
    input_refs: [{ id: 'raw', revision, source_id: 'ring-a', hash: 'synthetic-hash' }],
    interval_minutes: 5,
    day_start: '2026-10-09T00:00:00Z',
    as_of: '2026-10-09T08:00:00Z',
    plausible_counts: { vendor: 1, spot: 0 },
    candidate_counts: { vendor: 1, spot: 1 },
    points: [{ token_offset: 4, slot: 0, offset_minutes: 0, value: 34.5, unit: '°C' }],
    time_basis: 'phone_capture_local_midnight',
    ring_local_date: '2026-10-08',
    capture_local_date: '2026-10-09',
  },
})
const current = (revision: number) => {
  const legacy = raw(revision)
  return {
    ...legacy,
    interpretation: {
      ...legacy.interpretation,
      definition_id: 'soul1230-temperature-legacy25',
      formula_version: 'soul-1.0.1230-dex-B0-time-v1',
      time_basis: 'phone_sync_start_local_midnight',
      capture_local_date: '2026-10-08',
      parse_local_date: '2026-10-09',
      day_start: '2026-10-08T00:00:00+09:00',
      as_of: '2026-10-08T15:00:01+00:00',
      time_context: {
        version: 'bro-soul1230-temperature-capture-v1',
        sync_started_at: '2026-10-08T14:59:59+00:00',
        parsed_at: '2026-10-08T15:00:01+00:00',
        timezone: 'Asia/Tokyo',
        request_id: 'synthetic-request',
      },
      missing: [],
      trailing_bytes_ignored_by_soul: 2,
      legacy_capture_projection: legacy.interpretation,
    },
  }
}
const unavailable = (revision: number) => {
  const record = current(revision)
  return {
    ...record,
    interpretation: {
      ...record.interpretation,
      status: 'unavailable',
      points: [],
      missing: ['temperature_sync_start_context'],
      layout: null,
      interval_minutes: null,
      day_start: null,
      as_of: null,
      capture_local_date: null,
      parse_local_date: null,
      time_context: null,
      time_basis: 'unavailable',
      plausible_counts: null,
      candidate_counts: null,
      quality: ['source_transform', 'layout_unconfirmed', 'sync_time_context_missing'],
    },
  }
}
const mountedHistory = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(RawTemperatureHistory, {
    props: { from: '2026-10-08', to: '2026-10-09', timezone: 'UTC', today: '2026-10-09' },
    global: {
      plugins: [
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        [VueQueryPlugin, { queryClient: client }],
      ],
    },
  })
  return { wrapper, client }
}
beforeEach(() => {
  vi.clearAllMocks()
  transport.get.mockResolvedValue({
    from: '2026-10-08',
    to: '2026-10-09',
    items: [raw(1), raw(2)],
    count: 2,
    next_cursor: null,
    fingerprint: 'raw-history',
  })
})

describe('temperature source-only archive', () => {
  it('keeps versions, packet dates and Soul interpretation separate from measured temperature', async () => {
    const wrapper = mount(RawTemperatureHistory, {
      props: { from: '2026-10-08', to: '2026-10-09', timezone: 'UTC', today: '2026-10-09' },
      global: {
        plugins: [
          createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
          [
            VueQueryPlugin,
            { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
          ],
        ],
      },
    })
    await flushPromises()
    expect(wrapper.findAll('[data-testid="raw-temperature-version"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('Получено отчётов и версий за период: 2')
    expect(wrapper.text()).toContain('Архивное преобразование Soul 1.0.1214; формат не подтверждён')
    expect(wrapper.text()).toContain('а не подтверждённая измеренная температура')
    expect(wrapper.text()).toContain('2026-10-08')
    expect(wrapper.text()).toContain('2026-10-09')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
    const params = new URL(transport.get.mock.calls[0]![0], 'https://example.test').searchParams
    expect(params.get('metric')).toBe('temperature')
    wrapper.unmount()
  })

  it('shows Soul1230 sync and parse provenance across midnight separately from the profile date', async () => {
    const record = RawRingReport.parse(current(2))
    expect(record).not.toHaveProperty('value')
    expect(record).not.toHaveProperty('unit')
    transport.get.mockResolvedValue({
      from: '2026-10-08',
      to: '2026-10-09',
      items: [current(2)],
      count: 1,
      next_cursor: null,
      fingerprint: 'soul1230',
    })
    const { wrapper, client } = mountedHistory()
    await flushPromises()
    expect(wrapper.text()).toContain('Преобразование Soul 1.0.1230; формат не подтверждён')
    expect(wrapper.text()).toContain('полночь телефона в день начала запроса (2026-10-08)')
    expect(wrapper.text()).toContain('Часовой пояс разбора: Asia/Tokyo')
    expect(wrapper.text()).toContain('Начало запроса: 2026-10-08T14:59:59+00:00')
    expect(wrapper.text()).toContain('Разбор: 2026-10-08T15:00:01+00:00 (дата 2026-10-09)')
    expect(wrapper.find('[data-testid="soul1230-temperature-unavailable"]').exists()).toBe(false)
    expect(
      wrapper.find('[data-testid="soul1214-temperature-capture-projection"]').text(),
    ).toContain('Прежняя проекция Soul 1.0.1214 по получению пакета')
    wrapper.unmount()
    client.clear()
  })

  it('keeps old raw data honest when exact sync evidence is missing', async () => {
    const record = RawRingReport.parse(unavailable(1))
    expect(record.metric).toBe('temperature')
    transport.get.mockResolvedValue({
      from: '2026-10-08',
      to: '2026-10-09',
      items: [unavailable(1)],
      count: 1,
      next_cursor: null,
      fingerprint: 'missing-sync',
    })
    const { wrapper, client } = mountedHistory()
    await flushPromises()
    const missing = wrapper.find('[data-testid="soul1230-temperature-unavailable"]')
    expect(missing.text()).toContain('не сохранено время начала запроса температуры')
    expect(missing.text()).not.toContain('temperature_sync_start_context')
    expect(
      wrapper
        .find('[data-testid="soul-temperature-interpretation"] > details:last-child pre')
        .text(),
    ).toContain('temperature_sync_start_context')
    expect(wrapper.find('[data-testid="soul-temperature-interpretation"] > ul').exists()).toBe(
      false,
    )
    expect(
      wrapper.find('[data-testid="soul1214-temperature-capture-projection"]').text(),
    ).toContain('soul-1.0.1214-dex-z0-v1')
    expect(
      RawRingReport.safeParse({
        ...unavailable(1),
        interpretation: {
          ...unavailable(1).interpretation,
          points: [{ token_offset: 0, slot: 0, offset_minutes: 0, value: 34, unit: '°C' }],
        },
      }).success,
    ).toBe(false)
    wrapper.unmount()
    client.clear()
  })
})
