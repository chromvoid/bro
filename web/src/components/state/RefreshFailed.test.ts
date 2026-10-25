import { QueryClient, VueQueryPlugin, useInfiniteQuery, useQuery } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, type PropType } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import { NetworkError } from '@/core/http'
import ErrorState from './ErrorState.vue'
import RefreshFailed from './RefreshFailed.vue'

/**
 * The screens' pattern over the queries `use` starts: the error state only for a first query
 * that never loaded, its data otherwise, and the notice for any failed refresh.
 */
const Screen = defineComponent({
  components: { ErrorState, RefreshFailed },
  props: { use: { type: Function as PropType<() => unknown[]>, required: true } },
  setup(props) {
    const queries = props.use()
    return { queries, first: queries[0] }
  },
  template: `
    <RefreshFailed :query="queries" />
    <ErrorState v-if="first.isLoadingError.value" :error="first.error.value" />
    <p v-else-if="first.data.value" data-testid="notes">{{ first.data.value }}</p>`,
})

let client: QueryClient
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  client.clear()
})

function show(use: () => unknown[]) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  wrapper = mount(Screen, {
    props: { use },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
    },
  })
  return wrapper
}
const offline = (): Promise<never> => Promise.reject(new NetworkError(false))
const notice = (view: VueWrapper) => view.find('[data-state="refresh-failed"]')

describe('a failed refresh over loaded data', () => {
  it('keeps the data with a retry, and the error state stays for data that never loaded', async () => {
    const read = vi.fn<() => Promise<string>>(offline)
    const view = show(() => [useQuery({ queryKey: ['notes'], queryFn: read })])
    await flushPromises()
    expect(view.find('[data-state="error"]').exists()).toBe(true)
    expect(notice(view).exists()).toBe(false)

    read.mockResolvedValueOnce('Купить хлеб')
    await client.refetchQueries({ queryKey: ['notes'] })
    await flushPromises()
    expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб')

    // A background refetch (a stream resync) on a flaky network.
    read.mockImplementationOnce(offline)
    await client.invalidateQueries()
    await flushPromises()
    expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб')
    expect(view.find('[data-state="error"]').exists()).toBe(false)
    expect(notice(view).text()).toContain('Не удалось обновить')

    let answer: (value: string) => void = () => undefined
    read.mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
    await notice(view).get('button').trigger('click')
    expect(notice(view).get('button').attributes('disabled')).toBeDefined()
    answer('Купить хлеб и молоко')
    await flushPromises()
    expect(notice(view).exists()).toBe(false)
    expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб и молоко')
  })

  it('retries only the queries that failed', async () => {
    const read = vi.fn(() => Promise.resolve('Заметки'))
    const other = vi.fn(() => Promise.resolve('Другое'))
    const view = show(() => [
      useQuery({ queryKey: ['notes'], queryFn: read }),
      useQuery({ queryKey: ['other'], queryFn: other }),
    ])
    await flushPromises()
    other.mockImplementationOnce(offline)
    await client.invalidateQueries()
    await flushPromises()
    expect(notice(view).exists()).toBe(true)
    read.mockClear()
    other.mockClear()
    await notice(view).get('button').trigger('click')
    await flushPromises()
    expect(read).not.toHaveBeenCalled()
    expect(other).toHaveBeenCalledOnce()
    expect(notice(view).exists()).toBe(false)
  })

  it('leaves a failed earlier page to the screen that asked for it', async () => {
    const page = vi.fn((cursor: number) =>
      Promise.resolve({ items: [`Сообщение ${cursor}`], next: cursor + 1 }),
    )
    let earlier: () => Promise<{ isError: boolean }> = async () => ({ isError: false })
    const view = show(() => {
      const history = useInfiniteQuery({
        queryKey: ['pages'],
        initialPageParam: 1,
        queryFn: ({ pageParam }) => page(pageParam),
        getNextPageParam: (last) => last.next,
      })
      earlier = () => history.fetchNextPage()
      return [history]
    })
    await flushPromises()
    page.mockImplementationOnce(offline)
    expect((await earlier()).isError).toBe(true)
    await flushPromises()
    expect(notice(view).exists()).toBe(false)
  })
})
