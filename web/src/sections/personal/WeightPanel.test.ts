import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WeightPanel from './WeightPanel.vue'

vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'Europe/Moscow' } }) }))
vi.mock('./usePersonal', () => ({ usePersonal: () => ({ operate: vi.fn(), failed: vi.fn() }) }))

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T12:00:00Z'))
})
afterEach(() => vi.useRealTimers())

function show(day?: string) {
  return mount(WeightPanel, {
    props: { open: false, day },
    global: { stubs: { Panel: { template: '<section><slot /></section>' } } },
  })
}

describe('weighing date from the dashboard', () => {
  it('initializes an already-open form reached by a quick-action link', () => {
    const wrapper = mount(WeightPanel, {
      props: { open: true, day: '2026-10-03' },
      global: { stubs: { Panel: { template: '<section><slot /></section>' } } },
    })
    expect((wrapper.get('#weight-measured').element as HTMLInputElement).value).toBe(
      '2026-10-03T15:00',
    )
    wrapper.unmount()
  })
  it('starts on the selected historical day, at the account’s local time', async () => {
    const wrapper = show('2026-10-03')
    await wrapper.setProps({ open: true })
    expect((wrapper.get('#weight-measured').element as HTMLInputElement).value).toBe(
      '2026-10-03T15:00',
    )
    wrapper.unmount()
  })

  it.each([undefined, 'invalid', '2026-10-06'])('defaults to today for %s', async (day) => {
    const wrapper = show(day)
    await wrapper.setProps({ open: true })
    expect((wrapper.get('#weight-measured').element as HTMLInputElement).value).toBe(
      '2026-10-05T15:00',
    )
    wrapper.unmount()
  })

  it('preserves an open draft when the dashboard date changes', async () => {
    const wrapper = show('2026-10-03')
    await wrapper.setProps({ open: true })
    await wrapper.get('#weight-measured').setValue('2026-10-02T08:15')
    await wrapper.setProps({ day: '2026-10-01' })
    expect((wrapper.get('#weight-measured').element as HTMLInputElement).value).toBe(
      '2026-10-02T08:15',
    )
    wrapper.unmount()
  })
})
