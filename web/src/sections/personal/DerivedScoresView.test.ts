import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import DerivedScores from './DerivedScores.vue'
import { DerivedScore } from './derivedScores'
import {
  producerArchiveItem,
  producerHomeArchiveItem,
  producerOwnerSleepScore,
  producerRefresh,
  producerRhrScore,
  producerSummary,
  producerSummaryPage,
} from './derivedScores.fixtures'

const transport = vi.hoisted(() => ({ get: vi.fn(), operate: vi.fn(), failed: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({
    operate: transport.operate,
    failed: transport.failed,
    unresolved: ref([]),
    describe: vi.fn(),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner' }, can: () => true }),
}))
beforeEach(() => vi.resetAllMocks())
function show(from = '2026-10-09') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(DerivedScores, {
    props: { from, to: '2026-10-09', timezone: 'UTC', today: '2026-10-09' },
    global: {
      plugins: [
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        [VueQueryPlugin, { queryClient: client }],
      ],
    },
  })
  return {
    wrapper,
    close: () => {
      wrapper.unmount()
      client.clear()
    },
  }
}
const action = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll('button').find((button) => button.text() === text)!

describe('stored summary and immutable evidence', () => {
  it('opens a real Home-shaped missing-current archive and preserves all three producer baselines', async () => {
    const archive = producerHomeArchiveItem()
    const saved = JSON.stringify(archive)
    const summary = producerSummary(archive.score, archive.definition_snapshot.label)
    transport.get.mockImplementation((path: string) =>
      Promise.resolve(
        path.startsWith('/v1/personal/derived-scores?') ? producerSummaryPage([summary]) : archive,
      ),
    )
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.text()).toContain('Готовность · главный экран Soul')
    expect(wrapper.find('[data-testid="derived-baseline"]').exists()).toBe(false)
    await action(wrapper, 'Открыть сохранённые исходные данные').trigger('click')
    await flushPromises()
    const baselines = wrapper.get('[data-testid="derived-baseline"]')
    expect(baselines.text()).toContain('wrapper_K_z0')
    expect(baselines.text()).toContain('oy5.t/mbb.c/z5e.n')
    expect(baselines.text()).toContain('pya.d/e/a')
    const components = wrapper.findAll('[data-testid="derived-component"]')
    expect(components.map((component) => component.text()).join(' ')).toContain(
      'Шаги за день по сохранённой истории: 80',
    )
    expect(components.map((component) => component.text()).join(' ')).toContain(
      'Шаги во время сна, вычитаемые из активности: 80',
    )
    expect(wrapper.text()).toContain(
      'Не удалось подтвердить текущий счётчик шагов и дату его обновления.',
    )
    expect(wrapper.text()).toContain('Отчёт активности')
    expect(wrapper.find('.text-xl').exists()).toBe(false)
    expect(transport.get).toHaveBeenCalledTimes(2)
    expect(transport.operate).not.toHaveBeenCalled()
    expect(JSON.stringify(archive)).toBe(saved)
    close()
  })
  it('fetches full producer baselines and references only after the owner opens details', async () => {
    const sleep = producerOwnerSleepScore()
    const summaries = [
      producerSummary(),
      { ...producerSummary(sleep, 'Сон · экран сна Soul'), result_id: 'b'.repeat(64) },
    ]
    const rhrArchive = producerArchiveItem()
    const sleepArchive = {
      ...rhrArchive,
      result_id: 'b'.repeat(64),
      definition_id: sleep.definition_id,
      source_scope: null,
      score: sleep,
      definition_snapshot: {
        ...rhrArchive.definition_snapshot,
        id: sleep.definition_id,
        label: 'Сон · экран сна Soul',
        unit: sleep.unit,
        required_inputs: ['screen_sleep_minutes', 'screen_deep_minutes', 'sleep_goal_minutes'],
      },
      normalized_inputs: {
        snapshot: { source_id: null },
        facts: { screen_sleep_minutes: { value: 480, unit: 'min', refs: sleep.input_revisions } },
        requested_inputs: ['screen_sleep_minutes'],
      },
    }
    transport.get.mockImplementation((path: string) =>
      Promise.resolve(
        path.startsWith('/v1/personal/derived-scores?')
          ? producerSummaryPage(summaries)
          : path.endsWith(rhrArchive.result_id)
            ? rhrArchive
            : sleepArchive,
      ),
    )
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-score"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="derived-baseline"]').exists()).toBe(false)
    expect(transport.get).toHaveBeenCalledTimes(1)
    const buttons = wrapper
      .findAll('button')
      .filter((button) => button.text() === 'Открыть сохранённые исходные данные')
    await buttons[0]!.trigger('click')
    await flushPromises()
    await buttons[1]!.trigger('click')
    await flushPromises()
    expect(transport.get.mock.calls.slice(1).map(([path]) => path)).toEqual([
      `/v1/personal/derived-result-versions/${rhrArchive.result_id}`,
      `/v1/personal/derived-result-versions/${sleepArchive.result_id}`,
    ])
    expect(wrapper.text()).toContain('Числовой ряд · 2 значений')
    expect(wrapper.text()).toContain('Состояние выбора источников')
    expect(wrapper.text()).toContain('Момент расчёта')
    expect(wrapper.text()).toContain('Настройка владельца · sleep')
    const bases = wrapper.findAll('[data-testid="derived-baseline"]')
    expect(bases[0]!.text()).toContain('2 показаний')
    expect(bases[1]!.text()).toContain('SleepViewModel.n')
    expect(bases[1]!.text()).not.toContain('показаний')
    expect(wrapper.text()).not.toContain('NaN')
    expect(transport.operate).not.toHaveBeenCalled()
    close()
  })
  it('shows missing stored results without a preview, default score or automatic write', async () => {
    transport.get.mockResolvedValue({
      ...producerSummaryPage([]),
      refresh: producerRefresh(true, 2, 1),
    })
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-score"]')).toHaveLength(0)
    expect(wrapper.text()).toContain('Сохранённых расчётов за этот период пока нет')
    expect(wrapper.text()).toContain('Ожидают пересчёта: 2 · Не завершились: 1')
    expect(transport.operate).not.toHaveBeenCalled()
    expect(transport.get).toHaveBeenCalledTimes(1)
    close()
  })
  it('keeps source definitions separate and never shows an unqualified neutral value', async () => {
    const score = DerivedScore.parse({
      definition_id: 'readiness',
      formula_version: 'v1',
      qualification: 'unqualified',
      status: 'unqualified',
      value: 50,
      unit: 'index',
      components: [],
      missing: ['hrv_subtype'],
      confidence: { level: 'low', available_inputs: 0, required_inputs: 1 },
      coverage: { observed: 0, expected: 1, ratio: 0 },
      quality: ['definition_unqualified'],
      context: {
        evaluation: 'night',
        date: '2026-10-09',
        time_zone: 'UTC',
        as_of: '2026-10-09T08:00:00Z',
        source_id: 'ring-a',
      },
      input_revisions: [],
      baseline: null,
      fingerprint: 'score',
    })
    const first = producerSummary(score, 'Сохранённая готовность')
    const second = {
      ...producerSummary(
        { ...score, context: { ...score.context, source_id: 'ring-b' } },
        'Сохранённая готовность',
      ),
      result_id: 'b'.repeat(64),
    }
    transport.get.mockResolvedValue(producerSummaryPage([first, second]))
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-score"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('Расчётные оценки показаны отдельно')
    expect(wrapper.text()).toContain('Определение ещё не подтверждено')
    expect(wrapper.text()).toContain('hrv_subtype')
    expect(wrapper.text()).toContain('ring-a')
    expect(wrapper.text()).toContain('ring-b')
    expect(wrapper.text()).toContain('Сохранённая готовность')
    expect(wrapper.text()).not.toContain('50 index')
    expect(transport.operate).not.toHaveBeenCalled()
    expect(transport.get).toHaveBeenCalledTimes(1)
    close()
  })
  it('explains missing summary inputs without changing values or source', async () => {
    const score = DerivedScore.parse({
      ...producerRhrScore(),
      status: 'unavailable',
      value: null,
      missing: ['home_steps_day_db', 'home_steps_anchor', 'future_missing'],
      quality: ['protocol_unverified', 'future_quality', 'home_current_wrapper_unavailable'],
      components: [
        {
          id: 'home_steps_day_db',
          value: null,
          unit: 'steps',
          quality: ['home_day_db_source_query_unqualified'],
          synthetic: false,
        },
        {
          id: 'home_steps_anchor',
          value: null,
          unit: 'steps',
          quality: ['home_anchor_sleep_timestamp_order'],
          synthetic: false,
        },
        {
          id: 'home_steps_current',
          value: 0,
          unit: 'steps',
          quality: ['home_current_reference_zero'],
          synthetic: false,
        },
        { id: 'home_steps_session', value: 12, unit: 'steps', quality: [], synthetic: false },
      ],
    })
    const summary = producerSummary(score)
    const archive = { ...producerArchiveItem(), score }
    const saved = JSON.stringify(archive)
    transport.get.mockImplementation((path: string) =>
      Promise.resolve(
        path.startsWith('/v1/personal/derived-scores?') ? producerSummaryPage([summary]) : archive,
      ),
    )
    const { wrapper, close } = show()
    await flushPromises()
    const card = wrapper.get('[data-testid="derived-score"]')
    const explanations = card.get('[data-testid="derived-missing-explanations"]')
    expect(explanations.text()).toContain('Шаги за день по сохранённой истории')
    expect(explanations.text()).toContain('Шаги во время сна, вычитаемые из активности')
    expect(explanations.text()).toContain('Назначение дополнительного условия ещё не описано.')
    expect(explanations.text()).not.toContain('home_steps_anchor')
    expect(explanations.text()).not.toContain('home_steps_day_db')
    expect(card.get('[data-testid="derived-missing-codes"]').attributes('open')).toBeUndefined()
    expect(
      card.findAll('[data-testid="derived-missing-codes"] code').map((code) => code.text()),
    ).toEqual(score.missing)
    expect(transport.get).toHaveBeenCalledTimes(1)
    await action(wrapper, 'Открыть сохранённые исходные данные').trigger('click')
    await flushPromises()
    const components = card.findAll('[data-testid="derived-component"]')
    expect(components.map((component) => component.text())).toEqual([
      expect.stringContaining('Шаги за день по сохранённой истории: Нет данных'),
      expect.stringContaining('Шаги во время сна, вычитаемые из активности: Нет данных'),
      expect.stringContaining('Текущие шаги в контексте главного экрана Soul: 0'),
      expect.stringContaining('Шаги сеанса главного экрана Soul: 12'),
    ])
    const componentCodes = card.findAll('[data-testid="derived-component-codes"]')
    expect(componentCodes.map((detail) => detail.get('code').text())).toEqual([
      'home_steps_day_db',
      'home_steps_anchor',
      'home_steps_current',
      'home_steps_session',
    ])
    expect(componentCodes.every((detail) => detail.attributes('open') === undefined)).toBe(true)
    const reason = components[0]!.get('[data-testid="record-quality-explanations"]')
    expect(reason.text()).toContain('История шагов за день не подтверждена для расчёта готовности.')
    expect(reason.text()).not.toContain('home_day_db_source_query_unqualified')
    expect(components[1]!.get('[data-testid="record-quality-explanations"]').text()).toContain(
      'Несколько записей сна с одинаковым временем расходятся; выбрать одну нельзя.',
    )
    expect(components[2]!.get('[data-testid="record-quality-explanations"]').text()).toContain(
      'Ноль отражает сброс счётчика или прежнюю дату; это не свежее измерение шагов.',
    )
    const quality = card.get('[data-testid="record-quality-explanations"]')
    expect(quality.text()).toContain('Формат данных этого источника ещё не сверен.')
    expect(quality.text()).toContain('Смысл дополнительной отметки качества')
    expect(quality.text()).toContain(
      'Не удалось подтвердить текущий счётчик шагов и дату его обновления.',
    )
    const storedQuality = card.get('[data-testid="record-quality-codes"]')
    expect(storedQuality.findAll('code').map((code) => code.text())).toEqual(score.quality)
    expect(card.text()).toContain('Недостаточно данных')
    expect(card.text()).toContain(summary.context.source_id)
    expect(card.text()).toContain(summary.formula_version)
    expect(card.find('.text-xl').exists()).toBe(false)
    expect(transport.get).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(archive)).toBe(saved)
    expect(transport.operate).not.toHaveBeenCalled()
    close()
  })
  it('merges stable summary pages by day and shows the full instant count without loading all points', async () => {
    const instant = {
      ...producerSummary(),
      definition_id: 'soul-stress-from-hrv',
      unit: 'index',
      definition_snapshot: {
        ...producerSummary().definition_snapshot,
        id: 'soul-stress-from-hrv',
        label: 'Стресс по HRV Soul',
        unit: 'index',
      },
      context: { ...producerSummary().context, evaluation: 'instant' },
      series_count: 712,
    }
    const second = {
      ...producerSummary(),
      result_id: 'b'.repeat(64),
      context: { ...producerSummary().context, source_id: 'ring-b' },
    }
    transport.get
      .mockResolvedValueOnce({
        ...producerSummaryPage([instant], 'a'.repeat(64)),
        total_summaries: 2,
      })
      .mockResolvedValueOnce({ ...producerSummaryPage([second]), total_summaries: 2 })
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.get('[data-testid="derived-instant-count"]').text()).toContain('712')
    expect(wrapper.text()).toContain('Все точки, версии и происхождение расчёта')
    await action(wrapper, 'Загрузить ещё расчёты').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-score"]')).toHaveLength(2)
    expect(wrapper.findAll('h3')).toHaveLength(1)
    expect(wrapper.get('[data-testid="derived-summary-count"]').text()).toContain('2 / 2')
    const url = new URL(transport.get.mock.calls[1]![0], 'https://synthetic.invalid')
    expect(url.searchParams.get('cursor')).toBe('a'.repeat(64))
    expect(
      transport.get.mock.calls.every(([path]) =>
        String(path).startsWith('/v1/personal/derived-scores?'),
      ),
    ).toBe(true)
    close()
  })
  it.each(['2026-07-12', '2025-10-10'])(
    'requests a bounded stored summary page for a long period from %s',
    async (from) => {
      transport.get.mockResolvedValue({ ...producerSummaryPage(), from })
      const { wrapper, close } = show(from)
      await flushPromises()
      expect(wrapper.findAll('[data-testid="derived-score"]')).toHaveLength(1)
      const url = new URL(transport.get.mock.calls[0]![0], 'https://synthetic.invalid')
      expect(url.searchParams.get('from')).toBe(from)
      expect(url.searchParams.get('limit')).toBe('250')
      expect(transport.get).toHaveBeenCalledTimes(1)
      close()
    },
  )
  it('hides a changed summary collection until a coherent reload', async () => {
    transport.get
      .mockResolvedValueOnce({
        ...producerSummaryPage(undefined, 'a'.repeat(64)),
        total_summaries: 2,
      })
      .mockResolvedValueOnce(
        producerSummaryPage(
          [{ ...producerSummary(), result_id: 'b'.repeat(64) }],
          null,
          'changed-collection',
        ),
      )
    const { wrapper, close } = show()
    await flushPromises()
    await action(wrapper, 'Загрузить ещё расчёты').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="derived-score"]')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('Сохранённый набор изменился')
    close()
  })
})
