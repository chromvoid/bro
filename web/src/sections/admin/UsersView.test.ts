import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import UsersView from './UsersView.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get, post: mocks.post } }))
vi.mock('vue-sonner', () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { id: 'owner' } }) }))

const account = (label: string, provider: string, enabled = true) => ({
  id: label,
  provider,
  label,
  enabled,
  revision: 1,
})
const person = (id: string, name: string, system_admin = false) => ({
  id,
  login: id,
  name,
  status: 'active',
  system_admin,
  revision: 1,
  scopes: ['session.control'],
})
const responses: Record<string, unknown> = {
  '/v1/admin/users': {
    users: [person('owner', 'Владелец', true), person('guest', 'Гостья')],
    available_scopes: ['session.control', 'tasks.read'],
  },
  '/v1/accounts/snapshot': {
    accounts: [
      account('Семья', 'codex'),
      account('Работа', 'claude'),
      account('Старая', 'codex', false),
    ],
  },
  '/v1/admin/access': {
    grants: [
      { user_id: 'owner', accounts: ['Семья', 'Работа'], speech_connections: [] },
      { user_id: 'guest', accounts: [], speech_connections: [] },
    ],
  },
  '/v1/admin/speech': {
    providers: [],
    connections: [
      {
        id: 'voice',
        ownership: 'shared',
        provider: 'openai',
        label: 'Общий голос',
        enabled: true,
        revision: 1,
        has_key: true,
        capabilities: ['asr'],
      },
    ],
  },
}

let view: VueWrapper | undefined
let client: QueryClient | undefined
beforeEach(() => {
  vi.resetAllMocks()
  mocks.get.mockImplementation((path: string) => Promise.resolve(structuredClone(responses[path])))
  mocks.post.mockResolvedValue({ data: { token: 'synthetic' } })
})
afterEach(() => {
  view?.unmount()
  client?.clear()
})

function open() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  view = mount(UsersView, {
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: { PageHeader: true, Panel: { template: '<div><slot /></div>' } },
    },
  })
  return view
}
const button = (wrapper: VueWrapper, text: string) =>
  wrapper.findAll('button').find((item) => item.text() === text)
const boxes = (wrapper: VueWrapper, testid: string) =>
  wrapper.get(`[data-testid="${testid}"]`).findAll('[role="checkbox"]')
const choices = (wrapper: VueWrapper, testid: string) =>
  wrapper
    .get(`[data-testid="${testid}"]`)
    .findAll('label')
    .map((label) => [label.text(), label.get('[role="checkbox"]').attributes('aria-checked')])

describe('an invitation', () => {
  it('grants every working Codex subscription; the administrator may tick others', async () => {
    const wrapper = open()
    // Enabled only once the subscriptions are known, so the Codex ones are always ticked.
    await vi.waitFor(() => {
      expect(button(wrapper, 'Новое приглашение')).toBeDefined()
      expect(button(wrapper, 'Новое приглашение')!.attributes('disabled')).toBeUndefined()
    })
    await button(wrapper, 'Новое приглашение')!.trigger('click')
    expect(choices(wrapper, 'invite-subscriptions')).toEqual([
      ['Семья Codex', 'true'],
      ['Работа Claude', 'false'],
    ])

    await boxes(wrapper, 'invite-subscriptions')[1]!.trigger('click')
    await button(wrapper, 'Создать ссылку на 7 дней')!.trigger('click')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledWith('/v1/admin/tokens', {
      scopes: ['session.control', 'tasks.read'],
      accounts: ['Семья', 'Работа'],
    })
    expect(
      (wrapper.get('[data-testid="invite-link"] input').element as HTMLInputElement).value,
    ).toBe(
      `https://bro.chromvoid.com/join#server=${encodeURIComponent(location.origin)}&kind=invite&token=synthetic`,
    )
  })
})

describe('a person’s card', () => {
  it('builds a reset link for this installation on the public join page', async () => {
    const wrapper = open()
    await vi.waitFor(() => expect(wrapper.text()).toContain('Гостья'))
    await wrapper.get('[data-testid="users"]').findAll('li')[1]!.get('button').trigger('click')
    await button(wrapper, 'Выдать ссылку сброса пароля')!.trigger('click')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledWith('/v1/admin/tokens', { user_id: 'guest' })
    expect(
      (wrapper.get('[data-testid="reset-link"] input').element as HTMLInputElement).value,
    ).toBe(
      `https://bro.chromvoid.com/join#server=${encodeURIComponent(location.origin)}&kind=reset&token=synthetic`,
    )
  })

  it('marks a person without subscriptions and saves the ones ticked for them', async () => {
    const wrapper = open()
    await vi.waitFor(() => expect(wrapper.text()).toContain('Без подписок'))
    const rows = wrapper.get('[data-testid="users"]').findAll('li')
    expect(rows.map((row) => row.text().includes('Без подписок'))).toEqual([false, true])

    await rows[1]!.get('button').trigger('click')
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="user-subscriptions"] label').exists()).toBe(true),
    )
    expect(choices(wrapper, 'user-subscriptions')).toEqual([
      ['Семья Codex', 'false'],
      ['Работа Claude', 'false'],
      ['Общий голос', 'false'],
    ])
    await boxes(wrapper, 'user-subscriptions')[0]!.trigger('click')
    await boxes(wrapper, 'user-subscriptions')[2]!.trigger('click')
    await button(wrapper, 'Сохранить подписки')!.trigger('click')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledWith('/v1/admin/users/guest/access', {
      accounts: ['Семья'],
      speech_connections: ['voice'],
    })
  })
})
