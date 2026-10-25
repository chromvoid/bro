import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NetworkError } from '@/core/http'
import { i18n } from '@/i18n'
import ReportButton from './ReportButton.vue'
const mocks = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { post: mocks.post } }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { id: 'owner' } }) }))
beforeEach(() => {
  vi.clearAllMocks()
})
describe('Circle report', () => {
  it('explains who receives the reported content and uses the same key after a lost reply', async () => {
    mocks.post.mockRejectedValue(new NetworkError(false))
    const wrapper = mount(ReportButton, {
      props: { targetType: 'message', targetId: 'message' },
      global: { plugins: [i18n], stubs: { Panel: { template: '<div><slot /></div>' } } },
    })
    expect(wrapper.text()).toContain('администратору этой установки')
    await wrapper.get('textarea').setValue('Угроза')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post.mock.calls[0]).toEqual(mocks.post.mock.calls[1])
    expect(mocks.post).toHaveBeenCalledWith(
      '/v1/community/reports',
      { target_type: 'message', target_id: 'message', reason: 'Угроза' },
      expect.objectContaining({ expectedUser: 'owner' }),
    )
  })
})
