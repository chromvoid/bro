import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { ActivitySession, type ActivityKind } from './api'
import SessionForm from './SessionForm.vue'

const mocks = vi.hoisted(() => ({
  operate: vi.fn(),
  failed: vi.fn(),
  check: vi.fn(),
  unresolved: null as unknown,
}))
vi.mock('./useActivity', () => ({
  useActivity: () => ({
    onResolved: () => () => {},
    operate: mocks.operate,
    failed: mocks.failed,
    check: mocks.check,
    resend: mocks.check,
    abandon: vi.fn(),
    describe: () => '',
    unresolved: mocks.unresolved,
  }),
}))
const original = () =>
  ActivitySession.parse({
    id: 'session',
    revision: 2,
    kind: 'walking',
    title: '',
    date: '2026-10-05',
    timezone: 'Europe/Moscow',
    started_at: null,
    duration_minutes: null,
    distance_km: null,
    note: '',
    exercises: [],
  })
function screen(
  session?: ActivitySession,
  context: { initialDate?: string; initialKind?: ActivityKind } = {},
) {
  return mount(SessionForm, {
    props: { open: true, today: '2026-10-05', timezone: 'Europe/Moscow', session, ...context },
    global: {
      plugins: [i18n],
      stubs: {
        Panel: { template: '<div><slot /><footer><slot name="footer" /></footer></div>' },
        UnresolvedWrites: {
          props: ['entries', 'check'],
          template: '<button v-if="entries.length" @click="check(entries[0].key)">recover</button>',
        },
      },
    },
  })
}
describe('activity session editing', () => {
  beforeEach(() => {
    mocks.operate.mockReset()
    mocks.check.mockReset()
    mocks.unresolved = ref([])
  })
  it('creates a completed session with only kind and date and unknown optional numbers', async () => {
    const wrapper = screen()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('session.add', {
      kind: 'walking',
      date: '2026-10-05',
      title: '',
      timezone: 'Europe/Moscow',
      started_at: null,
      duration_minutes: null,
      distance_km: null,
      note: '',
    })
  })
  it('preserves the original revision and draft when newer server data arrives', async () => {
    const wrapper = screen(original())
    await wrapper.get('#activity-title').setValue('My walk')
    await wrapper.setProps({ session: { ...original(), revision: 3, title: 'Concurrent edit' } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'session.update',
      expect.objectContaining({ session_id: 'session', title: 'My walk' }),
      { session: 2 },
    )
  })
  it('starts a new historical strength entry in the selected date and kind', async () => {
    const wrapper = screen(undefined, { initialDate: '2026-10-03', initialKind: 'strength' })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'session.add',
      expect.objectContaining({ date: '2026-10-03', kind: 'strength' }),
    )
    expect(wrapper.get('footer button').attributes('form')).toBe(
      wrapper.get('form').attributes('id'),
    )
  })
  it('resets only on reopening and keeps edits bound to the original session', async () => {
    const wrapper = screen(undefined, { initialDate: '2026-10-03', initialKind: 'strength' })
    await wrapper.get('#activity-title').setValue('Draft')
    await wrapper.setProps({ initialDate: '2026-10-02', initialKind: 'cycling' })
    expect(wrapper.get<HTMLInputElement>('#activity-date').element.value).toBe('2026-10-03')
    expect(wrapper.get<HTMLInputElement>('#activity-title').element.value).toBe('Draft')
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    expect(wrapper.get<HTMLInputElement>('#activity-date').element.value).toBe('2026-10-02')
    expect(wrapper.get<HTMLSelectElement>('#activity-kind').element.value).toBe('cycling')
    expect(wrapper.get<HTMLInputElement>('#activity-title').element.value).toBe('')
    wrapper.unmount()

    const editing = screen(original(), { initialDate: '2026-10-02', initialKind: 'strength' })
    await editing.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'session.update',
      expect.objectContaining({ date: '2026-10-05', kind: 'walking' }),
      { session: 2 },
    )
  })
  it('does not initialize a new entry to a future or invalid date', () => {
    for (const initialDate of ['2026-10-06', '2026-02-30']) {
      const wrapper = screen(undefined, { initialDate })
      expect(wrapper.get<HTMLInputElement>('#activity-date').element.value).toBe('2026-10-05')
      wrapper.unmount()
    }
  })
  it('closes a recovered write only after its receipt is resolved', async () => {
    mocks.unresolved = ref([{ key: 'lost' }])
    mocks.check
      .mockResolvedValueOnce({ state: 'uncertain' })
      .mockResolvedValueOnce({ state: 'resolved' })
    const wrapper = screen()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:open')).toBeUndefined()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
    expect(mocks.operate).not.toHaveBeenCalled()
  })
})
