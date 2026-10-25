import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import { dayIn, momentLabel } from '@/core/dates'
import { Rings } from '@/sections/personal/sensors'
import CollectorDetails from './CollectorDetails.vue'

const transport = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
const canWrite = ref(true)
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'owner', timezone: 'UTC' },
    can: (scope: string) =>
      scope === 'personal.read' || (scope === 'personal.write' && canWrite.value),
  }),
}))
type Collector = ReturnType<typeof Rings.parse>['rings'][number]
type SavedMetric = NonNullable<Collector['metrics']>[number]
const savedMetric = (metric: string): SavedMetric => ({
  metric,
  status: 'missing',
  last_measured_at: null,
  last_received_at: '2026-10-10T12:00:00Z',
  coverage: { status: 'unknown', shape: 'unknown', start_at: null, end_at: null, quality: [] },
  import_status: 'connected',
  reason: 'no_observation',
})
/** A collector as the server lists it, read through the same schema as the page reads it. */
const savedRing = (metrics: SavedMetric[], fields: Record<string, unknown> = {}): Collector =>
  Rings.parse({
    rings: [
      {
        collector_id: 'collector',
        source_id: 'source',
        name: 'Synthetic ring',
        profile: 'qring-r20',
        generation: 1,
        enabled: true,
        last_received_at: '2026-10-10T12:00:00Z',
        cursor: null,
        error: null,
        status: 'connected',
        metrics,
        ...fields,
      },
    ],
  }).rings[0]!
let client: QueryClient
function show(collector: Collector) {
  return mount(CollectorDetails, {
    props: { collector },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  canWrite.value = true
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  transport.post.mockResolvedValue({ data: {} })
})
describe('collector import details', () => {
  it.each([
    ['point', 'Разовое значение'],
    ['interval_mean', 'Среднее за интервал'],
    ['sample_window_mean', 'Среднее по выборке'],
    ['range', 'Диапазон'],
    ['sleep_report', 'Отчёт о сне'],
    ['reported_unknown', 'Форма показания не подтверждена'],
    ['future_shape', 'future_shape'],
  ])('explains the %s reading shape without qualifying its saved data', async (shape, label) => {
    const wrapper = show(
      savedRing([
        {
          ...savedMetric('heart_rate'),
          status: 'unverified',
          reason: 'semantic_unknown',
          coverage: {
            ...savedMetric('heart_rate').coverage,
            shape,
            quality: ['protocol_unverified'],
          },
        },
      ]),
    )
    await flushPromises()
    const row = wrapper.get('[data-testid="ring-import-metric-heart_rate"]')
    expect(row.text()).toContain(`Форма показания: ${label}`)
    expect(row.text()).toContain('Смысл показания ещё не подтверждён')
    expect(row.get('[data-testid="record-quality-codes"]').text()).toContain('protocol_unverified')
    if (shape !== 'future_shape') expect(row.text()).not.toContain(shape)
    wrapper.unmount()
  })
  it('explains saved coverage flags without qualifying the source or hiding unknown codes', async () => {
    const flags = ['protocol_unverified', 'hrv_statistic_unknown', 'future_quality']
    const wrapper = show(
      savedRing([
        {
          ...savedMetric('hrv'),
          status: 'unverified',
          reason: 'semantic_unknown',
          coverage: { ...savedMetric('hrv').coverage, quality: flags },
        },
      ]),
    )
    await flushPromises()
    const row = wrapper.get('[data-testid="ring-import-metric-hrv"]')
    expect(row.text()).toContain('Смысл показания ещё не подтверждён')
    const explanations = row.get('[data-testid="record-quality-explanations"]')
    expect(explanations.text()).toContain('Формат данных этого источника ещё не сверен.')
    expect(explanations.text()).toContain('Определение показателя HRV')
    expect(explanations.text()).toContain(
      'Смысл дополнительной отметки качества ещё не подтверждён',
    )
    expect(explanations.text()).not.toContain('protocol_unverified')
    expect(
      row
        .get('[data-testid="record-quality-codes"]')
        .findAll('code')
        .map((code) => code.text()),
    ).toEqual(flags)
    wrapper.unmount()
  })
  it('shows source-bound capture outcomes for all 13 metrics and separates their scope and context', async () => {
    const cases = [
      ['steps', 'day', 'background', 'complete'],
      ['sleep', 'history', 'background', 'empty'],
      ['heart_rate', 'spot', 'manual', 'complete'],
      ['spo2', 'history', 'background', 'complete'],
      ['temperature', 'live', 'background', 'failed'],
      ['hrv', 'spot', 'manual', 'complete'],
      ['stress', 'spot', 'manual', 'complete'],
      ['active_energy', 'day', 'background', 'complete'],
      ['active_minutes', 'workout', 'running', 'complete'],
      ['distance', 'day', 'background', 'complete'],
      ['running_steps', 'live', 'running', 'complete'],
      ['running_workouts', 'workout', 'recovery', 'complete'],
      ['workouts', 'workout', 'workout', 'failed'],
    ] as const
    const capturedAt = '2026-10-08T10:00:00Z'
    const wrapper = show(
      savedRing(
        cases.map(([metric, scope, context, status]) => ({
          ...savedMetric(metric),
          last_capture_at: capturedAt,
          capture_status: status,
          capture_scope: scope,
          capture_context: context,
          capture_date: '2026-10-07',
          ...(status === 'failed'
            ? { capture_error_code: metric === 'workouts' ? 'synthetic_device_error' : 'timeout' }
            : {}),
        })),
      ),
    )
    await flushPromises()
    for (const [metric, , , status] of cases) {
      const row = wrapper.get(`[data-testid="ring-import-metric-${metric}"]`)
      expect(row.text()).toContain('Последнее чтение')
      expect(row.text()).toContain(momentLabel(capturedAt, 'UTC', dayIn('UTC')))
      expect(row.text()).toContain('Дата данных: 2026-10-07')
      expect(row.text()).toContain(
        status === 'complete'
          ? 'Чтение завершено'
          : status === 'empty'
            ? 'Чтение завершено без данных'
            : 'Чтение не удалось',
      )
      expect(row.text()).toContain('Импорт подключён')
    }
    const manual = wrapper.get('[data-testid="ring-import-metric-heart_rate"]')
    expect(manual.text()).toContain('Область чтения: Разовый замер')
    expect(manual.text()).toContain('Режим чтения: Ручной замер')
    expect(manual.text()).toContain('Данных нет')
    expect(manual.text()).not.toContain('spot')
    expect(manual.text()).not.toContain('manual')
    expect(wrapper.get('[data-testid="ring-import-metric-sleep"]').text()).toContain(
      'Область чтения: История',
    )
    expect(wrapper.get('[data-testid="ring-import-metric-active_minutes"]').text()).toContain(
      'Режим чтения: Бег',
    )
    expect(wrapper.get('[data-testid="ring-import-metric-running_workouts"]').text()).toContain(
      'Режим чтения: Восстановление',
    )
    expect(wrapper.get('[data-testid="ring-import-metric-workouts"]').text()).toContain(
      'Не удалось завершить чтение показателя.',
    )
    expect(wrapper.text()).not.toContain('synthetic_device_error')
    expect(wrapper.get('[data-testid="ring-import-metric-temperature"]').text()).toContain(
      'Кольцо не ответило',
    )
    expect(wrapper.text()).toContain('не подтверждает полноту истории или сверку формата')
    expect(wrapper.find('[style]').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each([
    ['response_timeout', 'Чтение не завершилось вовремя.'],
    ['write_failed', 'Не удалось передать запрос кольцу.'],
    ['running_ambiguous_time', 'Не удалось установить время данных активности.'],
    ['invalid_running_snapshot', 'Ответ с данными активности не прошёл проверку.'],
    ['invalid_legacy_hr_context', 'Не удалось определить период чтения пульса.'],
    ['query_failed', 'Не удалось завершить чтение показателя.'],
    ['future_failure', 'Не удалось завершить чтение показателя.'],
  ])('shows %s without hiding saved readings', async (code, label) => {
    const capturedAt = '2026-10-08T10:00:00Z'
    const measuredAt = '2026-10-07T09:00:00Z'
    const wrapper = show(
      savedRing([
        {
          ...savedMetric('workouts'),
          status: 'unverified',
          reason: 'protocol_unverified',
          last_measured_at: measuredAt,
          last_capture_at: capturedAt,
          capture_status: 'failed',
          capture_scope: 'history',
          capture_context: 'background',
          capture_error_code: code,
          coverage: {
            status: 'partial',
            shape: 'workout_report',
            start_at: measuredAt,
            end_at: measuredAt,
            quality: ['protocol_unverified'],
          },
        },
        {
          ...savedMetric('heart_rate'),
          status: 'available',
          reason: null,
          last_measured_at: measuredAt,
          last_capture_at: capturedAt,
          capture_status: 'complete',
          capture_scope: 'day',
          capture_context: 'background',
        },
      ]),
    )
    await flushPromises()
    const failed = wrapper.get('[data-testid="ring-import-metric-workouts"]')
    expect(failed.text()).toContain(label)
    expect(failed.text()).not.toContain(code)
    expect(failed.text()).toContain('Чтение не удалось')
    expect(failed.text()).toContain('Формат не подтверждён')
    expect(failed.text()).not.toContain('Не поддерживается')
    expect(failed.text()).toContain('Область чтения: История')
    expect(failed.text()).toContain('Режим чтения: Фоновое чтение')
    expect(failed.text()).toContain(
      'Последнее чтение: ' + momentLabel(capturedAt, 'UTC', dayIn('UTC')),
    )
    expect(failed.text()).toContain(
      'Последнее показание: ' + momentLabel(measuredAt, 'UTC', dayIn('UTC')),
    )
    expect(failed.text()).toContain('Неполное покрытие')
    const saved = wrapper.get('[data-testid="ring-import-metric-heart_rate"]')
    expect(saved.text()).toContain('Данные поступают')
    expect(saved.text()).toContain('Чтение завершено')
    expect(saved.text()).not.toContain(label)
    expect(saved.text()).toContain(
      'Последнее показание: ' + momentLabel(measuredAt, 'UTC', dayIn('UTC')),
    )
    expect(wrapper.find('[style]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('omits absent capture metadata and does not turn a missing outcome into a failed read', async () => {
    const wrapper = show(
      savedRing([
        savedMetric('sleep'),
        {
          ...savedMetric('steps'),
          last_capture_at: '2026-10-08T10:00:00Z',
          capture_status: null,
        },
      ]),
    )
    await flushPromises()
    const absent = wrapper.get('[data-testid="ring-import-metric-sleep"]')
    expect(absent.text()).not.toContain('Последнее чтение')
    expect(absent.text()).not.toContain('Область чтения')
    expect(absent.text()).not.toContain('Режим чтения')
    const legacy = wrapper.get('[data-testid="ring-import-metric-steps"]')
    expect(legacy.text()).toContain('Результат чтения неизвестен')
    expect(legacy.text()).not.toContain('Чтение не удалось')
    expect(legacy.text()).not.toContain('Область чтения')
    expect(legacy.text()).not.toContain('Режим чтения')
    wrapper.unmount()
  })
  it('leads from raw device reports to their original history', async () => {
    const wrapper = show(
      savedRing([
        {
          ...savedMetric('active_minutes'),
          status: 'unverified',
          raw_report_count: 2,
          original_kind: 'running_steps',
        },
      ]),
    )
    await flushPromises()
    const row = wrapper.get('[data-testid="ring-import-metric-active_minutes"]')
    expect(row.text()).toContain('Исходных отчётов: 2.')
    expect(wrapper.getComponent(RouterLinkStub).props('to')).toEqual({
      name: 'personal-history',
      query: { kind: 'running_steps', period: 7 },
    })
    wrapper.unmount()
  })
  it('tells a ring problem and its saved state apart from a strap problem', async () => {
    const ring = show(
      savedRing([], {
        error: 'timeout',
        device_state: {
          observed_at: '2026-10-06T12:00:00Z',
          received_at: '2026-10-06T12:00:01Z',
          revision: 3,
          stale: false,
          battery_percent: 40,
        },
      }),
    )
    await flushPromises()
    expect(ring.text()).toContain(
      'Последняя доставка: ' + momentLabel('2026-10-10T12:00:00Z', 'UTC', dayIn('UTC')),
    )
    expect(ring.text()).toContain('Кольцо не ответило')
    expect(ring.find('[data-testid="stored-ring-state"]').exists()).toBe(true)
    expect(ring.find('[data-testid="ring-import-metric-status"]').exists()).toBe(false)
    ring.unmount()
    const strap = show(
      savedRing([], {
        name: 'Synthetic strap',
        profile: 'whoop-5',
        error: 'timeout',
        last_received_at: null,
        device_state: {
          observed_at: '2026-10-06T12:00:00Z',
          received_at: '2026-10-06T12:00:01Z',
          revision: 1,
          stale: false,
        },
      }),
    )
    await flushPromises()
    expect(strap.text()).toContain('Последняя доставка: Ещё не было')
    expect(strap.text()).toContain('Браслет не ответил вовремя')
    expect(strap.text()).not.toContain('Кольцо')
    expect(strap.find('[data-testid="stored-ring-state"]').exists()).toBe(false)
    strap.unmount()
  })
  it('switches off another device import and refreshes personal data', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const wrapper = show(savedRing([], { collector_id: 'collector/1' }))
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(transport.post).toHaveBeenCalledWith(
      '/v1/personal/rings/collector%2F1/disable',
      {},
      { expectedUser: 'owner' },
    )
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['personal'] })
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('offers no switch for a disabled import or a reader', async () => {
    const disabled = show(savedRing([], { enabled: false, status: 'disabled' }))
    expect(disabled.find('button').exists()).toBe(false)
    disabled.unmount()
    canWrite.value = false
    const reader = show(savedRing([]))
    expect(reader.find('button').exists()).toBe(false)
    reader.unmount()
    expect(transport.post).not.toHaveBeenCalled()
  })
})
