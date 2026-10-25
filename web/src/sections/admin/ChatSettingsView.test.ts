import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import ChatSettingsView from './ChatSettingsView.vue'
import { ChatSettings, providers, purposes } from './chatSettings'

const mocks = vi.hoisted(() => ({ get: vi.fn(), send: vi.fn(), success: vi.fn(), error: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get, send: mocks.send } }))
vi.mock('vue-sonner', () => ({ toast: { success: mocks.success, error: mocks.error } }))

function settings() {
  return {
    revision: 7,
    providers: { codex: { model: 'gpt-5.6-terra', reasoning_effort: 'medium' } },
    defaults: { codex: { model: 'gpt-6.1-sol', reasoning_effort: 'low' } },
    catalog: {
      codex: [
        { id: 'gpt-5.6-terra', name: 'GPT-5.6 Terra', reasoning_efforts: ['low', 'medium'] },
        { id: 'gpt-6.1-sol', name: 'GPT-6.1 Sol', reasoning_efforts: ['low', 'high'] },
      ],
    },
  }
}

describe('Codex chat model settings', () => {
  let wrapper: VueWrapper | undefined
  let client: QueryClient | undefined

  beforeEach(() => vi.resetAllMocks())
  afterEach(() => {
    wrapper?.unmount()
    client?.clear()
  })

  async function open(response = settings()) {
    mocks.get.mockResolvedValue(response)
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    wrapper = mount(ChatSettingsView, {
      global: {
        plugins: [
          [VueQueryPlugin, { queryClient: client }],
          createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        ],
        stubs: { PageHeader: true, RouterLink: true },
      },
    })
    await vi.waitFor(() => expect(wrapper?.find('form').exists()).toBe(true))
    return wrapper
  }

  it('accepts the Codex-only response and strips legacy Claude keys', () => {
    const current = settings()
    expect(ChatSettings.parse(current)).toEqual({ ...current, purposes: {}, purpose_defaults: {} })
    expect(providers).toEqual(['codex'])
    expect(purposes).toEqual(['research', 'quick_search', 'food_search'])
    expect(
      ChatSettings.parse({
        ...current,
        providers: { ...current.providers, claude: null },
        defaults: { ...current.defaults, claude: { model: 'old', reasoning_effort: 'high' } },
        catalog: { ...current.catalog, claude: [{ id: 'old' }] },
      }),
    ).toEqual({ ...current, purposes: {}, purpose_defaults: {} })
  })

  it('renders one provider and saves only Codex with the current revision', async () => {
    const view = await open()
    expect(view.findAll('fieldset')).toHaveLength(1)
    expect(view.get('legend').text()).toBe('Чат')
    expect(view.find('#chat-model-claude').exists()).toBe(false)
    expect(view.find('#chat-reasoning-claude').exists()).toBe(false)
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await view.get('#chat-model-codex').setValue('gpt-6.1-sol')
    // Changing model also replaces an effort absent from the selected catalog entry.
    expect((view.get('#chat-reasoning-codex').element as HTMLSelectElement).value).toBe('low')
    const updated = settings()
    updated.revision = 8
    updated.providers = { codex: { model: 'gpt-6.1-sol', reasoning_effort: 'low' } }
    mocks.send.mockResolvedValue({ data: updated })
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.send).toHaveBeenCalledOnce()
    expect(mocks.send).toHaveBeenCalledWith(
      'PUT',
      '/v1/admin/chat-settings',
      JSON.stringify({
        providers: { codex: { model: 'gpt-6.1-sol', reasoning_effort: 'low' } },
        purposes: {},
        revision: 7,
      }),
    )
    expect(mocks.success).toHaveBeenCalledOnce()
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('restores gpt-6.1-sol low defaults without sending until save', async () => {
    const view = await open()
    await view.get('button[type="button"]').trigger('click')
    expect((view.get('#chat-model-codex').element as HTMLSelectElement).value).toBe('gpt-6.1-sol')
    expect((view.get('#chat-reasoning-codex').element as HTMLSelectElement).value).toBe('low')
    expect(view.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect(mocks.send).not.toHaveBeenCalled()
    mocks.send.mockResolvedValue({
      data: { ...settings(), providers: settings().defaults, revision: 8 },
    })
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.send).toHaveBeenCalledWith(
      'PUT',
      '/v1/admin/chat-settings',
      JSON.stringify({ providers: settings().defaults, purposes: {}, revision: 7 }),
    )
  })

  it('shows the declared research calls after the chat and saves them with it', async () => {
    const response = {
      ...settings(),
      purposes: {
        food_search: { model: 'gpt-5.6-terra', reasoning_effort: 'low' },
        research: { model: 'gpt-6.1-sol', reasoning_effort: 'low' },
        plugin_unknown_here: { model: 'gpt-6.1-sol', reasoning_effort: 'low' },
      },
      purpose_defaults: {
        food_search: { model: 'gpt-6.1-sol', reasoning_effort: 'low' },
        research: { model: 'gpt-6.1-sol', reasoning_effort: 'low' },
        plugin_unknown_here: { model: 'gpt-6.1-sol', reasoning_effort: 'low' },
      },
    }
    const view = await open(response)
    expect(view.findAll('legend').map((legend) => legend.text())).toEqual([
      'Чат',
      'Исследование',
      'Поиск продуктов',
    ])
    expect(view.text()).toContain('Подробный поиск в интернете')
    await view.get('#chat-model-research').setValue('gpt-5.6-terra')
    await view.get('#chat-reasoning-research').setValue('medium')
    mocks.send.mockResolvedValue({ data: { ...response, revision: 8 } })
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(JSON.parse(mocks.send.mock.calls[0]![2])).toEqual({
      providers: settings().providers,
      purposes: {
        ...response.purposes,
        research: { model: 'gpt-5.6-terra', reasoning_effort: 'medium' },
      },
      revision: 7,
    })
    await view.get('button[type="button"]').trigger('click')
    expect((view.get('#chat-model-food_search').element as HTMLSelectElement).value).toBe(
      'gpt-6.1-sol',
    )
  })
})
