import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import ru from '@/locales/ru.json'
import SensorHistory from './SensorHistory.vue'
import { page } from './resolverFixtures'
import { ApiError } from '@/core/http'

const transport = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  write: true,
}))
vi.mock('@/core/client', () => ({ api: transport }))
const session = reactive({ me: { id: 'synthetic-owner' } as { id: string } | null, readable: true })
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return session.me
    },
    can: (scope: string) => (scope === 'personal.read' ? session.readable : transport.write),
  }),
}))
vi.mock('./usePersonal', () => ({
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
function sample(id: string) {
  return {
    id,
    user_id: 'synthetic-owner',
    source_id: 'source',
    source_name: 'Synthetic ring',
    external_id: id,
    metric: 'spo2',
    start_at: '2026-10-04T12:00:00Z',
    end_at: '2026-10-04T12:10:00Z',
    timezone: 'UTC',
    local_date: '2026-10-04',
    value: null,
    reported_min: '94',
    reported_max: '99',
    unit: '%',
    origin: { provider: 'herzring' },
    quality: ['protocol_unverified'],
    revision: 1,
    annotation_revision: 0,
    excluded: false,
  }
}
function rawPage(
  items: ReturnType<typeof sample>[],
  next_cursor: string | null = null,
  fingerprint = 'synthetic-raw',
  count = 2,
) {
  return {
    items,
    next_cursor,
    fingerprint,
    count,
    from: '2026-09-30T21:00:00Z',
    to: '2026-10-05T21:00:00Z',
  }
}
function show() {
  return mount(SensorHistory, {
    props: {
      metric: 'spo2',
      from: '2026-10-01',
      to: '2026-10-05',
      timezone: 'Europe/Moscow',
      today: '2026-10-05',
    },
    global: {
      stubs: { RecordVersionHistory: true },
      plugins: [
        [
          VueQueryPlugin,
          { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
        ],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
    },
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  transport.write = true
  session.me = { id: 'synthetic-owner' }
  session.readable = true
  transport.operate.mockResolvedValue({ status: 'applied' })
})
async function allSources(wrapper: ReturnType<typeof show>) {
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Все источники')!
    .trigger('click')
  await flushPromises()
}
const loadMore = (wrapper: ReturnType<typeof show>) =>
  wrapper.findAll('button').find((button) => button.text() === 'Загрузить ещё')!
describe('sensor history presentation and pagination', () => {
  it('loads another page without replacing ranges with invented readings', async () => {
    transport.get
      .mockResolvedValueOnce({ ...page(), daily: [] })
      .mockResolvedValueOnce(rawPage([sample('first')], 'page-2'))
      .mockResolvedValueOnce(rawPage([sample('second')]))
    const wrapper = show()
    await allSources(wrapper)
    const query = new URL(transport.get.mock.calls[1]![0], 'https://example.test').searchParams
    expect(query.get('from')).toBe('2026-10-01T00:00:00+03:00')
    expect(query.get('to')).toBe('2026-10-06T00:00:00+03:00')
    expect(wrapper.text()).toContain('Диапазон устройства: 94 % — 99 %')
    expect(wrapper.text()).toContain('Формат истории ещё не сверён')
    expect(wrapper.text()).not.toContain('96,5')
    await loadMore(wrapper).trigger('click')
    await flushPromises()
    expect(
      new URL(transport.get.mock.calls[2]![0], 'https://example.test').searchParams.get('cursor'),
    ).toBe('page-2')
    expect(wrapper.findAll('[data-testid="sensor-sample"]')).toHaveLength(2)
    expect(loadMore(wrapper)).toBeUndefined()
  })
  it('hides stale measurements when the next page fails and explicitly resets the first page', async () => {
    transport.get
      .mockResolvedValueOnce({ ...page(), daily: [] })
      .mockResolvedValueOnce(rawPage([sample('first')], 'page-2'))
      .mockRejectedValueOnce(new Error('Synthetic failure'))
      .mockResolvedValueOnce(rawPage([sample('fresh')], null, 'fresh', 1))
    const wrapper = show()
    await allSources(wrapper)
    await loadMore(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="sensor-sample"]')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('Обновите её с первой страницы')
    expect(loadMore(wrapper)).toBeUndefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Обновить историю')!
      .trigger('click')
    await flushPromises()
    expect(
      new URL(transport.get.mock.calls[3]![0], 'https://synthetic.test').searchParams.has('cursor'),
    ).toBe(false)
    expect(wrapper.findAll('[data-testid="sensor-sample"]')).toHaveLength(1)
  })
  it('shows the resolved statistics independently of the selected series page', async () => {
    transport.get
      .mockResolvedValueOnce({ ...page(), series: [sample('first')], next_cursor: 'selected-2' })
      .mockResolvedValueOnce({ ...page(), series: [sample('second')] })
    const wrapper = show()
    await flushPromises()
    expect(wrapper.get('[data-testid="statistics-ranges"]').text()).toContain('Количество: 1')
    expect(wrapper.text()).not.toContain('96,5')
    await loadMore(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="statistics-ranges"]').text()).toContain('Количество: 1')
    expect(
      wrapper.get('[data-testid="sensor-samples"]').findAll('[data-testid="sensor-sample"]'),
    ).toHaveLength(2)
  })
  it('uses the ledger with both fact and annotation revisions for exclusion and restoration', async () => {
    transport.get
      .mockResolvedValueOnce({ ...page(), series: [sample('first')] })
      .mockResolvedValueOnce(
        rawPage(
          [{ ...sample('excluded'), excluded: true, revision: 4, annotation_revision: 3 }],
          null,
          'synthetic-raw',
          1,
        ),
      )
    const wrapper = show()
    await flushPromises()
    await wrapper.get('[data-testid="sensor-samples"] button').trigger('click')
    await flushPromises()
    expect(transport.operate).toHaveBeenCalledWith(
      'sensor.exclude',
      { sample_id: 'first', annotation_revision: 0 },
      { first: 1 },
    )
    await allSources(wrapper)
    expect(wrapper.text()).toContain('Исключено из итога')
    await wrapper.get('[data-testid="sensor-samples"] button').trigger('click')
    await flushPromises()
    expect(transport.operate).toHaveBeenCalledWith(
      'sensor.restore',
      { sample_id: 'excluded', annotation_revision: 3 },
      { excluded: 4 },
    )
  })
  it('hides exclusion writes without personal write access', async () => {
    transport.write = false
    transport.get.mockResolvedValueOnce({ ...page(), series: [sample('first')] })
    const wrapper = show()
    await flushPromises()
    expect(wrapper.get('[data-testid="sensor-samples"]').find('button').exists()).toBe(false)
    expect(transport.operate).not.toHaveBeenCalled()
  })
  it('offers a fresh first page when the server rejects a changed decision cursor', async () => {
    transport.get
      .mockResolvedValueOnce({ ...page(), series: [sample('old')], next_cursor: 'old-page-2' })
      .mockRejectedValueOnce(new ApiError(409, 'conflict', null, null, null))
      .mockResolvedValueOnce({ ...page(), fingerprint: 'new', series: [sample('new')] })
    const wrapper = show()
    await flushPromises()
    await loadMore(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('Данные изменились между страницами')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Обновить итог')!
      .trigger('click')
    await flushPromises()
    const query = new URL(transport.get.mock.calls[2]![0], 'https://example.test').searchParams
    expect(query.has('cursor')).toBe(false)
    expect(
      wrapper.get('[data-testid="sensor-samples"]').findAll('[data-testid="sensor-sample"]'),
    ).toHaveLength(1)
  })
  it('retains permitted exclusion controls when switching metrics in All sources mode', async () => {
    transport.get.mockImplementation(async (url: string) => {
      const parsed = new URL(url, 'https://example.test')
      if (parsed.pathname === '/v1/personal/resolved')
        return { ...page(), series: [sample('first')] }
      return {
        ...rawPage(
          [{ ...sample('raw'), metric: parsed.searchParams.get('metric')! }],
          null,
          'synthetic-raw',
          1,
        ),
        from: parsed.searchParams.get('from'),
        to: parsed.searchParams.get('to'),
      }
    })
    const wrapper = show()
    await allSources(wrapper)
    await wrapper.setProps({ metric: 'temperature' })
    await flushPromises()
    expect(wrapper.get('[data-testid="sensor-samples"] button').text()).toBe('Исключить')
  })
  it('does not join successful raw pages from different snapshots', async () => {
    transport.get
      .mockResolvedValueOnce({ ...page(), daily: [] })
      .mockResolvedValueOnce(rawPage([sample('first')], 'page-2'))
      .mockResolvedValueOnce(rawPage([sample('second')], null, 'changed'))
    const wrapper = show()
    await allSources(wrapper)
    await loadMore(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="sensor-samples"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('История изменилась')
    expect(wrapper.text()).not.toContain('В этом периоде нет')
    wrapper.unmount()
  })
  it('offers the version archive for each selected sample without changing its exclusion revision', async () => {
    transport.get.mockResolvedValueOnce({ ...page(), series: [sample('first')] })
    const wrapper = show()
    await flushPromises()
    expect(wrapper.getComponent({ name: 'RecordVersionHistory' }).props()).toMatchObject({
      family: 'sensor',
      recordId: 'first',
      sourceId: 'source',
      metric: 'spo2',
    })
    expect(transport.operate).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it.each(['owner', 'permission', 'metric', 'window'])(
    'does not display an in-flight all-source page after %s changes',
    async (change) => {
      let resolve!: (value: ReturnType<typeof rawPage>) => void
      const pending = new Promise<ReturnType<typeof rawPage>>((done) => {
        resolve = done
      })
      transport.get
        .mockResolvedValueOnce({ ...page(), daily: [] })
        .mockReturnValueOnce(pending)
        .mockResolvedValue({ ...page(), daily: [], series: [] })
      const wrapper = show()
      await allSources(wrapper)
      if (change === 'owner') session.me = { id: 'synthetic-other-owner' }
      else if (change === 'permission') session.readable = false
      else if (change === 'metric') await wrapper.setProps({ metric: 'heart_rate' })
      else await wrapper.setProps({ from: '2026-10-02' })
      await flushPromises()
      resolve(rawPage([sample('stale')], null, 'stale', 1))
      await flushPromises()
      expect(wrapper.find('[data-testid="sensor-sample"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('Synthetic ring')
      wrapper.unmount()
    },
  )
})
