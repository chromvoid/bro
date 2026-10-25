import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import ru from '@/locales/ru.json'
import BodySourceDetails from './BodySourceDetails.vue'
import { SourceAnalysisSummary } from './bodyArchive'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))

const archive = SourceAnalysisSummary.parse({
  id: 'synthetic-archive',
  source_id: 'synthetic-source',
  source_name: 'Synthetic DDX',
  provider_record_id: '7',
  measured_at: '2026-10-01T07:00:00Z',
  revision: 2,
  received_at: '2026-10-02T08:00:00Z',
})
const reply = (payload: Record<string, unknown>, summary = archive) => ({
  analysis: { ...summary, payload },
})
const cleanups: (() => void)[] = []

function show(
  summary: SourceAnalysisSummary | null = archive,
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  const wrapper = mount(BodySourceDetails, {
    props: { archive: summary, timezone: 'Europe/Moscow', today: '2026-10-03' },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
    },
  })
  cleanups.push(() => {
    wrapper.unmount()
    client.clear()
  })
  return wrapper
}
async function expand(wrapper: ReturnType<typeof show>) {
  wrapper.get('details').element.open = true
  await wrapper.get('details').trigger('toggle')
  await flushPromises()
}

beforeEach(() => mocks.get.mockReset())
afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup()
})

describe('source analysis details', () => {
  it('fetches lazily and displays source values without interpreting HTML or losing empty values', async () => {
    mocks.get.mockResolvedValue(
      reply({
        detail: {
          total_weight: 0,
          future: false,
          missing: null,
          blank: '',
          empty: [],
          object: {},
          note: '<img src=x onerror=alert(1)>',
          nested: [{ value: '  preserved  ' }],
        },
      }),
    )
    const wrapper = show()
    expect(mocks.get).not.toHaveBeenCalled()
    await expand(wrapper)
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid="archive-field"]')).toHaveLength(8))
    expect(mocks.get).toHaveBeenCalledWith(
      '/v1/personal/analyses/synthetic-archive',
      expect.any(AbortSignal),
    )
    expect(wrapper.text()).toContain('Ваши поправки хранятся отдельно')
    expect(wrapper.text()).toContain('total_weight')
    expect(wrapper.text()).toContain('Нет · false')
    expect(wrapper.text()).toContain('Нет значения · null')
    expect(wrapper.text()).toContain('Пустой список')
    expect(wrapper.text()).toContain('Пустая строка')
    expect(wrapper.text()).toContain('$.detail.nested[0].value')
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[style]').exists()).toBe(false)
  })

  it('explains an archive awaiting synchronization and makes no request', async () => {
    const wrapper = show(null)
    await expand(wrapper)
    expect(wrapper.text()).toContain('после следующей синхронизации')
    expect(wrapper.text()).not.toContain('полей не найдено')
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('asks for a shorter period when the archive list was truncated', async () => {
    const wrapper = show(null)
    await wrapper.setProps({ archiveListTruncated: true })
    await expand(wrapper)
    expect(wrapper.text()).toContain('Сократите период, чтобы открыть полный анализ')
    expect(wrapper.text()).not.toContain('после следующей синхронизации')
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('retains literal prototype-like keys when an open archive is refreshed', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mocks.get.mockResolvedValueOnce(
      reply(JSON.parse('{"detail":{"__proto__":{"value":"original-field"}}}')),
    )
    const wrapper = show(archive, client)
    await expand(wrapper)
    await vi.waitFor(() => expect(wrapper.text()).toContain('original-field'))
    mocks.get.mockResolvedValueOnce(
      reply(JSON.parse('{"detail":{"__proto__":{"value":"refreshed-field"}}}'), {
        ...archive,
        revision: 3,
      }),
    )
    await client.invalidateQueries({ queryKey: ['personal', 'analysis'] })
    await vi.waitFor(() => expect(wrapper.text()).toContain('refreshed-field'))
    expect(wrapper.text()).toContain('$.detail.__proto__.value')
    expect(wrapper.text()).not.toContain('original-field')
    expect(Object.hasOwn(Object.prototype, 'value')).toBe(false)
  })

  it('keeps an open archive with a retry when its refresh fails', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mocks.get.mockResolvedValueOnce(reply({ value: 'kept-field' }))
    const wrapper = show(archive, client)
    await expand(wrapper)
    await vi.waitFor(() => expect(wrapper.text()).toContain('kept-field'))
    mocks.get.mockRejectedValueOnce(new Error('offline'))
    await client.invalidateQueries({ queryKey: ['personal', 'analysis'] })
    await flushPromises()
    expect(wrapper.text()).toContain('kept-field')
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
    mocks.get.mockResolvedValueOnce(reply({ value: 'kept-field' }))
    await wrapper.get('[data-state="refresh-failed"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(false)
    // The refreshed archive is searched again before its fields are listed.
    await vi.waitFor(() => expect(wrapper.text()).toContain('kept-field'))
  })

  it('paginates a large archive and searches all pages by translated name and value', async () => {
    mocks.get.mockResolvedValue(
      reply({
        detail: Object.fromEntries([
          ...Array.from({ length: 81 }, (_, index) => [`unknown_${index}`, index]),
          ['total_weight', 76.25],
        ]),
      }),
    )
    const wrapper = show()
    await expand(wrapper)
    await vi.waitFor(() =>
      expect(wrapper.findAll('[data-testid="archive-field"]')).toHaveLength(40),
    )
    expect(wrapper.text()).toContain('Найдено полей: 82')
    const next = wrapper.findAll('button').find((button) => button.text() === 'Далее')!
    await next.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('unknown_79'))
    expect(wrapper.findAll('[data-testid="archive-field"]')).toHaveLength(40)
    await wrapper.get('input').setValue('вес')
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid="archive-field"]')).toHaveLength(1))
    expect(wrapper.text()).toContain('76.25')
    await wrapper.get('input').setValue('unknown_80')
    await vi.waitFor(() => expect(wrapper.text()).toContain('unknown_80'))
    expect(wrapper.text()).not.toContain('total_weight')
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })

  it('cancels the old request when the selection changes and rejects its late result', async () => {
    let resolveOld!: (value: unknown) => void
    let oldSignal: AbortSignal | undefined
    mocks.get.mockImplementationOnce((_path: string, signal: AbortSignal) => {
      oldSignal = signal
      return new Promise((resolve) => {
        resolveOld = resolve
      })
    })
    const wrapper = show()
    await expand(wrapper)
    const next = { ...archive, id: 'next-archive', provider_record_id: '8' }
    mocks.get.mockResolvedValueOnce(reply({ label: 'new-selected-analysis' }, next))
    await wrapper.setProps({ archive: next })
    await vi.waitFor(() => expect(wrapper.text()).toContain('new-selected-analysis'))
    expect(oldSignal?.aborted).toBe(true)
    resolveOld(reply({ label: 'stale-private-analysis' }))
    await flushPromises()
    expect(wrapper.text()).not.toContain('stale-private-analysis')
  })

  it('loads a new revision and safely shows a failed response without rendering its body', async () => {
    mocks.get.mockResolvedValueOnce(reply({ value: 'first-revision' }))
    const wrapper = show()
    await expand(wrapper)
    await vi.waitFor(() => expect(wrapper.text()).toContain('first-revision'))
    mocks.get.mockResolvedValueOnce(
      reply({ value: 'wrong-response-private-data' }, { ...archive, id: 'wrong' }),
    )
    await wrapper.setProps({ archive: { ...archive, revision: 3 } })
    await vi.waitFor(() => expect(wrapper.find('[data-state="error"]').exists()).toBe(true))
    expect(wrapper.text()).not.toContain('wrong-response-private-data')
    expect(wrapper.text()).not.toContain('first-revision')
    expect(mocks.get).toHaveBeenCalledTimes(2)
  })

  it('keeps long text bounded while allowing every fragment to be read', async () => {
    mocks.get.mockResolvedValue(reply({ text: 'a'.repeat(2000) + 'synthetic-last-fragment' }))
    const wrapper = show()
    await expand(wrapper)
    await vi.waitFor(() => expect(wrapper.find('pre').exists()).toBe(true))
    expect(wrapper.get('pre').text()).toHaveLength(2000)
    await wrapper.get('button[aria-label="Следующий фрагмент текста"]').trigger('click')
    expect(wrapper.get('pre').text()).toBe('synthetic-last-fragment')
    await wrapper.get('button[aria-label="Предыдущий фрагмент текста"]').trigger('click')
    expect(wrapper.get('pre').text()).toHaveLength(2000)
  })
})
