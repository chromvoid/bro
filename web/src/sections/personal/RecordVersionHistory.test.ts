import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { ApiError } from '@/core/http'
import RecordVersionHistory from './RecordVersionHistory.vue'
import { RecordVersionPage } from './recordVersions'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
const session = reactive({ me: { id: 'synthetic-owner' } as { id: string } | null, readable: true })
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return session.me
    },
    can: (scope: string) => session.readable && scope === 'personal.read',
  }),
}))
function archive(overrides: Partial<RecordVersionPage> = {}): RecordVersionPage {
  return {
    id: 'synthetic-record',
    kind: 'sleep',
    source_id: 'synthetic-source',
    source_name: 'Synthetic ring',
    source_status: 'disabled',
    current_revision: 2,
    count: 2,
    fingerprint: 'synthetic-lineage',
    next_cursor: null,
    annotation: {
      excluded: true,
      corrected: true,
      revision: 4,
      observation: { data: { value: 480 } },
    },
    versions: [
      {
        revision: 2,
        operation_id: 'synthetic-operation',
        created_at: '2026-10-05T12:00:00Z',
        snapshot: {
          id: 'synthetic-record',
          user_id: 'synthetic-owner',
          source_id: 'synthetic-source',
          kind: 'sleep',
          revision: 2,
          start_at: '2026-10-04T21:00:00Z',
          end_at: '2026-10-05T05:00:00Z',
          timezone: 'UTC',
          method: 'device_report',
          data: { value: null, stages: [{ stage: 'future_stage', retained: null }] },
          origin: { provider: 'synthetic', hardware: 'Synthetic HW', future: { nullable: null } },
          quality: ['protocol_unverified', 'synthetic_unknown_quality'],
          unknown: '<script>synthetic</script>',
        },
      },
    ],
    ...overrides,
  }
}
function show(extra: Partial<InstanceType<typeof RecordVersionHistory>['$props']> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(RecordVersionHistory, {
    props: {
      family: 'record',
      recordId: 'synthetic-record',
      sourceId: 'synthetic-source',
      metric: 'sleep',
      timezone: 'UTC',
      today: '2026-10-05',
      ...extra,
    },
    global: { plugins: [i18n, [VueQueryPlugin, { queryClient: client }]] },
  })
  return {
    wrapper,
    client,
    close: () => {
      wrapper.unmount()
      client.clear()
    },
  }
}
async function openArchive(wrapper: ReturnType<typeof show>['wrapper']) {
  const details = wrapper.get<HTMLDetailsElement>('[data-testid="record-version-history"]')
  details.element.open = true
  await details.trigger('toggle')
  await flushPromises()
}
async function click(wrapper: ReturnType<typeof show>['wrapper'], label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
beforeEach(() => {
  transport.get.mockReset()
  session.me = { id: 'synthetic-owner' }
  session.readable = true
})

describe('lazy exact ordinary/sensor version archive', () => {
  it('loads retained source data lazily beside current corrections and exclusions', async () => {
    transport.get.mockResolvedValue(archive())
    const { wrapper, close } = show()
    await flushPromises()
    expect(transport.get).not.toHaveBeenCalled()
    await openArchive(wrapper)
    const url = new URL(transport.get.mock.calls[0]![0], 'https://synthetic.test')
    expect(url.pathname).toBe('/v1/personal/records/synthetic-record/versions')
    expect(url.searchParams.get('kind')).toBe('sleep')
    expect(url.searchParams.get('source_id')).toBe('synthetic-source')
    expect(wrapper.text()).toContain('Сейчас запись исключена')
    expect(wrapper.text()).toContain('Сейчас действует пользовательская поправка')
    expect(wrapper.text()).toContain('значение не передано')
    expect(wrapper.text()).not.toContain('8 ч')
    expect(wrapper.get('[data-testid="saved-record-version"] pre').text()).toContain('future_stage')
    expect(wrapper.text()).toContain('synthetic_unknown_quality')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>synthetic</script>')
    expect(wrapper.text()).toContain('Квитанция сохранения: synthetic-operation')
    close()
  })
  it('binds sensor archive to its source and retains null range values and unknown fields', async () => {
    const saved = archive({
      kind: undefined,
      metric: 'spo2',
      annotation: { excluded: false, corrected: false, revision: 0, observation: null },
    })
    saved.versions[0]!.snapshot = {
      id: 'synthetic-record',
      source_id: 'synthetic-source',
      metric: 'spo2',
      value: null,
      reported_min: 94,
      reported_max: '99',
      unit: '%',
      unknown: { nullable: null },
    }
    transport.get.mockResolvedValue(saved)
    const { wrapper, close } = show({ family: 'sensor', metric: 'spo2' })
    await openArchive(wrapper)
    expect(transport.get.mock.calls[0]![0]).toContain('/sensor-samples/synthetic-record/versions?')
    expect(wrapper.text()).toContain('Диапазон устройства: 94 % — 99 %')
    expect(wrapper.text()).not.toContain('96,5')
    expect(wrapper.text()).toContain('"nullable": null')
    close()
  })
  it('does not fabricate archive entries or timestamps for a manual legacy row', async () => {
    transport.get.mockResolvedValue(
      archive({
        source_id: null,
        source_name: null,
        source_status: null,
        count: 0,
        versions: [],
        current_revision: 1,
        annotation: { excluded: false, corrected: false, revision: 0, observation: null },
      }),
    )
    const { wrapper, close } = show({ sourceId: null })
    await openArchive(wrapper)
    expect(
      new URL(transport.get.mock.calls[0]![0], 'https://synthetic.test').searchParams.has(
        'source_id',
      ),
    ).toBe(false)
    expect(wrapper.text()).toContain('Сохранённых версий нет')
    expect(wrapper.find('[data-testid="saved-record-version"]').exists()).toBe(false)
    close()
  })
  it.each([new Error('synthetic transport'), new ApiError(409, 'conflict', null, null, null)])(
    'hides loaded versions after a next-page failure and restarts without the cursor',
    async (error) => {
      transport.get
        .mockResolvedValueOnce(archive({ next_cursor: 'synthetic-next' }))
        .mockRejectedValueOnce(error)
        .mockResolvedValueOnce(
          archive({
            fingerprint: 'fresh',
            count: 1,
            versions: [
              { ...archive().versions[0]!, revision: 1, snapshot: { data: { value: null } } },
            ],
          }),
        )
      const { wrapper, close } = show()
      await openArchive(wrapper)
      await click(wrapper, 'Загрузить ещё версии')
      expect(wrapper.find('[data-testid="saved-record-version"]').exists()).toBe(false)
      expect(wrapper.get('[role="alert"]').text()).toContain('с первой страницы')
      await click(wrapper, 'Обновить архив')
      const query = new URL(transport.get.mock.calls[2]![0], 'https://synthetic.test').searchParams
      expect(query.has('cursor')).toBe(false)
      expect(wrapper.findAll('[data-testid="saved-record-version"]')).toHaveLength(1)
      close()
    },
  )
  it('hides pages with a changed lineage even if both requests succeeded', async () => {
    transport.get
      .mockResolvedValueOnce(archive({ next_cursor: 'synthetic-next' }))
      .mockResolvedValueOnce(
        archive({
          fingerprint: 'changed',
          versions: [{ ...archive().versions[0]!, revision: 1, snapshot: {} }],
        }),
      )
    const { wrapper, close } = show()
    await openArchive(wrapper)
    await click(wrapper, 'Загрузить ещё версии')
    expect(wrapper.find('[data-testid="saved-record-version"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('Архив изменился')
    close()
  })
  it.each([
    { id: 'foreign-record' },
    { source_id: 'foreign-source' },
    { kind: 'steps' as const },
    { versions: [{ ...archive().versions[0]!, snapshot: { user_id: 'foreign-owner' } }] },
  ])('rejects an archive returned for a different context', async (change) => {
    transport.get.mockResolvedValue(archive(change))
    const { wrapper, close } = show()
    await openArchive(wrapper)
    expect(wrapper.find('[data-testid="saved-record-version"]').exists()).toBe(false)
    close()
  })
  it.each(['owner', 'permission', 'source', 'record', 'metric'])(
    'discards an in-flight response after %s changes',
    async (change) => {
      const pending = deferred<RecordVersionPage>()
      transport.get.mockReturnValue(pending.promise)
      const { wrapper, client, close } = show()
      await openArchive(wrapper)
      if (change === 'owner') session.me = { id: 'synthetic-other-owner' }
      else if (change === 'permission') session.readable = false
      else if (change === 'source') await wrapper.setProps({ sourceId: 'synthetic-other-source' })
      else if (change === 'record') await wrapper.setProps({ recordId: 'synthetic-other-record' })
      else await wrapper.setProps({ metric: 'steps' })
      await flushPromises()
      pending.resolve(archive())
      await flushPromises()
      expect(wrapper.find('[data-testid="saved-record-version"]').exists()).toBe(false)
      expect(
        client.getQueryData([
          'personal',
          'record-versions',
          'synthetic-owner',
          'record',
          'synthetic-record',
          'synthetic-source',
          'sleep',
        ]),
      ).toBeUndefined()
      close()
    },
  )
})
