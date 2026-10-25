import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import ResearchSourcesView from './ResearchSourcesView.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/core/operations', () => ({ useLedger: () => ({ operate: mocks.operate }) }))
vi.mock('vue-sonner', () => ({ toast: { success: mocks.success, error: mocks.error } }))

const source = (fields: Record<string, unknown>) => ({
  topics: ['currency'],
  entry_url: null,
  role: 'reference',
  priority: 50,
  status: 'active',
  origin: 'seed',
  best_for: '',
  limitations: '',
  revision: 1,
  ...fields,
})

function registry() {
  return {
    sources: [
      source({
        id: 'cbr',
        slug: 'cbr',
        name: 'Банк России',
        domains: ['cbr.ru'],
      }),
      source({
        id: 'moex',
        slug: 'moex',
        name: 'Мосбиржа',
        domains: ['moex.com'],
        topics: ['economy'],
      }),
      source({
        id: 'new',
        slug: 'new',
        name: 'Наблюдаемый сайт',
        domains: ['observed.example'],
        status: 'candidate',
        origin: 'observed',
        revision: 3,
      }),
    ],
    topics: [
      { slug: 'currency', label: 'Курсы валют', status: 'active' },
      { slug: 'economy', label: 'Экономика и финансы', status: 'active' },
    ],
    settings: { unlisted_stats: false, model_discovery: false, revision: 4 },
  }
}

describe('research sources', () => {
  let wrapper: VueWrapper | undefined
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.get.mockResolvedValue(registry())
    mocks.operate.mockResolvedValue({})
  })
  afterEach(() => wrapper?.unmount())

  async function open() {
    wrapper = mount(ResearchSourcesView, {
      global: {
        plugins: [
          [
            VueQueryPlugin,
            { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
          ],
          createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        ],
        stubs: { PageHeader: true, Panel: true },
      },
      attachTo: document.body,
    })
    await vi.waitFor(() => expect(wrapper!.text()).toContain('Банк России'))
    return wrapper
  }

  it('groups active sources by topic', async () => {
    const view = await open()
    const headings = view.findAll('h2').map((heading) => heading.text())
    expect(headings).toEqual(['Курсы валют', 'Экономика и финансы'])
    expect(view.text()).toContain('cbr.ru')
    expect(view.text()).toContain('moex.com')
    expect(view.text()).not.toContain('Наблюдаемый сайт')
  })

  it('approves a candidate and switches a setting with the revisions the page read', async () => {
    const view = await open()
    const triggers = view.findAll('[role="tab"]')
    expect(triggers[1]!.text()).toBe('Ждут решения (1)')
    await triggers[1]!.trigger('mousedown', { button: 0 })
    await flushPromises()
    const pending = view.get('[data-testid="pending-source"]')
    await pending.findAll('button')[0]!.trigger('click')
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'candidate.approve',
      payload: { source_id: 'new' },
      expected_revisions: { new: 3 },
    })
    await triggers[2]!.trigger('mousedown', { button: 0 })
    await flushPromises()
    await view.get('[aria-label="Предложения модели"]').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith({
      kind: 'settings.update',
      payload: { model_discovery: true, revision: 4 },
    })
  })
})
