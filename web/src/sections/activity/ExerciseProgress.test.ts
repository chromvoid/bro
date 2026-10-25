import { RouterLinkStub, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import ExerciseProgress from './ExerciseProgress.vue'
import { Progress } from './api'

const mocks = vi.hoisted(() => ({ progress: null as unknown }))
vi.mock('./useActivity', () => ({ useExerciseProgress: () => mocks.progress }))

const result = () =>
  Progress.parse({
    exercise: { id: 'bench', revision: 1, name: 'Bench', load_basis: 'total' },
    from: '2026-10-01',
    to: '2026-10-10',
    daily: [{ date: '2026-10-05', working_sets: 2 }],
    series: [
      {
        snapshot: { name: 'Bench', equipment: 'Barbell', load_basis: 'total' },
        load_unit: 'kg',
        implement_count: 1,
        points: [
          {
            date: '2026-10-03',
            session_id: 'previous',
            session_exercise_id: 'performed-old',
            set_id: 'previous-set',
            repetitions: 8,
            load_value: '50',
            load_kg: '50',
          },
          {
            date: '2026-10-05',
            session_id: 'latest',
            session_exercise_id: 'performed-latest',
            set_id: 'heavy',
            repetitions: 5,
            load_value: '60',
            load_kg: '60',
          },
          {
            date: '2026-10-05',
            session_id: 'latest',
            session_exercise_id: 'performed-latest',
            set_id: 'repetitions',
            repetitions: 12,
            load_value: '40',
            load_kg: '40',
          },
        ],
      },
    ],
  })

function query(data: Progress | undefined) {
  return {
    data: ref(data),
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    isFetching: ref(false),
    error: ref(null),
    refetch: vi.fn(),
  }
}
function screen(selected = 'bench', canWrite = true) {
  return mount(ExerciseProgress, {
    props: {
      catalog: selected ? [result().exercise] : [],
      selected,
      canWrite,
      range: { from: '2026-10-01', to: '2026-10-10', kind: 'strength' },
      returnQuery: {
        tab: 'progress',
        day: '2026-10-04',
        end: '2026-10-08',
        period: '7',
        kind: 'strength',
      },
    },
    global: {
      plugins: [i18n],
      stubs: { RouterLink: RouterLinkStub, LineChart: true },
    },
  })
}

describe('strength progressive detail', () => {
  beforeEach(() => {
    mocks.progress = query(result())
  })

  it('shows actual latest pairs and previous comparable day before closed charts and full table', () => {
    const wrapper = screen()
    const latest = wrapper.get('[data-testid="strength-latest"]')
    expect(latest.text()).toContain('60 кг × 5 повт.')
    expect(latest.text()).toContain('40 кг × 12 повт.')
    expect(latest.text()).not.toContain('60 кг × 12')
    expect(wrapper.get('[data-testid="strength-previous"]').text()).toContain('50 кг × 8 повт.')
    expect(
      wrapper.findAll('details').every((details) => details.attributes('open') === undefined),
    ).toBe(true)
    const historyLink = wrapper.findAllComponents(RouterLinkStub).at(-1)!
    expect(historyLink.props('to')).toEqual({
      name: 'activity-session',
      params: { session: 'latest' },
      query: {
        tab: 'progress',
        day: '2026-10-04',
        end: '2026-10-08',
        period: '7',
        kind: 'strength',
        exercise: 'bench',
        metric: 'strength',
      },
    })
  })

  it('keeps different equipment and per-implement counts in independent result blocks', () => {
    const data = result()
    data.series.push({
      ...data.series[0]!,
      snapshot: { name: 'Bench', equipment: 'Dumbbell', load_basis: 'per_implement' },
      implement_count: 2,
      points: [{ ...data.series[0]!.points[1]!, set_id: 'dumbbell', load_kg: '20' }],
    })
    mocks.progress = query(data)
    const wrapper = screen()
    const series = wrapper.findAll('[data-testid="strength-series"]')
    expect(series).toHaveLength(2)
    expect(series[0]!.text()).toContain('Barbell')
    expect(series[1]!.text()).toContain('Dumbbell')
    expect(series[1]!.text()).toContain('Снарядов: 2')
    expect(series[1]!.find('[data-testid="strength-previous"]').exists()).toBe(false)
    expect(series[1]!.text()).toContain('Предыдущего дня с этой нагрузкой')
  })

  it('offers a strength entry only to a writer and keeps catalog access for an empty history', async () => {
    mocks.progress = query(undefined)
    const writer = screen('', true)
    await writer
      .findAll('button')
      .find((button) => button.text() === 'Добавить силовую запись')!
      .trigger('click')
    expect(writer.emitted('add-strength')).toEqual([[]])
    writer.unmount()
    const reader = screen('', false)
    expect(reader.findAll('button').map((button) => button.text())).toEqual(['Упражнения'])
    await reader.get('button').trigger('click')
    expect(reader.emitted('catalog')).toEqual([[]])
  })

  it('keeps opened details on return from a set and reports its restoration anchor', async () => {
    const wrapper = screen()
    const details = wrapper.get('[data-testid="strength-series"] details')
    ;(details.element as HTMLDetailsElement).open = true
    await details.trigger('toggle')
    const link = wrapper.findAllComponents(RouterLinkStub).at(-1)!
    await link.trigger('click')
    expect(wrapper.emitted('activate')?.at(-1)).toEqual(['activity-progress-set-repetitions'])
    expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
    wrapper.unmount()
    const returned = screen()
    expect(returned.get('[data-testid="strength-series"] details').attributes('open')).toBeDefined()
    const returnedDetails = returned.get('[data-testid="strength-series"] details')
    ;(returnedDetails.element as HTMLDetailsElement).open = false
    await returnedDetails.trigger('toggle')
    returned.unmount()
  })

  it('does not carry native open state into another cached exercise and restores the first exercise', async () => {
    const first = result()
    const second = result()
    second.exercise = { ...second.exercise, id: 'other-bench', name: 'Other bench' }
    second.series[0]!.snapshot.name = 'Other bench'
    const cached = query(first)
    mocks.progress = cached
    const wrapper = screen()
    for (const details of wrapper.findAll('details')) {
      ;(details.element as HTMLDetailsElement).open = true
      await details.trigger('toggle')
    }

    cached.data.value = second
    await wrapper.setProps({ selected: 'other-bench' })
    expect(wrapper.get('[data-testid="strength-series"] h2').text()).toBe('Other bench')
    expect(
      wrapper.findAll('details').every((details) => !(details.element as HTMLDetailsElement).open),
    ).toBe(true)

    cached.data.value = first
    await wrapper.setProps({ selected: 'bench' })
    expect(
      wrapper.findAll('details').every((details) => (details.element as HTMLDetailsElement).open),
    ).toBe(true)
    for (const details of wrapper.findAll('details')) {
      ;(details.element as HTMLDetailsElement).open = false
      await details.trigger('toggle')
    }
    wrapper.unmount()
  })
})
