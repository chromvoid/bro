import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import InstallationInfo from './InstallationInfo.vue'

vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({
    data: ref({ instance_id: 'i', name: 'Bro', providers: [], privacy_url: null }),
  }),
}))

describe('InstallationInfo', () => {
  it('says what it is about and names the server in its text', () => {
    const wrapper = mount(InstallationInfo, { global: { plugins: [i18n] } })
    expect(wrapper.get('h2').text()).toBe('Данные и приватность')
    expect(wrapper.text()).toContain('хранятся на сервере «Bro»')
  })
})
