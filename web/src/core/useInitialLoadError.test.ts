import { QueryClient, VueQueryPlugin, useQuery } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import ru from '@/locales/ru.json'
import { NetworkError } from './http'
import { useInitialLoadError } from './useInitialLoadError'

let client: QueryClient
let wrapper: VueWrapper
afterEach(() => {
  wrapper?.unmount()
  client?.clear()
  vi.restoreAllMocks()
})

function show(
  read: () => Promise<string>,
  key = ref(['notes']),
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  const Screen = defineComponent({
    components: { ErrorState, LoadingState },
    setup() {
      const query = useQuery({ queryKey: key, queryFn: read })
      return { query, loadError: useInitialLoadError(query) }
    },
    template: `
      <ErrorState v-if="loadError" :error="loadError.error" :retrying="loadError.retrying"
        @retry="query.refetch()" />
      <LoadingState v-else-if="query.isPending.value" />
      <p v-else data-testid="notes">{{ query.data.value }}</p>`,
  })
  client = queryClient
  wrapper = mount(Screen, {
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
    },
  })
  return wrapper
}

describe('an initial load error', () => {
  it.each(['button', 'resync'] as const)(
    'keeps the alert and retry spinner during a %s retry, then shows the data',
    async (trigger) => {
      const offline = new NetworkError(false)
      const read = vi.fn<() => Promise<string>>().mockRejectedValueOnce(offline)
      const view = show(read)
      expect(view.findComponent(LoadingState).exists()).toBe(true)
      await flushPromises()
      const alert = view.get('[role="alert"]').element
      expect(view.getComponent(ErrorState).props('error')).toBe(offline)
      expect(view.getComponent(ErrorState).props('retrying')).toBe(false)

      let answer: (value: string) => void = () => undefined
      read.mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
      if (trigger === 'button') await view.get('[role="alert"] button').trigger('click')
      else void client.invalidateQueries({ queryKey: ['notes'] })
      await flushPromises()
      expect(client.getQueryState(['notes'])).toMatchObject({
        status: 'pending',
        error: null,
        data: undefined,
        fetchStatus: 'fetching',
      })
      expect(view.get('[role="alert"]').element).toBe(alert)
      expect(view.getComponent(ErrorState).props('error')).toBe(offline)
      expect(view.getComponent(ErrorState).props('retrying')).toBe(true)
      expect(view.get('[role="alert"] button').attributes('disabled')).toBeDefined()
      expect(view.find('[role="alert"] .animate-spin').exists()).toBe(true)
      expect(view.findComponent(LoadingState).exists()).toBe(false)

      answer('Купить хлеб')
      await flushPromises()
      expect(view.find('[role="alert"]').exists()).toBe(false)
      expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб')

      read.mockRejectedValueOnce(offline)
      await client.invalidateQueries({ queryKey: ['notes'] })
      await flushPromises()
      expect(view.find('[role="alert"]').exists()).toBe(false)
      expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб')
    },
  )
  it('starts a different query key with loading instead of the previous query error', async () => {
    const key = ref(['notes'])
    const read = vi.fn<() => Promise<string>>().mockRejectedValueOnce(new NetworkError(false))
    const view = show(read, key)
    await flushPromises()
    expect(view.find('[role="alert"]').exists()).toBe(true)

    let answer: (value: string) => void = () => undefined
    read.mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
    key.value = ['other-notes']
    await flushPromises()
    expect(view.find('[role="alert"]').exists()).toBe(false)
    expect(view.findComponent(LoadingState).exists()).toBe(true)

    answer('Другая заметка')
    await flushPromises()
    expect(view.get('[data-testid="notes"]').text()).toBe('Другая заметка')
  })

  it.each(['success', 'error'] as const)(
    'restores the first key error during a retry after another key finishes with %s',
    async (result) => {
      const now = vi.spyOn(Date, 'now').mockReturnValue(1_000)
      const key = ref(['notes'])
      const firstError = new NetworkError(false)
      const otherError = new NetworkError(true)
      const read = vi.fn<() => Promise<string>>().mockRejectedValueOnce(firstError)
      const view = show(read, key)
      await flushPromises()
      expect(view.getComponent(ErrorState).props('error')).toBe(firstError)

      now.mockReturnValue(2_000)
      if (result === 'success') read.mockResolvedValueOnce('Другая заметка')
      else read.mockRejectedValueOnce(otherError)
      key.value = ['other-notes']
      await flushPromises()
      if (result === 'success') {
        expect(view.get('[data-testid="notes"]').text()).toBe('Другая заметка')
      } else {
        expect(view.getComponent(ErrorState).props('error')).toBe(otherError)
      }

      let answer: (value: string) => void = () => undefined
      read.mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
      key.value = ['notes']
      await flushPromises()
      expect(client.getQueryState(['notes'])).toMatchObject({
        status: 'pending',
        error: null,
        errorUpdatedAt: 1_000,
        data: undefined,
        fetchStatus: 'fetching',
      })
      expect(view.find('[role="alert"]').exists()).toBe(true)
      expect(view.getComponent(ErrorState).props('error')).toBe(firstError)
      expect(view.getComponent(ErrorState).props('retrying')).toBe(true)
      expect(view.find('[role="alert"] .animate-spin').exists()).toBe(true)
      expect(view.findComponent(LoadingState).exists()).toBe(false)

      answer('Купить хлеб')
      await flushPromises()
      expect(view.find('[role="alert"]').exists()).toBe(false)
      expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб')
    },
  )

  it('shows loading for an unseen cached error during a retry, even after another key fails', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000)
    const key = ref(['notes'])
    const firstError = new NetworkError(false)
    const otherError = new NetworkError(true)
    const read = vi.fn<() => Promise<string>>().mockRejectedValueOnce(firstError)
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    await expect(client.fetchQuery({ queryKey: key.value, queryFn: read })).rejects.toBe(firstError)
    let answer: (value: string) => void = () => undefined
    read.mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
    const retry = client.fetchQuery({ queryKey: key.value, queryFn: read }).catch(() => undefined)
    const view = show(read, key, client)
    await flushPromises()
    expect(client.getQueryState(['notes'])).toMatchObject({
      status: 'pending',
      error: null,
      errorUpdatedAt: 1_000,
      data: undefined,
      fetchStatus: 'fetching',
    })
    expect(view.find('[role="alert"]').exists()).toBe(false)
    expect(view.findComponent(LoadingState).exists()).toBe(true)

    now.mockReturnValue(2_000)
    read.mockRejectedValueOnce(otherError)
    key.value = ['other-notes']
    await flushPromises()
    expect(view.getComponent(ErrorState).props('error')).toBe(otherError)

    key.value = ['notes']
    await flushPromises()
    expect(view.find('[role="alert"]').exists()).toBe(false)
    expect(view.findComponent(LoadingState).exists()).toBe(true)

    answer('Купить хлеб')
    await retry
    await flushPromises()
    expect(view.get('[data-testid="notes"]').text()).toBe('Купить хлеб')
  })
})
