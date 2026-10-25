import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { russianPlural } from '@/i18n'
import ru from '@/locales/ru.json'
import {
  History,
  Metric,
  Observation,
  Profile,
  SourceMetric,
  type BodyKind,
  type Metric as MetricValue,
} from './api'
import DashboardBody from './DashboardBody.vue'

const mocks = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
vi.mock('./usePersonal', () => ({ useHistory: () => mocks.query }))
const characterId = ref<'sculpture' | 'spartan'>('sculpture')
vi.mock('./useBodyPresentation', () => ({
  useBodyPresentation: () => ({ ready: ref(true), characterId }),
}))

function record(kind: BodyKind, value: string, scan = 'latest') {
  return Observation.parse({
    id: `${scan}-${kind}`,
    source_id: 'inbody',
    source_name: 'Synthetic InBody',
    kind,
    start_at: `2026-${scan === 'latest' ? '10-02' : '09-02'}T09:00:00Z`,
    end_at: '2026-10-02T09:00:00Z',
    timezone: 'UTC',
    method: 'device_report',
    origin: { record_id: scan },
    data: { value },
    revision: 1,
  })
}
const currentFat = record('body_fat_percent', '22')
const currentMuscle = record('skeletal_muscle_mass', '34')
function metric(entry: Observation): MetricValue {
  return Metric.parse({
    kind: entry.kind,
    value: entry.data.value,
    unit: '%',
    status: 'available',
    records: [entry],
  })
}
const wrappers: VueWrapper[] = []
function show(
  extra: Record<string, unknown> = {},
  slots: Record<string, string> = {},
  editForms = false,
) {
  const wrapper = mount(DashboardBody, {
    slots,
    attachTo: editForms ? document.body : undefined,
    props: {
      metrics: {
        body_fat_percent: metric(currentFat),
        skeletal_muscle_mass: metric(currentMuscle),
      },
      day: '2026-10-05',
      today: '2026-10-05',
      timezone: 'UTC',
      canResolve: false,
      profile: Profile.parse({
        user_id: 'owner',
        timezone: 'UTC',
        height_cm: '200',
        gender: 'female',
        revision: 1,
      }),
      ...extra,
    },
    global: {
      plugins: [
        createI18n({
          legacy: false,
          locale: 'ru',
          messages: { ru },
          pluralRules: { ru: russianPlural },
        }),
      ],
      stubs: {
        BodyFigure: true,
        BodyAtlas: true,
        BodySourceDetails: true,
        BodyAnalysisCard: !editForms,
        BodyComposition: true,
        SourceLine: true,
        RecordPanel: editForms
          ? {
              name: 'RecordPanel',
              props: ['record', 'open'],
              emits: ['update:open'],
              template:
                '<section v-if="open" data-testid="body-record-editor"><button @click="$emit(\'update:open\', false)">close editor</button></section>',
            }
          : true,
        RefreshFailed: true,
        ErrorState: true,
        RouterLink: RouterLinkStub,
        Panel: { props: ['open'], template: '<aside v-if="open"><slot /></aside>' },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})
beforeEach(() => {
  characterId.value = 'sculpture'
  mocks.query = {
    data: ref(
      History.parse({
        from: '2025-10-06',
        to: '2026-10-05',
        records: [
          currentFat,
          currentMuscle,
          record('body_fat_percent', '23', 'old'),
          record('skeletal_muscle_mass', '32', 'old'),
        ],
        daily: [],
      }),
    ),
    isLoadingError: ref(false),
    error: ref(null),
    refetch: vi.fn(),
  }
})

describe('dashboard body', () => {
  it('hands the chosen source record to one editor, then restores its analysis and focused row', async () => {
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
    const wrapper = show({ canResolve: true }, {}, true)
    await wrapper.get('[data-testid="dashboard-body-previous"]').trigger('click')
    await wrapper.get('[data-testid="body-details-open"]').trigger('click')
    const originalAnalysis = wrapper.getComponent({ name: 'BodyAnalysisCard' }).props('analysis')
    const selected = originalAnalysis.records.find(
      (entry: Observation) => entry.kind === 'skeletal_muscle_mass',
    ) as Observation
    const disclosure = wrapper.get<HTMLDetailsElement>('[data-testid="analysis-details"]')
    disclosure.element.open = true
    await disclosure.trigger('toggle')
    const editSelector = `button[data-record-id="${selected.id}"]`
    const edit = wrapper.get(editSelector)
    expect(edit.attributes('aria-label')).toBe('Исправить: Скелетная мышечная масса')
    await edit.trigger('click')
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(wrapper.getComponent({ name: 'RecordPanel' }).props('record')).toEqual(selected)
    expect(wrapper.findAll('[data-testid="body-record-editor"]')).toHaveLength(1)
    await wrapper.get('[data-testid="body-record-editor"] button').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'RecordPanel' }).exists()).toBe(false)
    expect(wrapper.getComponent({ name: 'BodyAnalysisCard' }).props('analysis').id).toBe(
      originalAnalysis.id,
    )
    expect(wrapper.get<HTMLDetailsElement>('[data-testid="analysis-details"]').element.open).toBe(
      true,
    )
    expect(document.activeElement).toBe(wrapper.get(editSelector).element)
  })

  it('keeps caliper actions available to a fresh user without creating a figure', () => {
    const wrapper = show(
      { metrics: {}, sources: [], fatSources: [] },
      { caliper: '<button data-testid="fresh-caliper">Записать замер</button>' },
    )
    expect(wrapper.get('[data-testid="fresh-caliper"]').text()).toBe('Записать замер')
    expect(wrapper.findComponent({ name: 'BodyFigure' }).exists()).toBe(false)
  })

  it('uses the existing female calibrated figure and switches exact dated endpoints', async () => {
    const wrapper = show()
    const figure = wrapper.getComponent({ name: 'BodyFigure' })
    expect(figure.props('visual')).toMatchObject({ template: 'female', muscle: expect.any(Number) })
    expect(wrapper.get('[data-testid="dashboard-body-body_fat_percent"]').text()).toContain('22 %')
    expect(wrapper.get('[data-testid="dashboard-body-caption"]').text()).toContain('2 октября')
    await wrapper.get('[data-testid="dashboard-body-previous"]').trigger('click')
    expect(wrapper.get('[data-testid="dashboard-body-body_fat_percent"]').text()).toContain('23 %')
    expect(wrapper.get('[data-testid="dashboard-body-caption"]').text()).toContain('2 сентября')
    expect(figure.props('comparing')).toBe(true)
    await wrapper.get('[data-testid="dashboard-body-latest"]').trigger('click')
    expect(wrapper.get('[data-testid="dashboard-body-body_fat_percent"]').text()).toContain('22 %')
    expect(wrapper.findAll('[style]')).toHaveLength(0)
  })

  it('states a conflicting decision to readers and writers alike, without a choice', () => {
    const conflict = metric(currentFat)
    conflict.status = 'conflict'
    conflict.value = null
    const metrics = { body_fat_percent: conflict, skeletal_muscle_mass: metric(currentMuscle) }
    for (const view of [show({ metrics }), show({ metrics, canResolve: true })]) {
      expect(view.get('[data-testid="body-conflict"]').text()).toBe('Показания не согласованы')
      expect(view.findAll('button').map((button) => button.text())).not.toContain(
        'Выбрать источник',
      )
    }
    expect(show().find('[data-testid="body-conflict"]').exists()).toBe(false)
  })

  it("names the analysis's sources under its caption from the body decision", () => {
    expect(show().findComponent({ name: 'SourceLine' }).exists()).toBe(false)
    const contributions = [{ source_id: 'inbody', count: 10, spans: [] }]
    const muscle = Metric.parse({
      ...metric(currentMuscle),
      resolution: {
        status: 'available',
        fingerprint: 'synthetic-body',
        resolver_version: 'synthetic',
        contributions,
      },
    })
    const wrapper = show({
      metrics: { body_fat_percent: metric(currentFat), skeletal_muscle_mass: muscle },
    })
    const line = wrapper.getComponent({ name: 'SourceLine' })
    expect(line.props()).toEqual({
      metric: 'skeletal_muscle_mass',
      contributions,
      day: '2026-10-05',
    })
    // It sits right under the scan's own caption.
    expect(wrapper.get('[data-testid="dashboard-body-caption"]').element.nextElementSibling).toBe(
      line.element,
    )
  })

  it('keeps summary values visible without inventing a scan when record details are absent', () => {
    const wrapper = show({
      metrics: {
        skeletal_muscle_mass: Metric.parse({
          kind: 'skeletal_muscle_mass',
          value: '34',
          unit: 'kg',
          status: 'available',
        }),
      },
    })
    expect(wrapper.get('[data-testid="dashboard-body-skeletal_muscle_mass"]').text()).toContain(
      '34 кг',
    )
    expect(wrapper.findComponent({ name: 'BodyFigure' }).exists()).toBe(false)
    expect(wrapper.text()).toContain('отдельными измерениями')
  })

  it('shows separate caliper provenance and opens details of the same selected scan', async () => {
    const fold = {
      ...record('body_fat_percent', '18'),
      id: 'fold',
      source_id: 'caliper',
      source_name: 'Калипер',
      method: 'derived',
      origin: { provider: 'caliper', record_id: 'fold' },
    }
    const wrapper = show({
      fatSources: [
        SourceMetric.parse({ ...metric(fold), source_id: 'caliper', source_name: 'Калипер' }),
      ],
    })
    const caliper = wrapper.get('[data-source-id="caliper"]')
    expect(caliper.text()).toContain('18 %')
    expect(caliper.text()).toContain('Оценка')
    await wrapper.get('[data-testid="body-details-open"]').trigger('click')
    expect(
      wrapper.getComponent({ name: 'BodyAtlas' }).props('analysis').values.body_fat_percent.id,
    ).toBe(currentFat.id)
    expect(wrapper.getComponent({ name: 'BodyAtlas' }).props('canWrite')).toBe(false)
  })

  it('keeps the figure paused behind details and drops comparison when the day changes', async () => {
    const wrapper = show()
    expect(wrapper.find('[data-testid="dashboard-body-pause"]').exists()).toBe(false)
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('paused')).toBe(false)
    await wrapper.get('[data-testid="dashboard-body-previous"]').trigger('click')
    await wrapper.get('[data-testid="body-details-open"]').trigger('click')
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('paused')).toBe(true)
    await wrapper.setProps({ day: '2026-10-04' })
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('comparing')).toBe(false)
    expect(wrapper.findComponent({ name: 'BodyAtlas' }).exists()).toBe(false)
  })

  it('uses one prior scan caption and clears changes during comparison', async () => {
    const wrapper = show()
    expect(wrapper.get('[data-testid="dashboard-body-shared-change"]').text()).toContain(
      'Жир −1 п. п.',
    )
    expect(wrapper.get('[data-testid="dashboard-body-shared-change"]').text()).toContain(
      'Мышцы +2 кг',
    )
    expect(wrapper.get('[data-testid="dashboard-body-comparison"]').text()).toContain('2 сентября')
    await wrapper.get('[data-testid="dashboard-body-previous"]').trigger('click')
    expect(wrapper.find('[data-testid="dashboard-body-comparison"]').exists()).toBe(false)
  })

  it('does not share equal-time change captions from two different source scan identities', () => {
    mocks.query.data = ref(
      History.parse({
        from: '2025-10-06',
        to: '2026-10-05',
        records: [
          currentFat,
          currentMuscle,
          record('body_fat_percent', '23', 'fat-only'),
          record('skeletal_muscle_mass', '32', 'muscle-only'),
        ],
        daily: [],
      }),
    )
    const wrapper = show()
    expect(wrapper.find('[data-testid="dashboard-body-shared-change"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="dashboard-body-change-body_fat_percent"]').text()).toContain(
      'Жир −1 п. п.',
    )
    expect(
      wrapper.get('[data-testid="dashboard-body-change-skeletal_muscle_mass"]').text(),
    ).toContain('Мышцы +2 кг')
  })

  it('suppresses only the represented caliper source and retains full details', async () => {
    const fold = {
      ...record('body_fat_percent', '18'),
      id: 'fold',
      source_id: 'caliper',
      method: 'derived',
      origin: { provider: 'caliper', record_id: 'fold' },
    }
    const other = { ...fold, id: 'other-fold', source_id: 'other-caliper' }
    const sources = [
      SourceMetric.parse({ ...metric(fold), source_id: 'caliper', source_name: 'Калипер' }),
      SourceMetric.parse({ ...metric(other), source_id: 'other-caliper', source_name: 'Калипер' }),
    ]
    const wrapper = show({ fatSources: sources, caliperSourceId: 'caliper' })
    expect(wrapper.find('[data-source-id="caliper"]').exists()).toBe(false)
    expect(wrapper.get('[data-source-id="other-caliper"]').text()).toContain('18 %')
    await wrapper.get('[data-testid="body-details-open"]').trigger('click')
    expect(wrapper.getComponent({ name: 'BodyComposition' }).props('fatSources')).toEqual(sources)
    await wrapper.setProps({ caliperSourceId: null })
    expect(wrapper.get('[data-source-id="caliper"]').text()).toContain('18 %')
  })

  it('retains conflicting secondary records and their dates in details', async () => {
    const first = { ...record('body_fat_percent', '18'), id: 'first', source_id: 'other' }
    const second = { ...first, id: 'second', data: { value: '19' } }
    const source = SourceMetric.parse({
      kind: 'body_fat_percent',
      source_id: 'other',
      source_name: 'Другой прибор',
      value: null,
      status: 'conflict',
      unit: '%',
      records: [first, second],
      source_unavailable: true,
    })
    const wrapper = show({ fatSources: [source], caliperSourceId: 'caliper' })
    const row = wrapper.get('[data-source-id="other"]')
    expect(row.text()).toContain('Показания не согласованы')
    expect(row.text()).toContain('2 записи')
    expect(row.text()).toContain('Источник сейчас недоступен')
    await row.get('button').trigger('click')
    expect(wrapper.getComponent({ name: 'BodyComposition' }).props('fatSources')).toEqual([source])
  })

  it('uses the saved account character on the dashboard as well as in details', async () => {
    const wrapper = show()
    characterId.value = 'spartan'
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent({ name: 'BodyFigure' }).props('characterId')).toBe('spartan')
  })
})
