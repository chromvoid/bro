import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import AvailabilityNotice from './AvailabilityNotice.vue'
import type { Availability } from './availability'

const configured: Availability = {
  state: 'configured',
  reason: null,
  retry_at: null,
  checked_at: '2026-10-02T12:00:00Z',
}
function notice(props: { availability?: Availability; failed?: boolean; busy?: boolean }) {
  return mount(AvailabilityNotice, {
    props,
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

describe('assistant availability notice', () => {
  it('distinguishes a running first check, failure, and an explicit retry', async () => {
    const wrapper = notice({ busy: true })
    expect(wrapper.text()).toContain('Проверяем доступность ассистента…')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('Черновик сохранён')
    await wrapper.setProps({ busy: false, failed: true })
    expect(wrapper.text()).toContain('Не удалось проверить доступность')
    expect(wrapper.text()).not.toContain('Проверяем доступность')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    await wrapper.setProps({ busy: true })
    expect(wrapper.text()).toContain('Не удалось проверить доступность')
    expect(wrapper.text()).toContain('Проверяем доступность ассистента…')
    wrapper.unmount()
  })

  it.each(['PRIVATE_SERVER_DETAIL', 'checking', 'refresh'])(
    'does not mistake unknown reason %s for a translated availability state',
    (reason) => {
      const wrapper = notice({
        availability: { ...configured, state: 'blocked', reason },
      })
      expect(wrapper.text()).toContain('Ассистент сейчас недоступен. Причина не уточнена.')
      expect(wrapper.text()).not.toContain(reason)
      expect(wrapper.text()).not.toContain('Проверяем доступность')
      wrapper.unmount()
    },
  )

  it('keeps a known refusal visible during refresh and hides the notice after success', async () => {
    const wrapper = notice({
      availability: { ...configured, state: 'temporary', reason: 'quota_exhausted' },
      busy: true,
    })
    expect(wrapper.text()).toContain('Лимиты доступных подписок исчерпаны.')
    expect(wrapper.text()).toContain('Проверяем доступность ассистента…')
    await wrapper.setProps({ availability: configured, busy: false })
    expect(wrapper.find('[data-testid="assistant-availability"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
