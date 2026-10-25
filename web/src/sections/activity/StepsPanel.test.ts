import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import StepsPanel from './StepsPanel.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  override: null as unknown,
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/sections/personal/usePersonal', async () => {
  const { ref } = await import('vue')
  return {
    usePersonal: () => ({
      onResolved: () => () => {},
      operate: mocks.operate,
      failed: mocks.failed,
      unresolved: ref([]),
    }),
  }
})
let queries: QueryClient
async function screen(initialDate?: string) {
  queries = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(StepsPanel, {
    props: { open: true, today: '2026-10-05', initialDate },
    global: {
      plugins: [i18n, [VueQueryPlugin, { queryClient: queries }]],
      stubs: {
        Panel: { template: '<div><slot /><footer><slot name="footer" /></footer></div>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}
describe('manual daily steps', () => {
  beforeEach(() => {
    mocks.get.mockReset()
    mocks.operate.mockReset()
    mocks.failed.mockReset()
    mocks.override = null
    mocks.get.mockImplementation(async () => ({ date: '2026-10-05', override: mocks.override }))
  })
  it('saves zero as a daily total with no revision for the first record', async () => {
    const wrapper = await screen()
    await wrapper.get('#steps-value').setValue('0')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('steps.set', { date: '2026-10-05', value: 0 }, {})
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })
  it('uses the cleared tombstone revision for a later manual total', async () => {
    mocks.override = { id: 'tombstone', revision: 4, value: null, active: false }
    const wrapper = await screen()
    await wrapper.get('#steps-value').setValue('1234')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'steps.set',
      { date: '2026-10-05', value: 1234 },
      { tombstone: 4 },
    )
  })
  it('clears an active total and preserves the draft after a refused write', async () => {
    mocks.override = { id: 'steps', revision: 2, value: 8000, active: true }
    mocks.operate.mockRejectedValue(new Error('refused'))
    const wrapper = await screen()
    const clear = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Отменить ручной итог')!
    await clear.trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('steps.clear', { date: '2026-10-05' }, { steps: 2 })
    expect((wrapper.get('#steps-value').element as HTMLInputElement).value).toBe('8000')
    expect(wrapper.emitted('update:open')).toBeUndefined()
  })
  it('opens against a fresh daily override after closing before cached data refreshed', async () => {
    const wrapper = await screen()
    await wrapper.get('#steps-value').setValue('8000')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.setProps({ open: false })
    mocks.override = { id: 'steps', revision: 1, value: 8000, active: true }
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect((wrapper.get('#steps-value').element as HTMLInputElement).value).toBe('8000')
    await wrapper.get('#steps-value').setValue('8500')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'steps.set',
      { date: '2026-10-05', value: 8500 },
      { steps: 1 },
    )
  })
  it('keeps the draft and captured revision when a concurrent refresh arrives', async () => {
    mocks.override = { id: 'steps', revision: 1, value: 8000, active: true }
    const wrapper = await screen()
    await wrapper.get('#steps-value').setValue('8500')
    mocks.override = { id: 'steps', revision: 2, value: 9000, active: true }
    await queries.refetchQueries({ queryKey: ['personal', 'steps-override'] })
    expect((wrapper.get('#steps-value').element as HTMLInputElement).value).toBe('8500')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith(
      'steps.set',
      { date: '2026-10-05', value: 8500 },
      { steps: 1 },
    )
  })
  it('loads and saves the selected historical day, and resets context on reopening', async () => {
    mocks.get.mockImplementation(async (url: string) => ({
      date: new URL(url, 'https://example.com').searchParams.get('date'),
      override: mocks.override,
    }))
    const wrapper = await screen('2026-10-03')
    expect(wrapper.get<HTMLInputElement>('#steps-date').element.value).toBe('2026-10-03')
    await wrapper.get('#steps-value').setValue('1234')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith('steps.set', { date: '2026-10-03', value: 1234 }, {})
    await wrapper.setProps({ open: false, initialDate: '2026-10-02' })
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('#steps-date').element.value).toBe('2026-10-02')
    expect(wrapper.get<HTMLInputElement>('#steps-value').element.value).toBe('')
    expect(wrapper.get('footer button[type=submit]').attributes('form')).toBe(
      wrapper.get('form').attributes('id'),
    )
  })
  it('does not enable saving a previous day while its fresh revision is still loading', async () => {
    mocks.override = { id: 'today', revision: 2, value: 8000, active: true }
    const wrapper = await screen()
    let finish: (value: unknown) => void = () => {}
    mocks.get.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('#steps-date').setValue('2026-10-03')
    await flushPromises()
    expect(wrapper.get('footer button[type=submit]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('footer button:not([type=submit])').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(mocks.operate).not.toHaveBeenCalled()
    finish({ date: '2026-10-03', override: { id: 'past', revision: 4, value: 6000, active: true } })
    await flushPromises()
    await wrapper.get('#steps-value').setValue('6500')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'steps.set',
      { date: '2026-10-03', value: 6500 },
      { past: 4 },
    )
  })
})
