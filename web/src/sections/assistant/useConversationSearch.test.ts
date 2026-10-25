import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { searchBounds, useConversationSearch } from './useConversationSearch'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: mocks }))

const hit = (id: string, author: 'user' | 'assistant' = 'user') => ({
  id,
  author,
  occurred_at: '2026-10-05T07:30:00Z',
  text: `Синтетический завтрак ${id}`,
})
function deferred() {
  let resolve!: (value: unknown) => void
  const promise = new Promise((yes) => (resolve = yes))
  return { promise, resolve }
}
const mounted: Array<{ wrapper: VueWrapper; client: QueryClient }> = []
function screen() {
  const text = ref('завтрак')
  const after = ref('')
  const through = ref('')
  const zone = ref('Europe/Moscow')
  const open = ref(true)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  let search!: ReturnType<typeof useConversationSearch>
  const wrapper = mount(
    defineComponent({
      setup() {
        search = useConversationSearch(text, after, through, zone, open)
        return () => h('div')
      },
    }),
    { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } },
  )
  mounted.push({ wrapper, client })
  return { ...search, text, after, through, zone }
}
beforeEach(() => mocks.get.mockReset())
afterEach(() => {
  for (const { wrapper, client } of mounted.splice(0)) {
    wrapper.unmount()
    client.clear()
  }
})

describe('conversation search pages', () => {
  it('appends both authors using the server cursor and keeps pages after a next-page error', async () => {
    mocks.get.mockResolvedValueOnce({ messages: [hit('reply', 'assistant')], cursor: 'reply' })
    const search = screen()
    await vi.waitFor(() => expect(search.hits.value).toHaveLength(1))
    mocks.get.mockRejectedValueOnce(new TypeError('offline'))
    await search.results.fetchNextPage()
    expect(search.results.isFetchNextPageError.value).toBe(true)
    expect(search.hits.value.map((item) => item.id)).toEqual(['reply'])
    mocks.get.mockResolvedValueOnce({ messages: [hit('older-user')], cursor: null })
    await search.results.fetchNextPage()
    expect(mocks.get).toHaveBeenLastCalledWith(
      '/v1/conversation/search?query=%D0%B7%D0%B0%D0%B2%D1%82%D1%80%D0%B0%D0%BA&cursor=reply',
      expect.any(AbortSignal),
    )
    expect(search.hits.value.map((item) => item.author)).toEqual(['assistant', 'user'])
    expect(search.results.hasNextPage.value).toBe(false)
  })

  it('aborts a pending next page on date changes and includes the entire final calendar day', async () => {
    mocks.get.mockResolvedValueOnce({ messages: [hit('first')], cursor: 'first' })
    const search = screen()
    await vi.waitFor(() => expect(search.hits.value).toHaveLength(1))
    const late = deferred()
    mocks.get.mockReturnValueOnce(late.promise)
    const loading = search.results.fetchNextPage()
    await flushPromises()
    const signal = mocks.get.mock.calls.at(-1)![1] as AbortSignal
    mocks.get.mockResolvedValue({ messages: [hit('dated', 'assistant')], cursor: null })
    search.after.value = '2026-10-01'
    search.through.value = '2026-10-05'
    await vi.waitFor(() => expect(search.hits.value.map((item) => item.id)).toEqual(['dated']))
    expect(signal.aborted).toBe(true)
    const params = new URL(mocks.get.mock.calls.at(-1)![0], 'http://example.test').searchParams
    expect(params.get('after')).toBe('2026-10-01')
    expect(params.get('before')).toBe('2026-10-06')
    expect(params.has('cursor')).toBe(false)
    late.resolve({ messages: [hit('stale-private')], cursor: null })
    await loading
    expect(search.hits.value.map((item) => item.id)).toEqual(['dated'])
  })

  it('cancels immediately while a new query is debouncing and restarts repeated criteria at page one', async () => {
    const late = deferred()
    mocks.get.mockReturnValueOnce(late.promise)
    const search = screen()
    await vi.waitFor(() => expect(mocks.get).toHaveBeenCalledOnce())
    const signal = mocks.get.mock.calls[0]![1] as AbortSignal
    mocks.get.mockResolvedValue({ messages: [hit('fresh')], cursor: 'fresh' })
    search.text.value = 'ужин'
    expect(signal.aborted).toBe(true)
    expect(search.hits.value).toEqual([])
    await vi.waitFor(() => expect(search.hits.value.map((item) => item.id)).toEqual(['fresh']))
    mocks.get.mockResolvedValueOnce({ messages: [hit('older')], cursor: null })
    await search.results.fetchNextPage()
    expect(search.hits.value).toHaveLength(2)
    search.text.value = 'завтрак'
    mocks.get.mockResolvedValue({ messages: [hit('first-page')], cursor: null })
    await vi.waitFor(() => expect(search.hits.value.map((item) => item.id)).toEqual(['first-page']))
    expect(
      new URL(mocks.get.mock.calls.at(-1)![0], 'http://example.test').searchParams.has('cursor'),
    ).toBe(false)
    late.resolve({ messages: [hit('late')], cursor: null })
    await flushPromises()
    expect(search.hits.value.map((item) => item.id)).toEqual(['first-page'])
  })
})

describe('inclusive account calendar dates', () => {
  it('shifts calendar days without relying on device UTC offsets or DST day length', () => {
    expect(searchBounds('2026-03-08', '2026-03-08')).toEqual({
      after: '2026-03-08',
      before: '2026-03-09',
    })
    expect(searchBounds('', '2026-12-31')).toEqual({ after: null, before: '2027-01-01' })
    expect(searchBounds('2026-10-06', '2026-10-05')).toBeNull()
    expect(searchBounds('', '2026-02-30')).toBeNull()
  })
})
