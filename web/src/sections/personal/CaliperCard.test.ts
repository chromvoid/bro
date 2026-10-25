import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import { Profile } from './api'
import { CaliperHistory, CaliperMeasurement } from './caliper'
import CaliperCard from './CaliperCard.vue'

const mocks = vi.hoisted(() => ({ operate: vi.fn(), failed: vi.fn(), query: vi.fn() }))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))
vi.mock('./caliper', async (original) => ({
  ...(await original<object>()),
  useCaliperHistory: () => mocks.query(),
}))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))
const latest = CaliperMeasurement.parse({
  id: 'synthetic-incomplete',
  source_id: 'synthetic-caliper-source',
  measured_at: '2026-10-05T06:00:00Z',
  timezone: 'Europe/Moscow',
  folds_mm: { abdomen: '20', suprailiac: '15' },
  gender: 'male',
  age_years: 35,
  body_fat_percent: null,
  calculation: {
    status: 'incomplete',
    method: 'jackson_pollock_3_siri',
    missing_fields: ['chest', 'thigh'],
    algorithm_version: 'jackson_pollock_3_siri_v1',
  },
  revision: 2,
  body_fat_record_id: null,
})
const calculated = CaliperMeasurement.parse({
  ...latest,
  id: 'synthetic-complete',
  measured_at: '2026-10-04T06:00:00Z',
  folds_mm: { chest: '15', abdomen: '25', thigh: '20' },
  body_fat_percent: '18.515298680869137',
  calculation: { ...latest.calculation, status: 'calculated', missing_fields: [] },
  body_fat_record_id: 'synthetic-fat-record',
})
const data = CaliperHistory.parse({ records: [calculated, latest], truncated: false, limit: 100 })
const historyData = ref(data)
const profile = Profile.parse({
  user_id: 'synthetic-owner',
  timezone: 'Europe/Moscow',
  revision: 1,
})
function show(canWrite = true, value = profile) {
  return mount(CaliperCard, {
    props: {
      profile: value,
      day: '2026-10-05',
      today: '2026-10-05',
      canWrite,
    },
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: { CaliperPanel: true, CaliperMethod: true, RouterLink: RouterLinkStub },
    },
  })
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T06:00:00Z'))
  mocks.operate.mockReset().mockResolvedValue({})
  mocks.failed.mockReset()
  historyData.value = data
  mocks.query.mockReturnValue({
    data: historyData,
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    isFetching: ref(false),
    error: ref(null),
    refetch: vi.fn(),
  })
})
afterEach(() => vi.useRealTimers())

describe('caliper history card', () => {
  it('shows partial raw readings and labels the original calculation separately', () => {
    const wrapper = show()
    const rows = wrapper.findAll('[data-testid="caliper-record"]')
    expect(rows[0]!.text()).toContain('Сегодня, 09:00')
    expect(rows[0]!.text()).toContain('20 мм')
    expect(rows[0]!.text()).toContain('15 мм')
    expect(rows[0]!.get('[data-testid="caliper-incomplete"]').text()).toContain('грудь, бедро')
    expect(rows[0]!.text()).not.toContain('%')
    expect(rows[1]!.text()).toContain('18,5 %')
    expect(rows[1]!.text()).toContain('Расчёт по исходным складкам при записи')
    expect(rows[1]!.text()).toContain('Вчера, 09:00')
  })

  it('deletes the selected measurement only after confirmation with its revision', async () => {
    const wrapper = show()
    await wrapper.findAll('[data-testid="caliper-record"] button')[0]!.trigger('click')
    expect(mocks.operate).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="caliper-delete-confirm"] button').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'caliper.delete',
      { measurement_id: latest.id },
      { [latest.id]: 2 },
    )
    expect(wrapper.find('[data-testid="caliper-delete-confirm"]').exists()).toBe(false)
  })

  it('keeps measurements visible without offering writes to readers', () => {
    const wrapper = show(false)
    expect(wrapper.findAll('[data-testid="caliper-record"]')).toHaveLength(2)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('links only actual missing demographics and applies profile facts only on explicit click', async () => {
    const record = CaliperMeasurement.parse({
      ...calculated,
      body_fat_percent: null,
      age_years: null,
      body_fat_record_id: null,
      calculation: {
        ...calculated.calculation,
        status: 'incomplete',
        missing_fields: ['age_years'],
      },
    })
    historyData.value = { ...data, records: [record] }
    const wrapper = show()
    const notice = wrapper.get('[data-testid="caliper-incomplete"]')
    expect(notice.text()).toContain('возраст на дату замера')
    expect(wrapper.findAllComponents(RouterLinkStub).map((link) => link.props('to'))).toEqual([
      {
        name: 'personal-profile',
        query: {
          field: 'birth',
          returnDay: '2026-10-05',
          returnSection: 'body',
          returnKey: expect.any(String),
        },
      },
    ])
    expect(notice.find('button').exists()).toBe(false)
    await wrapper.setProps({
      profile: { ...profile, birth_date: '1990-01-01', gender: 'female', revision: 7 },
    })
    expect(mocks.operate).not.toHaveBeenCalled()
    expect(notice.get('button').text()).toBe('Рассчитать с данными профиля')
    expect(wrapper.text()).not.toContain('18,5 %')
    await notice.get('button').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledExactlyOnceWith(
      'caliper.complete',
      { measurement_id: record.id },
      { [record.id]: record.revision, [profile.user_id]: 7 },
    )
    expect(record.age_years).toBeNull()
  })

  it('waits until both absent facts can be filled and labels metadata-only completion', async () => {
    historyData.value = {
      ...data,
      records: [
        {
          ...latest,
          gender: null,
          age_years: null,
          calculation: { ...latest.calculation, missing_fields: ['gender', 'age_years'] },
        },
      ],
    }
    const wrapper = show(true, { ...profile, gender: 'male' })
    expect(wrapper.get('[data-testid="caliper-incomplete"]').text()).toContain('пол для расчёта')
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(2)
    expect(wrapper.get('[data-testid="caliper-incomplete"]').text()).not.toContain(
      'Дополнить данными профиля',
    )
    await wrapper.setProps({ profile: { ...profile, gender: 'male', birth_date: '1990-01-01' } })
    const notice = wrapper.get('[data-testid="caliper-incomplete"]')
    expect(notice.text()).toContain('Недостающие складки')
    expect(notice.findAll('button').map((button) => button.text())).toEqual([
      'Дополнить данными профиля',
      'Добавить полный замер',
    ])
  })

  it('offers no completion to readers even when profile metadata becomes available', () => {
    historyData.value = {
      ...data,
      records: [
        {
          ...latest,
          age_years: null,
          calculation: { ...latest.calculation, missing_fields: ['age_years', 'chest', 'thigh'] },
        },
      ],
    }
    const wrapper = show(false, { ...profile, birth_date: '1990-01-01' })
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(1)
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it('keeps stale snapshots after refusal and resubmits with the newly loaded revisions', async () => {
    const record = {
      ...latest,
      age_years: null,
      calculation: { ...latest.calculation, missing_fields: ['age_years', 'chest', 'thigh'] },
    }
    historyData.value = { ...data, records: [record] }
    const error = new Error('stale-profile-revision')
    mocks.operate.mockRejectedValueOnce(error)
    const wrapper = show(true, { ...profile, birth_date: '1990-01-01' })
    await wrapper.get('[data-testid="caliper-incomplete"] button').trigger('click')
    await flushPromises()
    expect(mocks.failed).toHaveBeenCalledWith(error)
    expect(wrapper.text()).toContain('возраст на дату замера: —')
    historyData.value = { ...data, records: [{ ...record, revision: 4 }] }
    await wrapper.setProps({ profile: { ...profile, birth_date: '1990-01-01', revision: 9 } })
    await wrapper.get('[data-testid="caliper-incomplete"] button').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'caliper.complete',
      { measurement_id: record.id },
      { [record.id]: 4, [profile.user_id]: 9 },
    )
  })
})
