import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import DerivedResultDetail from './DerivedResultDetail.vue'
import { producerArchiveItem } from './derivedScores.fixtures'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
const session = reactive({ me: { id: 'owner' } as { id: string } | null, readable: true })
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    get me() {
      return session.me
    },
    can: () => session.readable,
  }),
}))
function show() {
  const saved = producerArchiveItem()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(DerivedResultDetail, {
    props: {
      resultId: saved.result_id,
      sourceId: saved.source_scope,
      definitionId: saved.definition_id,
      formulaVersion: saved.formula_version,
      date: saved.date,
      timezone: saved.time_zone,
      today: '2026-10-09',
    },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
    },
  })
  return {
    wrapper,
    saved,
    client,
    close: () => {
      wrapper.unmount()
      client.clear()
    },
  }
}
beforeEach(() => {
  vi.resetAllMocks()
  session.me = { id: 'owner' }
  session.readable = true
})
describe('lazy immutable calculation details', () => {
  it('loads and retains the full captured result only after expansion', async () => {
    const { wrapper, saved, close } = show()
    transport.get.mockResolvedValue(saved)
    await flushPromises()
    expect(transport.get).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(transport.get).toHaveBeenCalledExactlyOnceWith(
      `/v1/personal/derived-result-versions/${saved.result_id}`,
      expect.any(AbortSignal),
    )
    expect(wrapper.text()).toContain('lower_fifth_percentile')
    expect(wrapper.text()).toContain('evaluation-operation')
    expect(wrapper.text()).toContain('git_blob_sha1')
    expect(wrapper.text()).toContain('"value": [')
    await wrapper.get('button').trigger('click')
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    expect(transport.get).toHaveBeenCalledTimes(1)
    close()
  })
  it('rejects a different immutable identity without showing its input data', async () => {
    const { wrapper, saved, close } = show()
    transport.get.mockResolvedValue({ ...saved, result_id: 'b'.repeat(64) })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('lower_fifth_percentile')
    close()
  })
  it('keeps only the immutable capture across domain and account-stream invalidation', async () => {
    const { wrapper, saved, client, close } = show()
    transport.get.mockResolvedValue(saved)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    await client.invalidateQueries({ queryKey: ['personal'] })
    await client.invalidateQueries()
    await flushPromises()
    expect(transport.get).toHaveBeenCalledTimes(1)
    expect(
      client.getQueryData(['personal', 'derived-result', 'owner', saved.result_id]),
    ).not.toHaveProperty('is_current')
    expect(wrapper.text()).not.toContain('"is_current"')
    await wrapper.get('button').trigger('click')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(true)
    expect(transport.get).toHaveBeenCalledTimes(1)
    close()
  })
  it('closes cached details on account changes and fetches again for the new owner', async () => {
    const { wrapper, saved, close } = show()
    transport.get.mockResolvedValue(saved)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    session.me = { id: 'other-owner' }
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(true)
    expect(transport.get).toHaveBeenCalledTimes(2)
    session.readable = false
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-result-detail"]').exists()).toBe(false)
    session.readable = true
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    close()
  })
  it('does not render a cached result under a different source, then reads the new result', async () => {
    const { wrapper, saved, close } = show()
    transport.get.mockResolvedValue(saved)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    const source = 'fbca20c0-d307-4cb9-a5d1-a1b9223bf390'
    await wrapper.setProps({ sourceId: source })
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    expect(transport.get).toHaveBeenCalledTimes(1)
    const next = {
      ...saved,
      result_id: 'b'.repeat(64),
      source_scope: source,
      score: { ...saved.score, context: { ...saved.score.context, source_id: source } },
    }
    transport.get.mockResolvedValue(next)
    await wrapper.setProps({ resultId: next.result_id })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(true)
    expect(transport.get).toHaveBeenLastCalledWith(
      `/v1/personal/derived-result-versions/${next.result_id}`,
      expect.any(AbortSignal),
    )
    expect(transport.get).toHaveBeenCalledTimes(2)
    close()
  })
  it('rejects a response that arrives after its owner has changed', async () => {
    const { wrapper, saved, client, close } = show()
    let resolve!: (value: typeof saved) => void
    transport.get.mockReturnValue(
      new Promise<typeof saved>((complete) => {
        resolve = complete
      }),
    )
    await wrapper.get('button').trigger('click')
    session.me = { id: 'other-owner' }
    await flushPromises()
    resolve(saved)
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(false)
    expect(
      client.getQueryData(['personal', 'derived-result', 'owner', saved.result_id]),
    ).toBeUndefined()
    close()
  })
  it('allows explicit retry after an initial read error with a static capture query', async () => {
    const { wrapper, saved, close } = show()
    transport.get.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(saved)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    const retry = wrapper.get('[data-state="error"]').get('button')
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="derived-score-details"]').exists()).toBe(true)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
    expect(transport.get).toHaveBeenCalledTimes(2)
    close()
  })
})
