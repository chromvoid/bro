import { mount, RouterLinkStub } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { History, Metric, Observation } from './api'
import DashboardWeight from './DashboardWeight.vue'

const weightHistory = ref<History>()
const bodyHistory = ref<History>()
vi.mock('./usePersonal', () => ({
  useHistory: (range: { value: { kind: string } }) => ({
    data: range.value.kind === 'weight' ? weightHistory : bodyHistory,
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    isFetching: ref(false),
    refetch: vi.fn(),
  }),
}))

function observation(id: string, revision: number, hour: string) {
  return Observation.parse({
    id,
    kind: 'weight',
    source_id: id,
    source_name: `Источник ${id}`,
    start_at: `2026-10-05T${hour}:00:00Z`,
    end_at: `2026-10-05T${hour}:00:00Z`,
    timezone: 'UTC',
    method: 'device_report',
    data: { value: '75' },
    revision,
    override_revision: revision + 1,
  })
}
const first = observation('first', 21, '08')
const second = observation('second', 23, '09')
const historyLink = {
  name: 'personal-history',
  query: { kind: 'weight', end: '2026-10-05', returnDay: '2026-10-05', returnSection: 'weight' },
}

function show(canWrite = true, weight: Record<string, unknown> = {}) {
  return mount(DashboardWeight, {
    props: {
      day: '2026-10-05',
      today: '2026-10-08',
      timezone: 'UTC',
      canWrite,
      weight: Metric.parse({
        kind: 'weight',
        status: 'available',
        value: '75',
        unit: 'kg',
        records: [second],
        ...weight,
      }),
      historyLink: (period) => ({ ...historyLink, query: { ...historyLink.query, period } }),
    },
    global: {
      plugins: [i18n],
      stubs: {
        RouterLink: RouterLinkStub,
        BodySourceDetails: true,
        SourceLine: true,
        Panel: { props: ['open'], template: '<aside v-if="open"><slot /></aside>' },
      },
    },
  })
}

beforeEach(() => {
  weightHistory.value = History.parse({
    from: '2026-07-08',
    to: '2026-10-05',
    records: [first, second],
    daily: [],
  })
  bodyHistory.value = History.parse({
    from: '2026-07-08',
    to: '2026-10-05',
    records: [],
    daily: [],
  })
})

describe('weight details from the dashboard', () => {
  it('corrects the chosen equal-value record from the weight number', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="weight-details-open"]').trigger('click')
    expect(wrapper.findAll('[data-testid^="weight-record-correct-"]')).toHaveLength(2)
    await wrapper.get('[data-testid="weight-record-correct-first"]').trigger('click')
    expect(wrapper.emitted('edit')).toEqual([[first]])
    expect(wrapper.find('[data-testid="dashboard-weight-records"]').exists()).toBe(false)
    await wrapper.setProps({ editingRecordId: first.id })
    await wrapper.setProps({ editingRecordId: null })
    expect(wrapper.get('[data-testid="weight-record-first"]').text()).toContain('Источник first')
    expect(wrapper.get('[data-testid="weight-record-second"]').text()).toContain('Источник second')
  })

  it('keeps provenance visible without correction controls in read-only mode', async () => {
    const wrapper = show(false)
    await wrapper.get('[data-testid="weight-details-open"]').trigger('click')
    expect(wrapper.findAll('[data-testid^="weight-record-correct-"]')).toHaveLength(0)
    expect(wrapper.get('[data-testid="weight-record-first"]').text()).toContain('75 кг')
  })

  it('captures the history link with the active graph period', async () => {
    const wrapper = show()
    const sevenDays = wrapper.findAll('button').find((button) => button.text() === '7 д')!
    await sevenDays.trigger('click')
    await wrapper.get('[data-testid="weight-details-open"]').trigger('click')
    const link = wrapper
      .findAllComponents(RouterLinkStub)
      .find((item) => item.text() === 'Вся история')!
    await link.trigger('click')
    expect(link.props('to')).toEqual({
      ...historyLink,
      query: { ...historyLink.query, period: 7 },
    })
    expect(wrapper.emitted('history')).toEqual([[link.props('to')]])
  })

  it('opens the existing weight action beside the number and respects write access', async () => {
    const writer = show()
    await writer.get('[data-testid="weight-add-mobile"]').trigger('click')
    expect(writer.emitted('add-weight')).toEqual([[]])
    expect(writer.find('[data-testid="weight-details-open"]').exists()).toBe(true)
    expect(show(false).find('[data-testid="weight-add-mobile"]').exists()).toBe(false)
  })

  it('names where the weight came from in one line; its details keep only the measurements', async () => {
    const contributions = [{ source_id: 'second', count: 1, spans: [] }]
    const wrapper = show(true, {
      resolution: { status: 'available', fingerprint: 'f', resolver_version: '1', contributions },
    })
    expect(wrapper.getComponent({ name: 'SourceLine' }).props()).toEqual({
      metric: 'weight',
      contributions,
      day: '2026-10-05',
    })
    await wrapper.get('[data-testid="weight-details-open"]').trigger('click')
    expect(wrapper.findAllComponents(RouterLinkStub).map((link) => link.text())).toEqual([
      'Вся история',
      'Вся история',
    ])
    expect(wrapper.get('[data-testid="weight-record-second"]').text()).toContain('Источник second')
  })

  it('states a conflicting weight without offering a choice', () => {
    const wrapper = show(true, { status: 'conflict', value: null, records: [first, second] })
    expect(wrapper.get('[data-testid="weight-card"]').text()).toContain('Показания не согласованы')
    expect(wrapper.text()).not.toContain('Источники расходятся')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain(
      'Выбрать источник',
    )
  })
})
