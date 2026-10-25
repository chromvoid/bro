import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { russianPlural } from '@/i18n'
import ru from '@/locales/ru.json'
import { Metric, Observation, Profile } from './api'
import ProfileForm from './ProfileForm.vue'
import ProfileView from './ProfileView.vue'

const mocks = vi.hoisted(() => ({ operate: vi.fn(), failed: vi.fn(), load: vi.fn() }))
const profile = Profile.parse({
  user_id: 'owner',
  timezone: 'UTC',
  height_cm: '170',
  birth_date: null,
  gender: null,
  gender_origin: 'unset',
  revision: 3,
})
const weighing = Observation.parse({
  id: 'weight-1',
  kind: 'weight',
  start_at: '2026-10-04T08:30:00Z',
  end_at: '2026-10-04T08:30:00Z',
  timezone: 'UTC',
  method: 'self_report',
  data: { value: '72.5' },
  revision: 1,
})
const weight = Metric.parse({
  kind: 'weight',
  status: 'available',
  value: '72.5',
  unit: 'kg',
  records: [weighing],
})
const data = ref<{ profile: Profile; metrics: { weight: Metric } } | undefined>()
const pending = ref(false)
const loadingError = ref(false)
const refreshError = ref(false)
const fetching = ref(false)
const loadFailure = ref<Error | null>(null)
const errorUpdatedAt = ref(0)
const retry = vi.fn()
const canWrite = ref(true)
const route = reactive({ query: {} as Record<string, unknown> })
const requestedDays: string[] = []
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'UTC' }, can: () => canWrite.value, load: mocks.load }),
}))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => route,
}))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))
vi.mock('./usePersonal', () => ({
  useOverview: (day: { value: string }) => {
    requestedDays.push(day.value)
    return {
      data,
      isPending: pending,
      isLoadingError: loadingError,
      isRefetchError: refreshError,
      isFetching: fetching,
      error: loadFailure,
      errorUpdatedAt,
      refetch: retry,
    }
  },
  usePersonal: () => ({
    operate: mocks.operate,
    failed: mocks.failed,
    unresolved: ref([]),
    describe: vi.fn(),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
const WeightStub = { name: 'WeightPanel', props: { open: Boolean }, template: '<div />' }
const wrappers: VueWrapper[] = []
function show() {
  const wrapper = mount(ProfileView, {
    attachTo: document.body,
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
        RouterLink: { template: '<a><slot /></a>' },
        PageHeader: true,
        UnresolvedWrites: true,
        WeightPanel: WeightStub,
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-05T12:00:00Z'))
  data.value = { profile: { ...profile }, metrics: { weight: { ...weight } } }
  pending.value = false
  loadingError.value = false
  refreshError.value = false
  fetching.value = false
  loadFailure.value = new Error('Offline')
  errorUpdatedAt.value = 0
  canWrite.value = true
  route.query = {}
  requestedDays.length = 0
  retry.mockReset()
  mocks.failed.mockReset()
  mocks.operate.mockReset().mockImplementation(async (_kind, changes, revisions) => ({
    profile: { ...profile, ...changes, revision: revisions.owner + 1 },
  }))
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})

describe('body profile page', () => {
  it('uses today and observation weight with its date, while recording weight separately', async () => {
    route.query = { day: '2025-01-01' }
    data.value!.metrics.weight.records = [
      { ...weighing, id: 'offset-weighing', start_at: '2026-10-04T09:00:00+03:00' },
      weighing,
    ]
    const wrapper = show()
    expect(requestedDays).toEqual(['2026-10-05'])
    const card = wrapper.get('[data-testid="profile-weight"]')
    expect(card.text()).toContain('72,5')
    expect(card.text()).toContain('вчера, 08:30')
    expect(card.text()).toContain('Введено вами')
    await card.get('button').trigger('click')
    expect(wrapper.getComponent(WeightStub).props('open')).toBe(true)
    expect(mocks.operate).not.toHaveBeenCalled()
  })
  it('saves DOB without age or weight and stays on the page for another save using the receipt revision', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-birth"]').setValue('1990-10-06')
    expect(wrapper.get('[data-testid="profile-age"]').text()).toBe('35 лет')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'profile.update',
      { birth_date: '1990-10-06' },
      { owner: 3 },
    )
    expect(wrapper.findComponent(ProfileForm).exists()).toBe(true)
    await wrapper.get('[data-testid="profile-gender"]').setValue('male')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'profile.update',
      { gender: 'male' },
      { owner: 4 },
    )
  })
  it('keeps a typed draft and captured revision during overview refresh and after a failed save', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('175')
    data.value = { profile: { ...profile, height_cm: '180', revision: 4 }, metrics: { weight } }
    await wrapper.vm.$nextTick()
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '175',
    )
    mocks.operate.mockRejectedValueOnce(new Error('Conflict'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'profile.update',
      { height_cm: '175' },
      { owner: 3 },
    )
    expect(mocks.failed).toHaveBeenCalledOnce()
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '175',
    )
    await wrapper.get('button[type="button"]').trigger('click')
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '180',
    )
    await wrapper.get('[data-testid="profile-height"]').setValue('181')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'profile.update',
      { height_cm: '181' },
      { owner: 4 },
    )
  })
  it('does not reset to stale overview props after a confirmed save', async () => {
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('175')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(data.value!.profile.revision).toBe(3)
    await wrapper.get('[data-testid="profile-height"]').setValue('176')
    await wrapper.get('button[type="button"]').trigger('click')
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '175',
    )
    await wrapper.get('[data-testid="profile-height"]').setValue('177')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'profile.update',
      { height_cm: '177' },
      { owner: 4 },
    )
  })
  it('accepts a newer clean overview that arrived while the save was in flight', async () => {
    let complete: (value: unknown) => void = () => undefined
    mocks.operate.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve
        }),
    )
    const wrapper = show()
    await wrapper.get('[data-testid="profile-height"]').setValue('175')
    await wrapper.get('form').trigger('submit')
    data.value = { profile: { ...profile, height_cm: '185', revision: 5 }, metrics: { weight } }
    await wrapper.vm.$nextTick()
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '175',
    )
    complete({ profile: { ...profile, height_cm: '175', revision: 4 } })
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[data-testid="profile-height"]').element.value).toBe(
      '185',
    )
    await wrapper.get('[data-testid="profile-height"]').setValue('186')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'profile.update',
      { height_cm: '186' },
      { owner: 5 },
    )
  })
  it('retains the field deep link through loading error and focuses DOB when retry loads the form', async () => {
    route.query = { field: 'birth', keep: 'yes' }
    data.value = undefined
    pending.value = true
    const wrapper = show()
    expect(wrapper.find('form').exists()).toBe(false)
    pending.value = false
    loadingError.value = true
    errorUpdatedAt.value = Date.now()
    await wrapper.vm.$nextTick()
    const alert = wrapper.get('[role="alert"]').element
    await wrapper.get('[data-state="error"] button').trigger('click')
    expect(retry).toHaveBeenCalledOnce()
    loadingError.value = false
    loadFailure.value = null
    pending.value = true
    fetching.value = true
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[role="alert"]').element).toBe(alert)
    expect(wrapper.get('[role="alert"] button').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="alert"] .animate-spin').exists()).toBe(true)
    expect(wrapper.find('[data-state="loading"]').exists()).toBe(false)
    pending.value = false
    fetching.value = false
    data.value = { profile, metrics: { weight } }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="profile-birth"]').element)
    expect(route.query).toEqual({ field: 'birth', keep: 'yes' })
  })
  it('rejects arbitrary focus selectors and presents profile/weight as read-only without write actions', async () => {
    route.query = { field: '#profile-zone' }
    canWrite.value = false
    const wrapper = show()
    await wrapper.vm.$nextTick()
    expect(document.activeElement).not.toBe(wrapper.get('[data-testid="profile-zone"]').element)
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
    expect(wrapper.findComponent(WeightStub).exists()).toBe(false)
    expect(wrapper.get('[data-testid="profile-weight"]').find('button').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).not.toHaveBeenCalled()
  })
  it('keeps loaded data after refresh failure and does not display a disputed weight as one value', async () => {
    data.value = { profile, metrics: { weight: { ...weight, status: 'conflict', value: null } } }
    refreshError.value = true
    const wrapper = show()
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
    const card = wrapper.get('[data-testid="profile-weight"]')
    expect(card.text()).toContain('Источники расходятся')
    expect(card.text()).not.toContain('72,5')
    await wrapper.get('[data-state="refresh-failed"] button').trigger('click')
    expect(retry).toHaveBeenCalledOnce()
  })
})
