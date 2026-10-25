import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import DerivedResultVersions from './DerivedResultVersions.vue'
import { DerivedResultVersion } from './derivedScores'
import {
  captureReference,
  measurementReference,
  producerArchiveItem,
  producerRefresh,
} from './derivedScores.fixtures'

const transport = vi.hoisted(() => ({ get: vi.fn(), operate: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner' }, can: () => true }),
}))
const source = '11111111-1111-4111-8111-111111111111'
const another = '22222222-2222-4222-8222-222222222222'
function version(version: number, sourceId: string | null = source) {
  const formula = `formula-v${version}`
  return DerivedResultVersion.parse({
    result_id: String(version).repeat(64),
    version,
    definition_id: 'soul-night-recovery',
    formula_version: formula,
    source_scope: sourceId,
    date: '2026-10-09',
    evaluation: 'night',
    time_zone: 'UTC',
    as_of: '2026-10-09T08:00:00Z',
    temporal_basis: 'source_sample',
    score: {
      definition_id: 'soul-night-recovery',
      formula_version: formula,
      qualification: 'source_transform',
      status: 'available',
      value: 41 + version,
      unit: 'index',
      components: [],
      missing: [],
      confidence: {
        kind: 'input_coverage',
        level: 'medium',
        available_inputs: 2,
        required_inputs: 2,
      },
      coverage: { kind: 'input_presence', observed: 2, expected: 2, ratio: 1 },
      quality: [],
      context: {
        evaluation: 'night',
        date: '2026-10-09',
        time_zone: 'UTC',
        as_of: '2026-10-09T08:00:00Z',
        source_id: sourceId,
      },
      input_revisions: [measurementReference('hrv-fact', sourceId, 'hrv', version)],
      baseline: null,
      fingerprint: `score-${version}`,
    },
    definition_snapshot: {
      id: 'soul-night-recovery',
      label: version === 1 ? 'Старое сохранённое определение' : 'Новое сохранённое определение',
      formula_version: formula,
      qualification: 'source_transform',
      unit: 'index',
      required_inputs: ['hrv'],
      evidence: [`captured-evidence-${version}`],
    },
    normalized_inputs: {
      facts: [{ id: 'hrv-fact', revision: version, value: 32.5 }],
      requested_inputs: ['hrv'],
    },
    context_revisions: [{ ...captureReference, id: 'owner-context', revision: version }],
    input_adapter_version: `adapter-${version}`,
    implementation_digest: `implementation-${version}`,
    implementation_source: {
      format: 'git_blob_sha1',
      files: [
        { path: 'app/calculation.py', git_blob_sha1: 'a'.repeat(40), sha256: 'b'.repeat(64) },
      ],
    },
    operation_id: `original-operation-${version}`,
    created_at: '2026-10-09T09:00:00Z',
    is_current: version === 2,
  })
}
function page(
  items = [version(2), version(1)],
  cursor: string | null = null,
  fingerprint = 'unchanged-archive',
  scope: 'all' | 'source' | 'owner' = 'all',
) {
  return {
    from: '2026-10-09',
    to: '2026-10-09',
    time_zone: 'UTC',
    items,
    next_cursor: cursor,
    fingerprint,
    persistence: 'immutable_versions',
    refresh: producerRefresh(),
    total_versions: 2,
    scope,
  }
}
function show(sourceId?: string | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(DerivedResultVersions, {
    props: {
      from: '2026-10-09',
      to: '2026-10-09',
      timezone: 'UTC',
      today: '2026-10-09',
      ...(sourceId !== undefined ? { sourceId } : {}),
    },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } },
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
beforeEach(() => vi.clearAllMocks())
describe('immutable derived result archive', () => {
  it('preserves each stored definition, inputs and implementation without editing a result', async () => {
    transport.get.mockResolvedValue(page())
    const { wrapper, close } = show()
    await flushPromises()
    const rows = wrapper.findAll('[data-testid="derived-result-version"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Новое сохранённое определение · v2')
    expect(rows[1]!.text()).toContain('Старое сохранённое определение · v1')
    expect(rows[1]!.text()).toContain('captured-evidence-1')
    expect(rows[1]!.text()).toContain('adapter-1')
    expect(rows[1]!.text()).toContain('implementation-1')
    expect(rows[1]!.text()).toContain('owner-context')
    expect(rows[1]!.text()).toContain('"value": 32.5')
    expect(rows[1]!.find('a').text()).toBe('Исходные записи за этот день')
    expect(wrapper.text()).toContain('Сам результат не редактируется')
    expect(wrapper.text()).toContain('Версий в архиве: 2')
    expect(transport.operate).not.toHaveBeenCalled()
    expect(transport.get).toHaveBeenCalledTimes(1)
    close()
  })
  it('keeps missing conditions and age references separate for saved versions', async () => {
    const latest = version(2)
    const current = DerivedResultVersion.parse({
      ...latest,
      score: {
        ...latest.score,
        status: 'partial',
        missing: ['home_steps_anchor', 'future_missing'],
        components: [
          {
            id: 'ide_hrv_14d',
            value: 40,
            unit: 'ms',
            synthetic: true,
            reason: 'soul_age_reference',
          },
        ],
      },
    })
    const previous = version(1)
    const older = DerivedResultVersion.parse({
      ...previous,
      score: { ...previous.score, missing: ['night_hr:four_samples'] },
    })
    const original = JSON.stringify([current, older])
    transport.get.mockResolvedValue(page([current, older]))
    const { wrapper, close } = show()
    await flushPromises()
    const rows = wrapper.findAll('[data-testid="derived-result-version"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.get('[data-testid="derived-missing-explanations"]').text()).toContain(
      'Шаги во время сна, вычитаемые из активности',
    )
    expect(rows[0]!.text()).toContain('Возрастная опора формулы Soul; это не измерение.')
    expect(rows[0]!.text()).toContain('soul_age_reference')
    expect(rows[0]!.get('.text-xl').text()).toContain('43')
    expect(rows[1]!.get('[data-testid="derived-missing-explanations"]').text()).toContain(
      'Не менее четырёх пригодных точек пульса в ночном окне',
    )
    expect(rows[1]!.text()).not.toContain('Возрастная опора формулы Soul')
    expect(rows[1]!.text()).toContain(previous.formula_version)
    expect(rows[1]!.text()).toContain('"value": 32.5')
    expect(JSON.stringify([current, older])).toBe(original)
    expect(transport.operate).not.toHaveBeenCalled()
    expect(wrapper.find('[style]').exists()).toBe(false)
    close()
  })
  it('passes the selected source and cursor, and hides incoherent version pages', async () => {
    transport.get
      .mockResolvedValueOnce(page([version(2)], 'older-result', 'unchanged-archive', 'source'))
      .mockResolvedValueOnce(page([version(1)], null, 'changed-archive', 'source'))
    const { wrapper, close } = show(source)
    await flushPromises()
    const first = new URL(transport.get.mock.calls[0]![0], 'https://synthetic.invalid')
    expect(first.searchParams.get('source_id')).toBe(source)
    expect(first.searchParams.get('scope')).toBe('source')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Загрузить ещё версии')!
      .trigger('click')
    await flushPromises()
    const second = new URL(transport.get.mock.calls[1]![0], 'https://synthetic.invalid')
    expect(second.searchParams.get('cursor')).toBe('older-result')
    expect(second.searchParams.get('source_id')).toBe(source)
    expect(wrapper.findAll('[data-testid="derived-result-version"]')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('Архив изменился')
    close()
  })
  it('rejects a response from a different source instead of displaying its inputs', async () => {
    transport.get.mockResolvedValue(
      page([version(1, another)], null, 'unchanged-archive', 'source'),
    )
    const { wrapper, close } = show(source)
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-result-version"]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('captured-evidence-1')
    close()
  })
  it('requires captured score and definition identity to agree', () => {
    const saved = version(1)
    expect(
      DerivedResultVersion.safeParse({
        ...saved,
        definition_snapshot: { ...saved.definition_snapshot, formula_version: 'unrelated-formula' },
      }).success,
    ).toBe(false)
    expect(DerivedResultVersion.safeParse({ ...saved, source_scope: another }).success).toBe(false)
  })
  it('renders real producer context references and composite baseline in an archived version', async () => {
    const saved = DerivedResultVersion.parse(producerArchiveItem())
    transport.get.mockResolvedValue(page([saved]))
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-result-version"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="derived-baseline"]').text()).toContain(
      'lower_fifth_percentile',
    )
    expect(wrapper.text()).toContain('Числовой ряд · 2 значений')
    expect(wrapper.text()).toContain('Состояние выбора источников')
    expect(wrapper.text()).toContain('Момент расчёта')
    expect(wrapper.text()).not.toContain('NaN')
    close()
  })
  it('retains owner-global results beside a selected ring without attributing them to it', async () => {
    transport.get.mockResolvedValue(
      page([version(2), version(1, null)], null, 'unchanged-archive', 'source'),
    )
    const { wrapper, close } = show(source)
    await flushPromises()
    const rows = wrapper.findAll('[data-testid="derived-result-version"]')
    expect(rows).toHaveLength(2)
    expect(rows[1]!.text()).toContain(
      'Контекст владельца · все источники; не относится к выбранному кольцу',
    )
    close()
  })
  it('requests owner scope before paging and uses its correctly scoped count', async () => {
    transport.get.mockResolvedValue(page([version(1, null)], null, 'owner-archive', 'owner'))
    const { wrapper, close } = show(null)
    await flushPromises()
    const url = new URL(transport.get.mock.calls[0]![0], 'https://synthetic.invalid')
    expect(url.searchParams.get('scope')).toBe('owner')
    expect(url.searchParams.has('source_id')).toBe(false)
    expect(wrapper.findAll('[data-testid="derived-result-version"]')).toHaveLength(1)
    close()
  })
  it('keeps valid unchanged second-page versions visible', async () => {
    transport.get
      .mockResolvedValueOnce(page([version(2)], 'older-result'))
      .mockResolvedValueOnce(page([version(1)]))
    const { wrapper, close } = show()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Загрузить ещё версии')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-result-version"]')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('Архив изменился')
    close()
  })
  it('shows shared-owner pending work separately without blocking an unrelated source archive', async () => {
    transport.get.mockResolvedValue({
      ...page([version(2)], null, 'unchanged-archive', 'source'),
      refresh: { ...producerRefresh(), owner_pending: true, owner_pending_runs: 1 },
    })
    const { wrapper, close } = show(source)
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-result-version"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('Ожидают пересчёта: 0')
    expect(wrapper.text()).toContain(
      'Общие показатели владельца по всем источникам: ожидают пересчёта 1',
    )
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === 'Обновить сохранённые версии')!
        .attributes('disabled'),
    ).toBeUndefined()
    close()
  })
})
