import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError } from '@/core/http'
import { i18n } from '@/i18n'
import DeleteAccount from './DeleteAccount.vue'
const mocks = vi.hoisted(() => ({ get: vi.fn(), send: vi.fn(), logout: vi.fn(), replace: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get, send: mocks.send } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'owner' }, logout: mocks.logout }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mocks.replace }) }))
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocks.get.mockResolvedValue({
    spaces: [{ id: 'space', name: 'Family', successors: [{ id: 'admin', name: 'Анна' }] }],
    last_admin: true,
  })
  mocks.logout.mockResolvedValue(undefined)
  mocks.replace.mockResolvedValue(undefined)
})
async function form() {
  const wrapper = mount(DeleteAccount, {
    global: { plugins: [i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  await flushPromises()
  await wrapper.get('input[type="password"]').setValue('private-password')
  return wrapper
}
describe('account deletion confirmation', () => {
  it('shows actual eligible successors and last administrator warning', async () => {
    const wrapper = await form()
    expect(wrapper.text()).toContain('Анна')
    expect(wrapper.text()).toContain('последний администратор')
  })
  it('keeps password out of persistent recovery proof and retries unknown requests identically', async () => {
    mocks.send.mockRejectedValue(new NetworkError(false))
    const wrapper = await form()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const first = mocks.send.mock.calls[0]!
    expect(sessionStorage.getItem('assistant.account-deletion-pending')).not.toContain(
      'private-password',
    )
    expect(wrapper.get('input[type="password"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.send.mock.calls[1]).toEqual(first)
    expect(mocks.logout).not.toHaveBeenCalled()
  })
  it('allows correcting a refused password and clears the recovery marker', async () => {
    mocks.send.mockRejectedValue(new ApiError(403, null, null, null, null))
    const wrapper = await form()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('input[type="password"]').attributes('disabled')).toBeUndefined()
    expect(sessionStorage.getItem('assistant.account-deletion-pending')).toBeNull()
  })
  it('purges the local session only once a server receipt confirms admission', async () => {
    mocks.send.mockResolvedValue({
      status: 202,
      data: {
        deletion_id: 'deletion',
        status: 'pending',
        receipt_token: 'receipt',
        status_url: '/v1/account-deletions/deletion',
      },
    })
    const wrapper = await form()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.logout).toHaveBeenCalledOnce()
    expect(mocks.replace).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'account-deletion' }),
    )
    expect(sessionStorage.getItem('assistant.account-deletion-pending')).toBeNull()
    expect(sessionStorage.getItem('assistant.account-deletion')).toContain('receipt')
  })
})
