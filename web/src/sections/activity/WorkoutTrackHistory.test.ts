import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import WorkoutTrackHistory from './WorkoutTrackHistory.vue'
import {
  WorkoutTrackSummaryVersionsPage,
  type WorkoutTrackPage,
  type WorkoutTrackSummarySnapshot,
} from './workoutTrack'

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

function routePage(overrides: Partial<WorkoutTrackPage> = {}): WorkoutTrackPage {
  return {
    workout_id: 'synthetic-workout',
    source_id: 'synthetic-source',
    workout: {
      start_at: '2026-10-06T10:00:00Z',
      end_at: '2026-10-06T10:10:00Z',
      timezone: 'UTC',
      status: 'completed',
      trigger: 'manual',
    },
    chunks: [],
    segments: [],
    summary: null,
    next_cursor: null,
    fingerprint: 'synthetic-route-fingerprint',
    ...overrides,
  }
}
function snapshot(
  overrides: Partial<WorkoutTrackSummarySnapshot> = {},
): WorkoutTrackSummarySnapshot {
  return {
    algorithm_version: 'synthetic-gps-v2',
    unit: 'm',
    value_meters: 2316,
    contexts: {
      workout: {
        distance_meters: 2316,
        usable_points: 5,
        reported_points: 7,
        gap_count: 1,
        coverage_seconds: 10,
        usable_segments: 3,
        quality: ['gps_estimate', 'partial', 'paused'],
        paused_points: 2,
        paused_seconds: 30,
        retained_context_field: { reason: 'synthetic-detail' },
      },
      recovery: {
        distance_meters: null,
        usable_points: 0,
        reported_points: 0,
        gap_count: 0,
        coverage_seconds: 0,
        usable_segments: 0,
        quality: ['gps_estimate', 'no_usable_segments'],
      },
      retained_context: { evidence: 'synthetic-future-context' },
    },
    speed_limit_meters_per_second: 100,
    max_segment_seconds: 15,
    max_accuracy_meters: 50,
    pause_policy: 'exclude_points_and_crossing_segments-v1',
    report_ref: { id: 'synthetic-report', revision: 2, retained_report_field: true },
    input_refs: [
      { id: 'synthetic-input', sequence: 4, hash: 'synthetic-input-hash', retained_input_field: 9 },
    ],
    retained_top_field: { included: true, nullable: null },
    ...overrides,
  }
}
function versionsPage(
  overrides: Partial<WorkoutTrackSummaryVersionsPage> = {},
): WorkoutTrackSummaryVersionsPage {
  return {
    workout_id: 'synthetic-workout',
    source_id: 'synthetic-source',
    items: [{ revision: 2, snapshot: snapshot(), is_latest: true }],
    latest_revision: 2,
    fingerprint: 'synthetic-archive-fingerprint',
    next_cursor: null,
    ...overrides,
  }
}
function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(WorkoutTrackHistory, {
    props: { workoutId: 'synthetic-workout', sourceId: 'synthetic-source', timezone: 'UTC' },
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
  const details = wrapper.get<HTMLDetailsElement>('[data-testid="workout-track-summary-archive"]')
  details.element.open = true
  await details.trigger('toggle')
  await flushPromises()
}
async function clickButton(wrapper: ReturnType<typeof show>['wrapper'], label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}
function archiveCalls() {
  return transport.get.mock.calls
    .map(([path]) => new URL(path as string, 'https://synthetic.test'))
    .filter((url) => url.pathname.endsWith('/track-summary-versions'))
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function currentPage(path: string) {
  const url = new URL(path, 'https://synthetic.test')
  return routePage({
    source_id: url.searchParams.get('source_id')!,
    workout_id: decodeURIComponent(url.pathname.split('/').at(-2)!),
  })
}

beforeEach(() => {
  transport.get.mockReset()
  session.me = { id: 'synthetic-owner' }
  session.readable = true
})

describe('GPS archive without a route', () => {
  it('accepts a saved workout without GPS and leaves the version query lazy', async () => {
    transport.get.mockImplementation((path: string) => {
      if (path.includes('/track-summary-versions?')) return versionsPage()
      return routePage()
    })
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.text()).toContain('Координат за эту тренировку нет')
    expect(wrapper.text()).toContain('Это не означает отсутствие записи пульса')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('0 км')
    expect(archiveCalls()).toHaveLength(0)
    expect(wrapper.text()).toContain('Версии расчёта GPS')
    close()
  })
  it('rejects mixed current summaries even when the raw input fingerprint is unchanged', async () => {
    const page = (summaryFingerprint: string, next: string | null) =>
      routePage({
        next_cursor: next,
        fingerprint: 'same-inputs',
        summary: {
          algorithm_version: 'synthetic-gps-v1',
          value_meters: null,
          unit: 'm',
          contexts: {
            recovery: {
              distance_meters: null,
              usable_points: 0,
              reported_points: 0,
              gap_count: 0,
              coverage_seconds: 0,
              quality: ['no_usable_segments'],
            },
          },
          input_refs: [],
          fingerprint: summaryFingerprint,
        },
      })
    transport.get.mockImplementation((path: string) => {
      if (path.includes('/track-summary-versions?')) return versionsPage()
      return path.includes('cursor=')
        ? page('new-summary', null)
        : page('old-summary', 'second-page')
    })
    const { wrapper, close } = show()
    await flushPromises()
    await clickButton(wrapper, 'Загрузить ещё')
    expect(wrapper.get('[role="alert"]').text()).toContain('Маршрут изменился')
    expect(wrapper.text()).not.toContain('Координат за эту тренировку нет')
    expect(wrapper.find('pre').exists()).toBe(false)
    expect(archiveCalls()).toHaveLength(0)
    close()
  })
})

describe('immutable GPS calculation versions', () => {
  it.each(['signed-out', 'no-read-scope'] as const)(
    'does not query an archive when %s',
    async (state) => {
      if (state === 'signed-out') session.me = null
      else session.readable = false
      const { wrapper, close } = show()
      await flushPromises()
      expect(transport.get).not.toHaveBeenCalled()
      expect(wrapper.find('[data-testid="workout-track-history"]').exists()).toBe(false)
      close()
    },
  )
  it('shows latest and previous snapshots, their evidence and every saved field without changing the current summary', async () => {
    const latest = snapshot()
    const previous = snapshot({
      algorithm_version: 'synthetic-gps-v1',
      value_meters: null,
      contexts: {
        workout: {
          distance_meters: null,
          usable_points: 1,
          reported_points: 1,
          gap_count: 0,
          coverage_seconds: 0,
          usable_segments: 0,
          quality: ['no_usable_segments'],
        },
        recovery: latest.contexts.recovery,
      },
      report_ref: { id: 'synthetic-report', revision: 1 },
      input_refs: [],
    })
    const currentSnapshot = snapshot({ algorithm_version: 'synthetic-current-only' })
    delete currentSnapshot.contexts.retained_context
    const raw = routePage({
      summary: { ...currentSnapshot, fingerprint: 'current-only' },
      chunks: [{ id: 'synthetic-raw-chunk', full_raw_field: 'kept' }],
    })
    transport.get.mockImplementation((path: string) => {
      if (path.includes('/track-summary-versions?'))
        return versionsPage({
          items: [
            { revision: 2, snapshot: latest, is_latest: true },
            { revision: 1, snapshot: previous, is_latest: false },
          ],
        })
      return raw
    })
    const { wrapper, close } = show()
    await flushPromises()
    const current = wrapper.get('[data-testid="workout-track-current-details"]')
    expect(JSON.parse(current.findAll('pre')[0]!.text())).toEqual(raw.summary)
    expect(JSON.parse(current.findAll('pre')[1]!.text())).toEqual(raw.chunks)
    await openArchive(wrapper)
    const versions = wrapper.findAll('[data-testid="workout-track-summary-version"]')
    expect(versions).toHaveLength(2)
    expect(versions[0]!.text()).toContain('Версия 2 · Последняя сохранённая')
    expect(versions[1]!.text()).toContain('Версия 1 · Предыдущая')
    expect(versions[1]!.text()).toContain('Дистанция GPS: неизвестна')
    expect(versions[1]!.text()).not.toContain('0 км')
    expect(versions[0]!.text()).toContain('Формула: synthetic-gps-v2')
    expect(versions[0]!.text()).toContain('Качество: оценка по GPS, неполное покрытие, есть паузы')
    expect(versions[0]!.text()).toContain('Учтённых отрезков: 3')
    expect(versions[0]!.text()).toContain('Координат во время пауз: 2')
    expect(versions[0]!.text()).toContain('Время пауз: 30 с')
    expect(versions[0]!.text()).toContain('Предел скорости: 100 м/с')
    expect(versions[0]!.text()).toContain('Исходная запись тренировки: synthetic-report · версия 2')
    expect(versions[0]!.text()).toContain(
      'Запись synthetic-input · последовательность 4 · хеш synthetic-input-hash',
    )
    expect(JSON.parse(versions[0]!.get('pre').text())).toEqual(latest)
    expect(JSON.parse(versions[1]!.get('pre').text())).toEqual(previous)
    expect(JSON.parse(current.findAll('pre')[0]!.text())).toEqual(raw.summary)
    expect(JSON.parse(current.findAll('pre')[1]!.text())).toEqual(raw.chunks)
    expect(archiveCalls()[0]!.searchParams.get('source_id')).toBe('synthetic-source')
    expect(archiveCalls()[0]!.searchParams.get('limit')).toBe('20')
    expect(
      transport.get.mock.calls.filter(([path]) => (path as string).includes('/track?')),
    ).toHaveLength(1)
    close()
  })
  it('keeps absent historical metadata unknown and preserves future nested fields without defaults', async () => {
    const historical = snapshot()
    delete historical.pause_policy
    delete historical.speed_limit_meters_per_second
    delete historical.max_accuracy_meters
    delete historical.max_segment_seconds
    delete historical.contexts.workout!.usable_segments
    const page = versionsPage({ items: [{ revision: 2, snapshot: historical, is_latest: true }] })
    expect(WorkoutTrackSummaryVersionsPage.parse(page).items[0]!.snapshot).toEqual(historical)
    transport.get.mockImplementation((path: string) =>
      path.includes('/track-summary-versions?') ? page : routePage(),
    )
    const { wrapper, close } = show()
    await openArchive(wrapper)
    const version = wrapper.get('[data-testid="workout-track-summary-version"]')
    expect(version.text()).toContain('Учтённых отрезков: неизвестно')
    expect(version.text()).toContain('Предел скорости: не сохранён')
    expect(version.text()).toContain('Правило пауз: не сохранено')
    const rendered = JSON.parse(version.get('pre').text())
    expect(rendered).toEqual(historical)
    expect(rendered).not.toHaveProperty('pause_policy')
    expect(rendered.contexts.workout).not.toHaveProperty('usable_segments')
    close()
  })
  it('shows an empty saved archive without inventing a GPS value', async () => {
    transport.get.mockImplementation((path: string) =>
      path.includes('/track-summary-versions?')
        ? versionsPage({ items: [], latest_revision: null })
        : routePage(),
    )
    const { wrapper, close } = show()
    await openArchive(wrapper)
    expect(wrapper.text()).toContain('Сохранённых версий расчёта GPS пока нет')
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('0 км')
    close()
  })
  it('loads older revisions through its own cursor without fetching the next raw route page', async () => {
    const initial = versionsPage({
      items: [{ revision: 3, snapshot: snapshot(), is_latest: true }],
      latest_revision: 3,
      next_cursor: 'synthetic-versions-cursor',
    })
    const older = versionsPage({
      items: [
        { revision: 2, snapshot: snapshot(), is_latest: false },
        { revision: 1, snapshot: snapshot(), is_latest: false },
      ],
      latest_revision: 3,
    })
    transport.get.mockImplementation((path: string) => {
      if (!path.includes('/track-summary-versions?'))
        return routePage({ next_cursor: 'raw-route-cursor' })
      return path.includes('cursor=') ? older : initial
    })
    const { wrapper, close } = show()
    await openArchive(wrapper)
    await clickButton(wrapper, 'Загрузить ещё версии')
    const versions = wrapper.findAll('[data-testid="workout-track-summary-version"]')
    expect(versions.map((version) => version.attributes('data-revision'))).toEqual(['3', '2', '1'])
    expect(
      versions.filter((version) => version.text().includes('Последняя сохранённая')),
    ).toHaveLength(1)
    expect(archiveCalls()[1]!.searchParams.get('cursor')).toBe('synthetic-versions-cursor')
    expect(
      transport.get.mock.calls.filter(([path]) => (path as string).includes('/track?')),
    ).toHaveLength(1)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    close()
  })
  it.each([
    'fingerprint',
    'latest_revision',
    'latest_marker',
    'duplicate_revision',
    'order',
  ] as const)(
    'withholds a mixed archive on %s conflict and reloads from its first page',
    async (conflict) => {
      const initial = versionsPage({ next_cursor: 'synthetic-versions-cursor' })
      const second = versionsPage({
        items: [{ revision: 1, snapshot: snapshot(), is_latest: false }],
      })
      if (conflict === 'fingerprint') second.fingerprint = 'different-lineage'
      if (conflict === 'latest_revision') second.latest_revision = 3
      if (conflict === 'latest_marker') second.items[0]!.is_latest = true
      if (conflict === 'duplicate_revision') second.items[0]!.revision = 2
      if (conflict === 'order') second.items[0]!.revision = 3
      let request = 0
      transport.get.mockImplementation((path: string) => {
        if (!path.includes('/track-summary-versions?')) return routePage()
        request++
        if (request === 1) return initial
        if (request === 2) return second
        return versionsPage({
          items: [
            {
              revision: 3,
              snapshot: snapshot({ algorithm_version: 'synthetic-reloaded' }),
              is_latest: true,
            },
          ],
          latest_revision: 3,
          fingerprint: 'reloaded-lineage',
        })
      })
      const { wrapper, close } = show()
      await openArchive(wrapper)
      expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(1)
      await clickButton(wrapper, 'Загрузить ещё версии')
      expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
      expect(wrapper.get('[role="alert"]').text()).toContain('Архив расчётов изменился')
      expect(wrapper.text()).toContain('Координат за эту тренировку нет')
      await clickButton(wrapper, 'Обновить версии GPS')
      expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(1)
      expect(wrapper.text()).toContain('synthetic-reloaded')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(archiveCalls()[2]!.searchParams.has('cursor')).toBe(false)
      expect(archiveCalls()).toHaveLength(3)
      close()
    },
  )
  it('withholds already loaded versions after next-page failure and reloads without the failed cursor', async () => {
    let request = 0
    transport.get.mockImplementation((path: string) => {
      if (!path.includes('/track-summary-versions?')) return routePage()
      request++
      if (request === 1) return versionsPage({ next_cursor: 'synthetic-failed-cursor' })
      if (request === 2) return Promise.reject(new Error('Synthetic next-page failure'))
      return versionsPage()
    })
    const { wrapper, close } = show()
    await openArchive(wrapper)
    await clickButton(wrapper, 'Загрузить ещё версии')
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('следующая страница не получена')
    await clickButton(wrapper, 'Обновить версии GPS')
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(1)
    expect(archiveCalls()[2]!.searchParams.has('cursor')).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    close()
  })
  it.each(['source_id', 'workout_id'] as const)(
    'rejects an archive response for another %s',
    async (field) => {
      transport.get.mockImplementation((path: string) =>
        path.includes('/track-summary-versions?')
          ? versionsPage({ [field]: 'synthetic-other-identity' })
          : routePage(),
      )
      const { wrapper, close } = show()
      await openArchive(wrapper)
      expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
      expect(wrapper.text()).not.toContain('synthetic-input-hash')
      close()
    },
  )
  it.each(['owner', 'source', 'workout'] as const)(
    'closes details across a %s change and discards the old in-flight response',
    async (boundary) => {
      const pending = deferred<WorkoutTrackSummaryVersionsPage>()
      let request = 0
      transport.get.mockImplementation((path: string) => {
        if (!path.includes('/track-summary-versions?')) return currentPage(path)
        request++
        if (request === 1) return pending.promise
        const url = new URL(path, 'https://synthetic.test')
        return versionsPage({
          source_id: url.searchParams.get('source_id')!,
          workout_id: decodeURIComponent(url.pathname.split('/').at(-2)!),
          items: [
            {
              revision: 2,
              snapshot: snapshot({ algorithm_version: 'synthetic-new-boundary' }),
              is_latest: true,
            },
          ],
        })
      })
      const { wrapper, client, close } = show()
      await openArchive(wrapper)
      if (boundary === 'owner') session.me = { id: 'synthetic-other-owner' }
      if (boundary === 'source') await wrapper.setProps({ sourceId: 'synthetic-other-source' })
      if (boundary === 'workout') await wrapper.setProps({ workoutId: 'synthetic-other-workout' })
      await flushPromises()
      pending.resolve(
        versionsPage({
          items: [
            {
              revision: 2,
              snapshot: snapshot({ algorithm_version: 'synthetic-old-private' }),
              is_latest: true,
            },
          ],
        }),
      )
      await flushPromises()
      expect(
        wrapper.get<HTMLDetailsElement>('[data-testid="workout-track-summary-archive"]').element
          .open,
      ).toBe(false)
      expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
      expect(wrapper.text()).not.toContain('synthetic-old-private')
      expect(
        client.getQueryData([
          'personal',
          'workout-track-summary-versions',
          'synthetic-owner',
          'synthetic-source',
          'synthetic-workout',
        ]),
      ).toBeUndefined()
      await openArchive(wrapper)
      expect(wrapper.text()).toContain('synthetic-new-boundary')
      expect(wrapper.text()).not.toContain('synthetic-old-private')
      expect(archiveCalls()).toHaveLength(2)
      close()
    },
  )
  it('revokes loaded archive data with read access and fetches anew after access returns', async () => {
    transport.get.mockImplementation((path: string) =>
      path.includes('/track-summary-versions?') ? versionsPage() : routePage(),
    )
    const { wrapper, client, close } = show()
    await openArchive(wrapper)
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(1)
    session.readable = false
    await flushPromises()
    expect(wrapper.find('[data-testid="workout-track-history"]').exists()).toBe(false)
    expect(
      client.getQueryData([
        'personal',
        'workout-track-summary-versions',
        'synthetic-owner',
        'synthetic-source',
        'synthetic-workout',
      ]),
    ).toBeUndefined()
    session.readable = true
    await flushPromises()
    expect(
      wrapper.get<HTMLDetailsElement>('[data-testid="workout-track-summary-archive"]').element.open,
    ).toBe(false)
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
    await openArchive(wrapper)
    expect(archiveCalls()).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(1)
    close()
  })
  it('rejects an in-flight result after read access is revoked and restored in the same turn', async () => {
    const pending = deferred<WorkoutTrackSummaryVersionsPage>()
    let request = 0
    transport.get.mockImplementation((path: string) => {
      if (!path.includes('/track-summary-versions?')) return routePage()
      request++
      return request === 1 ? pending.promise : versionsPage()
    })
    const { wrapper, close } = show()
    await openArchive(wrapper)
    session.readable = false
    session.readable = true
    pending.resolve(
      versionsPage({
        items: [
          {
            revision: 2,
            snapshot: snapshot({ algorithm_version: 'synthetic-revoked-private' }),
            is_latest: true,
          },
        ],
      }),
    )
    await flushPromises()
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('synthetic-revoked-private')
    await openArchive(wrapper)
    expect(archiveCalls()).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="workout-track-summary-version"]')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('synthetic-revoked-private')
    close()
  })
})
