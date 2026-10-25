import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { shallowReactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import { Source } from './api'
import DdxCard from './DdxCard.vue'

const mocks = vi.hoisted(() => ({
  session: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  invalidate: vi.fn(),
}))
vi.mock('@/stores/session', () => ({ useSession: mocks.session }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get, post: mocks.post } }))
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: mocks.invalidate }),
}))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({ operate: mocks.operate, failed: mocks.failed }),
}))

function managed(extra: Record<string, unknown> = {}) {
  return Source.parse({
    id: 'source-a',
    name: 'DDX InBody',
    kinds: ['weight', 'body_fat_percent'],
    status: 'connected',
    revision: 2,
    connection: {
      provider: 'ddx',
      generation: 1,
      revision: 7,
      auth_state: 'active',
      phone_masked: '+7••••••0001',
      automatic: true,
      ...extra,
    },
  })
}
const i18n = createI18n({ legacy: false, locale: 'ru', messages: { ru } })
const t = i18n.global.t
const waiting = {
  id: 'login-a',
  state: 'waiting_code',
  phone_masked: '+7••••••0001',
  can_resend: false,
  resend_at: '2026-10-02T10:01:00Z',
}

describe('DDX Sources card', () => {
  let wrapper: VueWrapper
  let session: ReturnType<typeof userSession>
  function userSession(canWrite = true) {
    return shallowReactive({
      state: 'ready',
      me: { id: 'user-a' },
      account: { store: { userId: 'user-a' } },
      can: () => canWrite,
    })
  }
  async function show(sources: Source[] = [], canWrite = true) {
    session = userSession(canWrite)
    mocks.session.mockReturnValue(session)
    wrapper = mount(DdxCard, {
      props: { sources, canWrite, today: '2026-10-02', timezone: 'Europe/Moscow' },
      global: {
        plugins: [i18n],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()
    return wrapper
  }
  function button(label: string) {
    const found = wrapper.findAll('button').find((item) => item.text() === label)
    if (!found) throw new Error(`Button not found: ${label}`)
    return found
  }
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-02T10:00:00Z'))
    vi.clearAllMocks()
    mocks.get.mockResolvedValue({ available: true, can_disconnect: true, active_login: null })
    mocks.post.mockResolvedValue({ data: { login: waiting } })
    mocks.operate.mockResolvedValue({})
  })
  afterEach(() => {
    wrapper?.unmount()
    vi.useRealTimers()
  })

  it('offers labelled mobile phone and one-time-code fields without using domain operations', async () => {
    await show()
    await button(t('personal.ddx.connect')).trigger('click')
    const phone = wrapper.get('input#ddx-phone')
    expect(phone.attributes()).toMatchObject({ type: 'tel', inputmode: 'tel', autocomplete: 'tel' })
    expect(wrapper.get('label[for="ddx-phone"]').text()).toBe(t('personal.ddx.phone'))
    await phone.setValue('8 (900) 000-00-01')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledWith(
      '/v1/personal/ddx/logins',
      {
        phone: '+79000000001',
        mode: 'connect',
      },
      expect.objectContaining({ expectedUser: 'user-a', key: expect.any(String) }),
    )
    const code = wrapper.get('input#ddx-code')
    expect(code.attributes()).toMatchObject({
      inputmode: 'numeric',
      autocomplete: 'one-time-code',
      maxlength: '6',
    })
    expect(wrapper.get('label[for="ddx-code"]').text()).toBe(t('personal.ddx.code'))
    expect(
      button(t('personal.ddx.resendWait', { seconds: 60 })).attributes('disabled'),
    ).toBeDefined()
    expect(mocks.operate).not.toHaveBeenCalled()
    session.account = { store: { userId: 'user-b' } }
    session.me = { id: 'user-b' }
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(waiting.phone_masked)
  })

  it('shows persisted sync counts and legacy history guidance without offering impossible reconnect', async () => {
    await show([
      managed({
        can_reconnect: false,
        last_success_at: '2026-10-02T08:30:00Z',
        last_result: {
          new_analyses: 2,
          updated_analyses: 1,
          warning: 'history_restricted',
          projection_incomplete: true,
        },
        warning: 'history_restricted',
      }),
    ])
    expect(wrapper.text()).toContain('Новых анализов: 2. Обновлено: 1.')
    expect(wrapper.text()).toContain(t('personal.ddx.legacyConnection'))
    expect(wrapper.text()).toContain(t('personal.sourceError.history_restricted'))
    expect(wrapper.text()).toContain(t('personal.sourceError.projection_incomplete'))
    expect(
      wrapper.findAll('button').some((item) => item.text() === t('personal.ddx.reconnect')),
    ).toBe(false)
    await button(t('personal.ddx.changeAccount')).trigger('click')
    expect(wrapper.text()).toContain(t('personal.ddx.replaceHint'))
    await wrapper.get('#ddx-phone').setValue('+79000000002')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post.mock.calls[0]?.[1]).toEqual({
      mode: 'replace',
      phone: '+79000000002',
      source_id: 'source-a',
      expected_revision: 7,
    })
  })

  it('keeps the source measurement date separate from the successful check time', async () => {
    await show([
      managed({
        last_measurement_at: '2026-09-25T09:15:00Z',
        last_success_at: '2026-10-02T08:30:00Z',
      }),
    ])
    const value = (label: string) =>
      wrapper
        .findAll('dl > div')
        .find((row) => row.get('dt').text() === label)
        ?.get('dd')
        .text()
    expect(value('Последний анализ')).toBe('25 сентября, 12:15')
    expect(value('Последняя успешная проверка')).toBe('Сегодня, 11:30')
    await wrapper.setProps({ sources: [managed({ last_success_at: '2026-10-02T08:30:00Z' })] })
    expect(value('Последний анализ')).toBe('Анализов пока нет')
    expect(value('Последняя успешная проверка')).toBe('Сегодня, 11:30')
  })

  it('disables provider actions while unavailable but allows credential removal when advertised', async () => {
    mocks.get.mockResolvedValue({ available: false, can_disconnect: true, active_login: null })
    await show([managed()])
    expect(wrapper.text()).toContain(t('personal.ddx.unavailable'))
    expect(button(t('personal.ddx.syncNow')).attributes('disabled')).toBeDefined()
    expect(button(t('personal.ddx.reconnect')).attributes('disabled')).toBeDefined()
    expect(wrapper.get('#ddx-automatic').attributes('disabled')).toBeDefined()
    expect(button(t('personal.ddx.disconnect')).attributes('disabled')).toBeUndefined()
    await button(t('personal.ddx.disconnect')).trigger('click')
    const confirmation = wrapper.get('[role="group"]')
    expect(confirmation.text()).toContain(t('personal.ddx.disconnectHint'))
    await confirmation.get('button').trigger('click')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledWith(
      '/v1/personal/ddx/connections/source-a/disconnect',
      { expected_revision: 7 },
      expect.objectContaining({ expectedUser: 'user-a', key: expect.any(String) }),
    )
    expect(mocks.operate).not.toHaveBeenCalled()
  })

  it('sends the connection revision when changing the hourly schedule', async () => {
    await show([managed()])
    await wrapper.get('#ddx-automatic').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      'source.automatic.update',
      {
        source_id: 'source-a',
        automatic: false,
      },
      { 'source-a': 7 },
    )
  })

  it('follows an existing queued sync without repeating it or showing an old failure', async () => {
    const queued = { id: 'run-a', source_id: 'source-a', state: 'queued', outcome: null }
    mocks.get.mockImplementation(async (path: string) =>
      path.includes('/sync-runs/')
        ? { ...queued, state: 'succeeded', outcome: { new_analyses: 1, updated_analyses: 0 } }
        : { available: true, can_disconnect: true, active_login: null },
    )
    await show([managed({ active_run: queued, last_result: { error: 'auth_required' } })])
    expect(button(t('personal.ddx.syncing')).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain(t('personal.ddx.error.auth_required'))
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain(t('personal.ddx.run.succeeded'))
    expect(wrapper.text()).toContain('Новых анализов: 1. Обновлено: 0.')
    expect(mocks.operate).not.toHaveBeenCalled()
    expect(mocks.invalidate).toHaveBeenCalledWith({ queryKey: ['personal'] })
  })

  it('explains a rejected OTP and closes the form after cancellation', async () => {
    mocks.get.mockResolvedValue({
      available: true,
      active_login: { ...waiting, error: 'invalid_code' },
    })
    mocks.post.mockResolvedValue({ data: { login: { ...waiting, state: 'cancelled' } } })
    await show()
    expect(wrapper.get('[role="alert"]').text()).toBe(t('personal.ddx.error.invalid_code'))
    await wrapper.get('#ddx-code').setValue('001234')
    await button(t('common.cancel')).trigger('click')
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.text()).toContain(t('personal.ddx.login.cancelled'))
    expect(mocks.post.mock.calls[0]?.[0]).toBe('/v1/personal/ddx/logins/login-a/cancel')
  })

  it('shows a ready login result only for its current active source', async () => {
    mocks.get.mockResolvedValue({
      available: true,
      active_login: { ...waiting, state: 'ready', source_id: 'source-a' },
    })
    await show([managed()])
    expect(wrapper.text()).toContain(t('personal.ddx.login.ready'))
    await wrapper.setProps({ sources: [managed({ auth_state: 'disconnected' })] })
    expect(wrapper.text()).not.toContain(t('personal.ddx.login.ready'))
    expect(button(t('personal.ddx.connect')).exists()).toBe(true)
    await wrapper.setProps({ sources: [{ ...managed(), id: 'source-b' }] })
    expect(wrapper.text()).not.toContain(t('personal.ddx.login.ready'))
  })

  it('shows read-only connection/history information without starting enrollment requests', async () => {
    await show([managed()], false)
    expect(mocks.get).not.toHaveBeenCalled()
    expect(wrapper.findAll('form')).toHaveLength(0)
    expect(
      wrapper.findAll('button').filter((item) => item.attributes('role') !== 'switch'),
    ).toHaveLength(0)
    expect(wrapper.get('#ddx-automatic').attributes('disabled')).toBeDefined()
    expect(wrapper.getComponent(RouterLinkStub).props('to')).toEqual({
      name: 'personal-history',
      query: { kind: 'body', period: 365 },
    })
  })
})
