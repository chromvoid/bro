import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { ApiError } from '@/core/http'
import ActivityReportHistory from './ActivityReportHistory.vue'
import type { ActivityReportFilter, ActivityReportPage } from './activityReports'

const transport = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  writable: true,
}))
vi.mock('@/core/client', () => ({ api: transport }))
const account = reactive({
  me: { id: 'owner' } as { id: string } | null,
  readable: true,
})
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return account.me
    },
    can: (scope: string) =>
      scope === 'personal.read'
        ? account.readable
        : scope === 'personal.write' && transport.writable,
  }),
}))
vi.mock('@/sections/personal/usePersonal', () => ({
  usePersonal: () => ({
    operate: transport.operate,
    failed: transport.failed,
    unresolved: { value: [] },
    describe: vi.fn(),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
const report = (revision: number) => ({
  id: 'report',
  source_id: 'ring-a',
  source_name: 'Ring A',
  revision,
  source_version: revision,
  kind: 'running_steps',
  shape: 'day_counter_snapshot',
  local_date: '2026-10-08',
  timezone: 'UTC',
  observed_at: '2026-10-08T12:00:00Z',
  start_at: null,
  end_at: null,
  data: { value: '100' },
  origin: {},
  quality: [],
  is_current: revision === 3,
  current_revision: 3,
  annotation_revision: 2,
  excluded: true,
  resolution_disposition: 'excluded',
})
type HistoryProps = {
  from: string
  to: string
  timezone: string
  today: string
  kind?: ActivityReportFilter
}
function queryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } })
}
function screen(props: Partial<HistoryProps> = {}, client = queryClient()) {
  return mount(ActivityReportHistory, {
    props: {
      from: '2026-10-08',
      to: '2026-10-09',
      timezone: 'UTC',
      today: '2026-10-09',
      ...props,
    },
    global: {
      plugins: [i18n, [VueQueryPlugin, { queryClient: client }]],
    },
  })
}
function page(overrides: Partial<ActivityReportPage> = {}): ActivityReportPage {
  return {
    from: '2026-10-08',
    to: '2026-10-09',
    items: [report(1), report(3)],
    next_cursor: null,
    fingerprint: 'archive',
    ...overrides,
  }
}
type ButtonQuery = Pick<ReturnType<typeof screen>, 'findAll'>
async function click(wrapper: ButtonQuery, label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}
function requests() {
  return transport.get.mock.calls.map(([path]) => new URL(path as string, 'https://synthetic.test'))
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function cachedPages(client: QueryClient) {
  return client
    .getQueriesData({ queryKey: ['personal', 'activity-report-archive'] })
    .map(([, data]) => data)
    .filter((data) => data !== undefined)
}
beforeEach(() => {
  vi.clearAllMocks()
  transport.get.mockReset()
  account.me = { id: 'owner' }
  account.readable = true
  transport.writable = true
  transport.operate.mockResolvedValue({})
  transport.get.mockResolvedValue(page())
})

describe('all report versions and annotation writes', () => {
  it('keeps original workout fields separate from the readable projection and current annotations', async () => {
    const original = {
      ...report(1),
      kind: 'workouts',
      shape: 'workout_report',
      start_at: '2026-10-08T11:40:00Z',
      end_at: '2026-10-08T12:00:00Z',
      original_extension: { marker: 'retained', nullable: null },
      source_status: 'paused',
      annotation_id: 'current-annotation',
      data: {
        sport_type: 'boxing',
        distance_meters: '00100.00',
        sport_plan: {
          requested_id: 20,
          name: 'boxing',
          family: 'combat',
          candidate_wire_mode: 20,
          actual_wire_mode: null,
          qualification: 'protocol_unverified',
          evidence_ref: 'synthetic',
          unknown_extension: { marker: 'retained' },
        },
        unknown_measurement: { text: '003.00', nullable: null },
      },
    }
    transport.get.mockResolvedValue(page({ items: [original] }))
    const before = JSON.stringify(original)
    const wrapper = screen()
    await flushPromises()
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).toContain('0,1 км')
    expect(wrapper.get('[data-testid="device-workout-values"]').text()).toContain(
      'Длительность не сообщена',
    )
    const raw = wrapper.get('[data-testid="saved-activity-report-fields"]')
    expect(raw.attributes('open')).toBeUndefined()
    const saved = JSON.parse(raw.get('pre').text())
    expect(saved.data).toEqual(original.data)
    expect(saved.original_extension).toEqual(original.original_extension)
    expect(saved.data).not.toHaveProperty('duration_seconds')
    expect(saved.data).not.toHaveProperty('steps')
    expect(saved).not.toHaveProperty('excluded')
    expect(saved).not.toHaveProperty('annotation_id')
    expect(saved).not.toHaveProperty('annotation_revision')
    expect(saved).not.toHaveProperty('current_revision')
    expect(saved).not.toHaveProperty('source_status')
    const details = wrapper.get('[data-testid="record-details"]')
    expect(JSON.parse(details.get('pre').text()).data).toEqual(original.data)
    expect(wrapper.get('[data-testid="activity-report-disposition"]').text()).toBe(
      'Исключено из итога',
    )
    await click(wrapper, 'Вернуть в итог')
    expect(transport.operate).toHaveBeenCalledWith(
      'activity_report.restore',
      { report_id: 'report', annotation_revision: 2 },
      { report: 3 },
    )
    expect(JSON.stringify(original)).toBe(before)
    wrapper.unmount()
  })
  it.each([
    ['current', 'Текущая версия отчёта'],
    ['excluded', 'Исключено из итога'],
    ['conflict', 'Конфликт версий отчёта'],
    ['superseded', 'Заменён новой версией'],
  ])(
    'renders %s as a readable report disposition without mutation rights',
    async (disposition, label) => {
      transport.writable = false
      transport.get.mockResolvedValue({
        from: '2026-10-08',
        to: '2026-10-09',
        items: [
          {
            ...report(disposition === 'current' ? 3 : 1),
            excluded: disposition === 'excluded',
            resolution_disposition: disposition,
          },
        ],
        next_cursor: null,
        fingerprint: 'archive',
      })
      const wrapper = screen()
      await flushPromises()
      expect(wrapper.get('[data-testid="activity-report-disposition"]').text()).toBe(label)
      expect(wrapper.find('[data-testid="activity-report-disposition-details"]').exists()).toBe(
        false,
      )
      expect(wrapper.findAll('[data-testid="record-details"]')).toHaveLength(1)
      expect(wrapper.findAll('[data-testid="activity-report-version"] button')).toHaveLength(0)
      expect(transport.operate).not.toHaveBeenCalled()
      wrapper.unmount()
    },
  )
  it.each(['future_disposition', 'constructor'])(
    'keeps unknown disposition %s neutral and its original code in closed details',
    async (disposition) => {
      transport.get.mockResolvedValue({
        from: '2026-10-08',
        to: '2026-10-09',
        items: [{ ...report(3), excluded: false, resolution_disposition: disposition }],
        next_cursor: null,
        fingerprint: 'archive',
      })
      const wrapper = screen()
      await flushPromises()
      expect(wrapper.get('[data-testid="activity-report-disposition"]').text()).toBe(
        'Статус отчёта не распознан',
      )
      const details = wrapper.get('[data-testid="activity-report-disposition-details"]')
      expect(details.attributes('open')).toBeUndefined()
      expect(details.get('summary').text()).toBe('Технический статус отчёта')
      expect(details.get('code').text()).toBe(disposition)
      expect(wrapper.get('[data-testid="record-details"]').text()).toContain('100')
      await wrapper.get('[data-testid="activity-report-version"] button').trigger('click')
      await flushPromises()
      expect(transport.operate).toHaveBeenCalledWith(
        'activity_report.exclude',
        { report_id: 'report', annotation_revision: 2 },
        { report: 3 },
      )
      wrapper.unmount()
    },
  )
  it('restores a logical report from an old version using the current head and annotation revision', async () => {
    const wrapper = screen()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('Предыдущая версия')
    await wrapper
      .findAll('[data-testid="activity-report-version"]')[0]!
      .get('button')
      .trigger('click')
    await flushPromises()
    expect(transport.operate).toHaveBeenCalledWith(
      'activity_report.restore',
      { report_id: 'report', annotation_revision: 2 },
      { report: 3 },
    )
    wrapper.unmount()
  })
  it('keeps old/excluded originals readable without mutation rights', async () => {
    transport.writable = false
    const wrapper = screen()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="record-details"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="activity-report-version"] button')).toHaveLength(0)
    expect(transport.operate).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('one coherent generation of report archive pages', () => {
  it.each([new Error('Synthetic unavailable'), new ApiError(409, 'synthetic', null, null, null)])(
    'hides loaded cards and actions on a failed continuation and restarts with an empty first page',
    async (failure) => {
      transport.get
        .mockResolvedValueOnce(page({ next_cursor: 'old-cursor' }))
        .mockRejectedValueOnce(failure)
        .mockResolvedValueOnce(page({ fingerprint: 'new-archive', items: [] }))
      const client = queryClient()
      const wrapper = screen({}, client)
      await flushPromises()
      const oldAction = wrapper.get('[data-testid="activity-report-version"] button')
      expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(2)
      await click(wrapper, 'Загрузить ещё')
      expect(requests()[1]!.searchParams.get('cursor')).toBe('old-cursor')
      expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(0)
      expect(wrapper.find('[data-testid="activity-report-disposition"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="record-details"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('Отчётов за этот период нет.')
      expect(wrapper.get('[role="alert"]').text()).toContain('с первой страницы')
      expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['Обновить историю'])
      await oldAction.trigger('click')
      expect(transport.operate).not.toHaveBeenCalled()
      await click(wrapper, 'Обновить историю')
      expect(requests()[2]!.searchParams.has('cursor')).toBe(false)
      expect(transport.get).toHaveBeenCalledTimes(3)
      expect(wrapper.text()).toContain('Отчётов за этот период нет.')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(cachedPages(client)).toHaveLength(1)
      wrapper.unmount()
      expect(cachedPages(client)).toEqual([])
      client.clear()
    },
  )

  it('hides mixed fingerprints and restarts without refetching the old cursor chain', async () => {
    transport.get
      .mockResolvedValueOnce(page({ next_cursor: 'old-cursor' }))
      .mockResolvedValueOnce(page({ fingerprint: 'changed', items: [report(2)] }))
      .mockResolvedValueOnce(
        page({ fingerprint: 'fresh', items: [{ ...report(3), data: { value: '777' } }] }),
      )
    const wrapper = screen()
    await flushPromises()
    await click(wrapper, 'Загрузить ещё')
    expect(wrapper.find('[data-testid="activity-report-version"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="record-details"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Отчётов за этот период нет.')
    await click(wrapper, 'Обновить историю')
    expect(requests()[2]!.searchParams.has('cursor')).toBe(false)
    expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="record-details"]').text()).toContain('777')
    wrapper.unmount()
  })

  it('hides retained cards on a failed background refresh', async () => {
    transport.get
      .mockResolvedValueOnce(page())
      .mockRejectedValueOnce(new Error('Synthetic refresh failed'))
      .mockResolvedValueOnce(page({ items: [] }))
    const client = queryClient()
    const wrapper = screen({}, client)
    await flushPromises()
    await client.refetchQueries({ queryKey: ['personal', 'activity-report-archive'] })
    await flushPromises()
    expect(wrapper.find('[data-testid="activity-report-version"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="record-details"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Отчётов за этот период нет.')
    await click(wrapper, 'Обновить историю')
    expect(requests()[2]!.searchParams.has('cursor')).toBe(false)
    expect(wrapper.text()).toContain('Отчётов за этот период нет.')
    wrapper.unmount()
    client.clear()
  })

  it.each([
    { from: '2026-10-07' },
    { to: '2026-10-10' },
    { items: [{ ...report(3), kind: 'distance' }] },
  ])('rejects a response outside the requested date window or kind %#', async (wrong) => {
    transport.get.mockResolvedValue(page(wrong))
    const wrapper = screen({ kind: 'running_steps' })
    await flushPromises()
    expect(wrapper.find('[data-testid="activity-report-version"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('Отчётов за этот период нет.')
    wrapper.unmount()
  })
})

describe('report archive owner, filters and permission epochs', () => {
  it.each(['signed out', 'permission missing'])(
    'does not read or render when %s',
    async (reason) => {
      if (reason === 'signed out') account.me = null
      else account.readable = false
      const wrapper = screen()
      await flushPromises()
      expect(transport.get).not.toHaveBeenCalled()
      expect(wrapper.find('section').exists()).toBe(false)
      wrapper.unmount()
    },
  )

  it.each(['owner', 'from', 'to', 'kind'])(
    'rejects a late page after changing %s and reads the new first page',
    async (scope) => {
      const late = deferred<ActivityReportPage>()
      transport.get.mockReturnValueOnce(late.promise).mockImplementation((path: string) => {
        const query = new URL(path, 'https://synthetic.test').searchParams
        return page({
          from: query.get('from')!,
          to: query.get('to')!,
          items: [
            { ...report(3), kind: query.get('kind') ?? 'running_steps', data: { value: '777' } },
          ],
        })
      })
      const client = queryClient()
      const wrapper = screen({}, client)
      await flushPromises()
      if (scope === 'owner') account.me = { id: 'second-owner' }
      else if (scope === 'from') await wrapper.setProps({ from: '2026-10-07' })
      else if (scope === 'to') await wrapper.setProps({ to: '2026-10-10' })
      else await wrapper.setProps({ kind: 'distance' })
      await flushPromises()
      late.resolve(page())
      await flushPromises()
      expect(requests()[1]!.searchParams.has('cursor')).toBe(false)
      expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(1)
      expect(wrapper.get('[data-testid="record-details"]').text()).toContain('777')
      expect(wrapper.get('[data-testid="record-details"]').text()).not.toContain('100')
      expect(cachedPages(client)).toHaveLength(1)
      wrapper.unmount()
      client.clear()
    },
  )

  it('keeps source choices but rejects a late response after changing the selected source', async () => {
    const late = deferred<ActivityReportPage>()
    const second = { ...report(3), id: 'second-report', source_id: 'ring-b', source_name: 'Ring B' }
    transport.get
      .mockResolvedValueOnce(page({ items: [report(3), second] }))
      .mockReturnValueOnce(late.promise)
      .mockResolvedValueOnce(page({ items: [{ ...second, data: { value: '777' } }] }))
    const client = queryClient()
    const wrapper = screen({}, client)
    await flushPromises()
    await wrapper.get('select').setValue('ring-a')
    await flushPromises()
    await wrapper.get('select').setValue('ring-b')
    await flushPromises()
    late.resolve(page({ items: [report(3)] }))
    await flushPromises()
    expect(requests()[2]!.searchParams.get('source_id')).toBe('ring-b')
    expect(requests()[2]!.searchParams.has('cursor')).toBe(false)
    expect(wrapper.get('[data-testid="record-details"]').text()).toContain('777')
    expect(wrapper.get('[data-testid="record-details"]').text()).not.toContain('100')
    expect(wrapper.findAll('option').map((option) => option.text())).toEqual([
      'Все источники',
      'Ring A',
      'Ring B',
    ])
    expect(cachedPages(client)).toHaveLength(1)
    wrapper.unmount()
    client.clear()
  })

  it('removes loaded private data and source names on revoke and waits for a fresh read after regrant', async () => {
    const fresh = deferred<ActivityReportPage>()
    transport.get
      .mockResolvedValueOnce(
        page({ items: [report(3), { ...report(1), id: 'second-report', source_id: 'ring-b' }] }),
      )
      .mockReturnValueOnce(fresh.promise)
    const client = queryClient()
    const wrapper = screen({}, client)
    await flushPromises()
    expect(wrapper.find('select').exists()).toBe(true)
    account.readable = false
    await flushPromises()
    expect(wrapper.find('section').exists()).toBe(false)
    expect(cachedPages(client)).toEqual([])
    account.readable = true
    await flushPromises()
    expect(transport.get).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="activity-report-version"]').exists()).toBe(false)
    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Отчётов за этот период нет.')
    fresh.resolve(page({ items: [{ ...report(3), data: { value: '777' } }] }))
    await flushPromises()
    expect(wrapper.get('[data-testid="record-details"]').text()).toContain('777')
    expect(requests()[1]!.searchParams.has('cursor')).toBe(false)
    wrapper.unmount()
    client.clear()
  })

  it.each(['permission', 'owner'])(
    'rejects a pending old page after a same-turn %s round trip',
    async (scope) => {
      const late = deferred<ActivityReportPage>()
      transport.get
        .mockReturnValueOnce(late.promise)
        .mockResolvedValue(page({ items: [{ ...report(3), data: { value: '777' } }] }))
      const client = queryClient()
      const wrapper = screen({}, client)
      await flushPromises()
      if (scope === 'permission') {
        account.readable = false
        account.readable = true
      } else {
        account.me = { id: 'second-owner' }
        account.me = { id: 'owner' }
      }
      await flushPromises()
      late.resolve(page())
      await flushPromises()
      expect(wrapper.findAll('[data-testid="activity-report-version"]')).toHaveLength(1)
      expect(wrapper.get('[data-testid="record-details"]').text()).toContain('777')
      expect(wrapper.get('[data-testid="record-details"]').text()).not.toContain('100')
      expect(cachedPages(client)).toHaveLength(1)
      wrapper.unmount()
      client.clear()
    },
  )

  it('does not retain a late response after unmount', async () => {
    const late = deferred<ActivityReportPage>()
    transport.get.mockReturnValueOnce(late.promise)
    const client = queryClient()
    const wrapper = screen({}, client)
    await flushPromises()
    wrapper.unmount()
    late.resolve(page())
    await flushPromises()
    expect(cachedPages(client)).toEqual([])
    client.clear()
  })

  it('keeps two readers independent when one resets and unmounts', async () => {
    transport.get
      .mockResolvedValueOnce(page({ items: [report(3)], next_cursor: 'first-reader-cursor' }))
      .mockResolvedValueOnce(page({ items: [report(3)], next_cursor: 'second-reader-cursor' }))
      .mockRejectedValueOnce(new Error('Synthetic first reader failure'))
      .mockResolvedValueOnce(
        page({ fingerprint: 'fresh', items: [{ ...report(3), data: { value: '777' } }] }),
      )
      .mockResolvedValueOnce(page({ items: [{ ...report(2), data: { value: '200' } }] }))
    const firstVisible = ref(true)
    const client = queryClient()
    const wrapper = mount(
      defineComponent({
        components: { ActivityReportHistory },
        setup: () => ({ firstVisible }),
        template: `<div>
          <ActivityReportHistory v-if="firstVisible" from="2026-10-08" to="2026-10-09" timezone="UTC" today="2026-10-09" />
          <ActivityReportHistory from="2026-10-08" to="2026-10-09" timezone="UTC" today="2026-10-09" />
        </div>`,
      }),
      { global: { plugins: [i18n, [VueQueryPlugin, { queryClient: client }]] } },
    )
    await flushPromises()
    const readers = wrapper.findAllComponents(ActivityReportHistory)
    expect(transport.get).toHaveBeenCalledTimes(2)
    await click(readers[0]!, 'Загрузить ещё')
    expect(readers[0]!.find('[data-testid="activity-report-version"]').exists()).toBe(false)
    expect(readers[1]!.get('[data-testid="record-details"]').text()).toContain('100')
    await click(readers[0]!, 'Обновить историю')
    expect(readers[0]!.get('[data-testid="record-details"]').text()).toContain('777')
    expect(readers[1]!.get('[data-testid="record-details"]').text()).toContain('100')
    firstVisible.value = false
    await flushPromises()
    expect(cachedPages(client)).toHaveLength(1)
    const remaining = wrapper.getComponent(ActivityReportHistory)
    await click(remaining, 'Загрузить ещё')
    expect(requests()[4]!.searchParams.get('cursor')).toBe('second-reader-cursor')
    expect(remaining.findAll('[data-testid="activity-report-version"]')).toHaveLength(2)
    expect(remaining.text()).toContain('100')
    expect(remaining.text()).toContain('200')
    expect(remaining.text()).not.toContain('777')
    wrapper.unmount()
    expect(cachedPages(client)).toEqual([])
    client.clear()
  })
})
