import { mount, RouterLinkStub } from '@vue/test-utils'
import { ref, type Ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { History, Metric, Observation, type HistoryKind } from './api'
import MetricDetails from './MetricDetails.vue'

const historyData = ref<History>()
const queryPending = ref(false)
const queryError = ref(false)
const request = vi.fn()
type DetailRange = Ref<{ from: string; to: string; kind: HistoryKind }>
vi.mock('./usePersonal', () => ({
  useHistory: (range: DetailRange, enabled: Ref<boolean>) => {
    request(range, enabled)
    return {
      data: historyData,
      isPending: queryPending,
      isLoadingError: queryError,
      isRefetchError: ref(false),
      refetch: vi.fn(),
    }
  },
}))

const original = Observation.parse({
  id: 'sleep-record',
  kind: 'sleep',
  start_at: '2026-10-04T21:00:00Z',
  end_at: '2026-10-05T06:00:00Z',
  timezone: 'UTC',
  method: 'device_report',
  data: { value: null },
  source_name: 'Кольцо',
  revision: 17,
  override_revision: 3,
})
const historyLink = {
  name: 'personal-history',
  query: { kind: 'sleep', end: '2026-10-05' },
}
function metric(records?: Observation[]) {
  return Metric.parse({ kind: 'sleep', status: 'available', value: null, unit: 'min', records })
}
function show(value: Metric, canWrite = true) {
  return mount(MetricDetails, {
    props: {
      metric: value,
      day: '2026-10-05',
      today: '2026-10-08',
      timezone: 'UTC',
      canWrite,
      historyLink,
    },
    global: {
      plugins: [i18n],
      stubs: { RouterLink: RouterLinkStub, MetricCard: true, SourceLine: true },
    },
  })
}
beforeEach(() => {
  request.mockClear()
  queryPending.value = false
  queryError.value = false
  historyData.value = History.parse({
    from: '2026-10-05',
    to: '2026-10-05',
    records: [],
    daily: [],
  })
})

describe('daily metric observations', () => {
  it('edits unknown-duration sleep with its original value and revisions', async () => {
    const wrapper = show(metric([original]))
    expect(request.mock.calls[0]![1].value).toBe(false)
    expect(wrapper.get('[data-testid="metric-record-sleep-record"]').text()).toContain(
      'Значение не передано',
    )
    await wrapper.get('[data-testid="metric-record-correct-sleep-record"]').trigger('click')
    expect(wrapper.emitted('edit')).toEqual([[original]])
  })

  it('requests observations for the selected day and kind when absent from overview', () => {
    historyData.value!.records = [original]
    const wrapper = show(metric([]))
    const [range, enabled] = request.mock.calls[0]!
    expect(range.value).toEqual({ from: '2026-10-05', to: '2026-10-05', kind: 'sleep' })
    expect(enabled.value).toBe(true)
    expect(wrapper.get('[data-testid="metric-record-sleep-record"]').text()).toContain('Кольцо')
  })

  it('keeps samples, aggregates and unmatched refs out of correction targets', () => {
    const value = Metric.parse({
      ...metric([]),
      resolution: {
        status: 'available',
        fingerprint: 'synthetic',
        resolver_version: '1',
        selected: [
          { id: 'sample', type: 'sample', contribution: '420', revision: 1 },
          { id: 'total', type: 'manual_day_total', contribution: '420', revision: 2 },
          { id: 'unmatched-record', type: 'record', contribution: '420', revision: 3 },
        ],
      },
    })
    const wrapper = show(value)
    expect(wrapper.findAll('[data-testid^="metric-record-correct-"]')).toHaveLength(0)
    expect(wrapper.text()).toContain('Отдельных записей за этот день нет')
    expect(wrapper.find('[data-testid="metric-detail-history"]').exists()).toBe(true)
  })

  it('refreshes record details without replacing an emitted correction snapshot', async () => {
    const wrapper = show(metric([original]))
    await wrapper.get('[data-testid="metric-record-correct-sleep-record"]').trigger('click')
    const updated = { ...original, data: { value: '420' }, revision: 18 }
    await wrapper.setProps({ metric: metric([updated]) })
    expect(wrapper.get('[data-testid="metric-record-sleep-record"]').text()).toContain('7 ч 0 мин')
    expect(wrapper.emitted('edit')).toEqual([[original]])
  })

  it('keeps the dated history link and capture event in read-only mode', async () => {
    const wrapper = show(metric([original]), false)
    expect(wrapper.findAll('[data-testid^="metric-record-correct-"]')).toHaveLength(0)
    const link = wrapper.getComponent(RouterLinkStub)
    expect(link.props('to')).toEqual(historyLink)
    await link.trigger('click')
    expect(wrapper.emitted('history')).toEqual([[historyLink]])
  })
})

describe('where the result came from', () => {
  it('passes the decision of the shown day to one source line', () => {
    const contributions = [{ source_id: 'ring', count: 3, spans: [] }]
    const wrapper = show(
      Metric.parse({
        kind: 'sleep',
        status: 'available',
        value: '420',
        unit: 'min',
        resolution: { status: 'available', fingerprint: 'f', resolver_version: '1', contributions },
      }),
    )
    expect(wrapper.findAllComponents({ name: 'SourceLine' })).toHaveLength(1)
    expect(wrapper.getComponent({ name: 'SourceLine' }).props()).toEqual({
      metric: 'sleep',
      contributions,
      day: '2026-10-05',
    })
  })
})
