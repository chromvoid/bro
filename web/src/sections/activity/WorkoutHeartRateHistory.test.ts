import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { i18n } from '@/i18n'
import WorkoutHeartRateChart from './WorkoutHeartRateChart.vue'
import WorkoutHeartRateHistory from './WorkoutHeartRateHistory.vue'
import { WorkoutHeartRatePage, type WorkoutHeartRateChunk } from './workoutHeartRate'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
const session = reactive({
  me: { id: 'synthetic-owner' } as { id: string } | null,
  readable: true,
})
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return session.me
    },
    can: (scope: string) => session.readable && scope === 'personal.read',
  }),
}))
const workout = '839d3913-8c05-4c83-b88a-86845072945c'
const otherWorkout = 'ca86f3a5-e47b-4ab3-b210-5a4b178c2c76'
const source = '40c24557-02df-4b3f-a77a-841b377624da'
const otherSource = 'd642f52d-d7cb-4f6a-b6ac-cd839f4be625'
const report = 'd7626157-1466-4cdd-a030-7e982f4dd6c1'

function chunk(sequence = 0, bpm = 72): WorkoutHeartRateChunk {
  const start = new Date(Date.parse('2026-10-06T10:00:00Z') + sequence * 60_000)
  return {
    external_id: `synthetic-chunk-${sequence}`,
    workout_id: workout,
    sequence,
    start_at: start.toISOString(),
    end_at: new Date(start.getTime() + 60_000).toISOString(),
    time_basis: 'phone_monotonic',
    samples: [
      { offset_ms: 0, bpm, quality: 'valid' },
      { offset_ms: 1_000, bpm: bpm + 1, quality: 'unknown' },
    ],
    gaps: [{ start_offset_ms: 40_000, end_offset_ms: 60_000, reason: 'synthetic_gap' }],
    clock_anchor: { utc_at: start.toISOString(), elapsed_realtime_ms: 0, boot_id: 'synthetic' },
  }
}
function page(overrides: Partial<WorkoutHeartRatePage> = {}): WorkoutHeartRatePage {
  return {
    workout_id: workout,
    source_id: source,
    report_ref: { id: report, revision: 1 },
    fingerprint: 'a'.repeat(64),
    workout: {
      kind: 'running_workouts',
      sport_type: 'running',
      start_at: '2026-10-06T10:00:00Z',
      end_at: '2026-10-06T10:10:00Z',
      recovery_end_at: null,
      timezone: 'UTC',
      status: 'completed',
      trigger: 'manual',
    },
    chunks: [chunk()],
    next_cursor: null,
    ...overrides,
  }
}
function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(WorkoutHeartRateHistory, {
    props: { workoutId: workout, sourceId: source, timezone: 'UTC' },
    global: {
      plugins: [i18n, [VueQueryPlugin, { queryClient: client }]],
      stubs: { WorkoutHeartRateChart: true },
    },
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
type ButtonQuery = Pick<ReturnType<typeof show>['wrapper'], 'findAll'>
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
function cachedPages(client: QueryClient) {
  return client
    .getQueriesData({
      queryKey: ['personal', 'workout-heart-rate', 'synthetic-owner', source, workout],
    })
    .map(([, data]) => data)
    .filter((data) => data !== undefined)
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
const more = () => String(i18n.global.t('ring.loadMore'))
const restart = 'Обновить историю пульса'

beforeEach(() => {
  transport.get.mockReset()
  session.me = { id: 'synthetic-owner' }
  session.readable = true
})

describe('workout pulse pages from one source snapshot', () => {
  it('joins matching pages with an opaque cursor and preserves unknown observations and gaps', async () => {
    transport.get
      .mockResolvedValueOnce(page({ next_cursor: 'synthetic-cursor' }))
      .mockResolvedValueOnce(page({ chunks: [chunk(1, 76)] }))
    const { wrapper, close } = show()
    await flushPromises()
    expect(requests()[0]!.searchParams.get('cursor')).toBeNull()
    expect(requests()[0]!.searchParams.has('after_sequence')).toBe(false)
    expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')).toHaveLength(2)
    await click(wrapper, more())
    expect(requests()[1]!.searchParams.get('cursor')).toBe('synthetic-cursor')
    expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')).toHaveLength(4)
    expect(wrapper.get('pre').text()).toContain('synthetic-chunk-0')
    expect(wrapper.get('pre').text()).toContain('synthetic-chunk-1')
    expect(wrapper.get('pre').text()).toContain('"quality": "unknown"')
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    close()
  })

  it('keeps a saved workout without pulse distinct from a zero measurement', async () => {
    transport.get.mockResolvedValue(page({ chunks: [], next_cursor: null }))
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.text()).toContain(String(i18n.global.t('ring.workout.noSamples')))
    expect(wrapper.findComponent(WorkoutHeartRateChart).exists()).toBe(false)
    expect(wrapper.find('pre').exists()).toBe(true)
    expect(wrapper.get('pre').text()).toBe('[]')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('button')).toHaveLength(0)
    close()
  })

  it.each<[string, Partial<WorkoutHeartRatePage>]>([
    ['fingerprint', { fingerprint: 'b'.repeat(64), chunks: [chunk(1)] }],
    ['report identity', { report_ref: { id: otherWorkout, revision: 1 }, chunks: [chunk(1)] }],
    ['report revision', { report_ref: { id: report, revision: 2 }, chunks: [chunk(1)] }],
    [
      'terminal boundary',
      { workout: { ...page().workout, end_at: '2026-10-06T10:09:00Z' }, chunks: [chunk(1)] },
    ],
    [
      'terminal status',
      { workout: { ...page().workout, status: 'interrupted' }, chunks: [chunk(1)] },
    ],
    ['duplicate sequence', { chunks: [chunk(0)] }],
    ['unordered sequence', { chunks: [chunk(2), chunk(1)] }],
    ['foreign chunk', { chunks: [{ ...chunk(1), workout_id: otherWorkout }] }],
  ])(
    'hides all mixed chart and raw details on %s and explicitly restarts from page one',
    async (_label, changed) => {
      transport.get
        .mockResolvedValueOnce(page({ next_cursor: 'old-cursor' }))
        .mockResolvedValueOnce(page(changed))
        .mockResolvedValueOnce(
          page({
            fingerprint: 'c'.repeat(64),
            report_ref: { id: report, revision: 2 },
            chunks: [chunk(0, 88)],
          }),
        )
      const { wrapper, close } = show()
      await flushPromises()
      await click(wrapper, more())
      expect(wrapper.get('[role="alert"]').text()).toContain('с первой страницы')
      expect(wrapper.findComponent(WorkoutHeartRateChart).exists()).toBe(false)
      expect(wrapper.find('details').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('synthetic_gap')
      expect(wrapper.findAll('button').map((button) => button.text())).toEqual([restart])
      await click(wrapper, restart)
      expect(requests()[2]!.searchParams.get('cursor')).toBeNull()
      expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(88)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      close()
    },
  )

  it.each([new Error('Synthetic unavailable'), new ApiError(409, 'synthetic', null, null, null)])(
    'hides the first page and its raw details when the next request fails',
    async (failure) => {
      transport.get
        .mockResolvedValueOnce(page({ next_cursor: 'old-cursor' }))
        .mockRejectedValueOnce(failure)
        .mockResolvedValueOnce(page({ fingerprint: 'b'.repeat(64), chunks: [chunk(0, 86)] }))
      const { wrapper, close } = show()
      await flushPromises()
      await click(wrapper, more())
      expect(wrapper.findComponent(WorkoutHeartRateChart).exists()).toBe(false)
      expect(wrapper.find('details').exists()).toBe(false)
      expect(wrapper.get('[role="alert"]').text()).toContain('с первой страницы')
      await click(wrapper, restart)
      expect(requests()[2]!.searchParams.get('cursor')).toBeNull()
      expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(86)
      close()
    },
  )

  it.each([{ source_id: otherSource }, { workout_id: otherWorkout }])(
    'rejects a response outside the requested workout/source',
    async (wrong) => {
      transport.get.mockResolvedValue(page(wrong))
      const { wrapper, close } = show()
      await flushPromises()
      expect(wrapper.findComponent(WorkoutHeartRateChart).exists()).toBe(false)
      expect(wrapper.find('details').exists()).toBe(false)
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
      close()
    },
  )

  it('hides old chart and raw details on a failed background refresh', async () => {
    transport.get
      .mockResolvedValueOnce(page())
      .mockRejectedValueOnce(new Error('Synthetic refresh failed'))
    const { wrapper, client, close } = show()
    await flushPromises()
    await client.refetchQueries({
      queryKey: ['personal', 'workout-heart-rate', 'synthetic-owner', source, workout],
    })
    await flushPromises()
    expect(wrapper.findComponent(WorkoutHeartRateChart).exists()).toBe(false)
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('с первой страницы')
    close()
  })
})

describe('workout pulse ownership and permission epochs', () => {
  it.each(['signed out', 'permission missing'])(
    'does not fetch or render when %s',
    async (reason) => {
      if (reason === 'signed out') session.me = null
      else session.readable = false
      const { wrapper, close } = show()
      await flushPromises()
      expect(transport.get).not.toHaveBeenCalled()
      expect(wrapper.find('section').exists()).toBe(false)
      close()
    },
  )

  it.each(['owner', 'source', 'workout'])(
    'rejects a late request after changing %s',
    async (scope) => {
      const late = deferred<WorkoutHeartRatePage>()
      transport.get.mockReturnValueOnce(late.promise).mockImplementation((path: string) => {
        const url = new URL(path, 'https://synthetic.test')
        const currentWorkout = url.pathname.split('/').at(-2)!
        return page({
          source_id: url.searchParams.get('source_id')!,
          workout_id: currentWorkout,
          chunks: [{ ...chunk(0, 91), workout_id: currentWorkout }],
        })
      })
      const { wrapper, client, close } = show()
      await flushPromises()
      if (scope === 'owner') session.me = { id: 'synthetic-second-owner' }
      else if (scope === 'source') await wrapper.setProps({ sourceId: otherSource })
      else await wrapper.setProps({ workoutId: otherWorkout })
      await flushPromises()
      late.resolve(page({ chunks: [chunk(0, 72)] }))
      await flushPromises()
      expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(91)
      expect(wrapper.get('pre').text()).not.toContain('"bpm": 72')
      expect(cachedPages(client)).toEqual([])
      close()
    },
  )

  it('removes already loaded personal data on revoke and starts fresh when access returns', async () => {
    transport.get
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ chunks: [chunk(0, 93)] }))
    const { wrapper, client, close } = show()
    await flushPromises()
    session.readable = false
    await flushPromises()
    expect(wrapper.find('section').exists()).toBe(false)
    expect(cachedPages(client)).toEqual([])
    session.readable = true
    await flushPromises()
    expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(93)
    expect(requests()[1]!.searchParams.get('cursor')).toBeNull()
    close()
  })

  it('rejects an old request even when read access is revoked and restored in the same turn', async () => {
    const late = deferred<WorkoutHeartRatePage>()
    transport.get
      .mockReturnValueOnce(late.promise)
      .mockResolvedValueOnce(page({ chunks: [chunk(0, 94)] }))
    const { wrapper, close } = show()
    await flushPromises()
    session.readable = false
    session.readable = true
    await flushPromises()
    late.resolve(page())
    await flushPromises()
    expect(wrapper.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(94)
    expect(wrapper.get('pre').text()).not.toContain('"bpm": 72')
    close()
  })

  it('does not retain a late response after the owned view is unmounted', async () => {
    const late = deferred<WorkoutHeartRatePage>()
    transport.get.mockReturnValueOnce(late.promise)
    const { wrapper, client } = show()
    await flushPromises()
    wrapper.unmount()
    late.resolve(page())
    await flushPromises()
    expect(cachedPages(client)).toEqual([])
    client.clear()
  })

  it('keeps simultaneous archive readers independent across reset and unmount', async () => {
    transport.get
      .mockResolvedValueOnce(page({ next_cursor: 'reader-one-cursor' }))
      .mockResolvedValueOnce(page({ next_cursor: 'reader-two-cursor' }))
      .mockRejectedValueOnce(new Error('Synthetic reader one continuation failed'))
      .mockResolvedValueOnce(page({ fingerprint: 'b'.repeat(64), chunks: [chunk(0, 96)] }))
      .mockResolvedValueOnce(page({ chunks: [chunk(1, 76)] }))
    const firstVisible = ref(true)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(
      defineComponent({
        components: { WorkoutHeartRateHistory },
        setup: () => ({ workout, source, firstVisible }),
        template: `<div>
        <WorkoutHeartRateHistory v-if="firstVisible" :workout-id="workout" :source-id="source" timezone="UTC" />
        <WorkoutHeartRateHistory :workout-id="workout" :source-id="source" timezone="UTC" />
      </div>`,
      }),
      {
        global: {
          plugins: [i18n, [VueQueryPlugin, { queryClient: client }]],
          stubs: { WorkoutHeartRateChart: true },
        },
      },
    )
    await flushPromises()
    const readers = wrapper.findAllComponents(WorkoutHeartRateHistory)
    expect(transport.get).toHaveBeenCalledTimes(2)
    await click(readers[0]!, more())
    expect(readers[0]!.findComponent(WorkoutHeartRateChart).exists()).toBe(false)
    expect(readers[1]!.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(72)
    await click(readers[0]!, restart)
    expect(readers[0]!.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(96)
    expect(readers[1]!.getComponent(WorkoutHeartRateChart).props('samples')[0]!.bpm).toBe(72)
    firstVisible.value = false
    await flushPromises()
    const remaining = wrapper.getComponent(WorkoutHeartRateHistory)
    expect(client.getQueryCache().findAll()).toHaveLength(1)
    await click(remaining, more())
    expect(requests()[4]!.searchParams.get('cursor')).toBe('reader-two-cursor')
    expect(remaining.getComponent(WorkoutHeartRateChart).props('samples')).toHaveLength(4)
    wrapper.unmount()
    client.clear()
  })
})

describe('workout pulse snapshot response contract', () => {
  it.each([
    { fingerprint: 'unknown' },
    { fingerprint: 'g'.repeat(64) },
    { report_ref: { id: report, revision: 0 } },
    { report_ref: null },
    { next_cursor: '' },
    { next_cursor: 'x'.repeat(501) },
  ])('rejects malformed snapshot metadata %#', (invalid) => {
    expect(WorkoutHeartRatePage.safeParse({ ...page(), ...invalid }).success).toBe(false)
  })
})
