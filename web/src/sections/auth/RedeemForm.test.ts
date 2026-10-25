import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { useSession } from '@/stores/session'
import RedeemForm from './RedeemForm.vue'

const PASSWORD = 'Синтетический-пароль-1'

/** An invitation opened in a browser whose own zone is [zone]; the list leaves «UTC» out. */
function invitation(zone: string) {
  const resolved = Intl.DateTimeFormat.prototype.resolvedOptions
  const options = vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions')
  options.mockImplementation(function (this: Intl.DateTimeFormat) {
    return { ...resolved.call(this), timeZone: zone }
  })
  vi.spyOn(Intl, 'supportedValuesOf').mockReturnValue(['Europe/Belgrade', 'Europe/Moscow'])
  const pinia = createPinia()
  const session = useSession(pinia)
  const redeem = vi.spyOn(session, 'redeem').mockResolvedValue(undefined)
  const wrapper = mount(RedeemForm, {
    props: { link: { kind: 'invite', token: 'synthetic-token' } },
    global: { plugins: [pinia, i18n] },
  })
  return { wrapper, redeem }
}

async function accept(wrapper: ReturnType<typeof invitation>['wrapper']) {
  await wrapper.get('#redeem-name').setValue('Мария')
  await wrapper.get('#redeem-login').setValue('maria')
  await wrapper.get('#redeem-password').setValue(PASSWORD)
  await wrapper.get('#redeem-repeat').setValue(PASSWORD)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

afterEach(() => vi.restoreAllMocks())

describe('RedeemForm', () => {
  it('accepts the browser’s own zone even when the zone list leaves it out', async () => {
    const { wrapper, redeem } = invitation('UTC')
    expect((wrapper.get('#redeem-timezone').element as HTMLInputElement).value).toBe('UTC')
    await accept(wrapper)
    expect(redeem).toHaveBeenCalledWith({
      token: 'synthetic-token',
      password: PASSWORD,
      login: 'maria',
      name: 'Мария',
      timezone: 'UTC',
    })
    expect(wrapper.text()).not.toContain('Выберите часовой пояс из списка.')
  })

  it('refuses a zone no browser knows', async () => {
    const { wrapper, redeem } = invitation('UTC')
    await wrapper.get('#redeem-timezone').setValue('Europe/Mosccow')
    await accept(wrapper)
    expect(redeem).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Выберите часовой пояс из списка.')
  })
})
