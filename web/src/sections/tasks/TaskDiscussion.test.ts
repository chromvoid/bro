import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref, type Ref } from 'vue'
import { ApiError } from '@/core/http'
import type { History } from '@/sections/circle/api'
import TaskDiscussion from './TaskDiscussion.vue'

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  invalidateQueries: vi.fn(),
  clear: vi.fn(),
  refetch: vi.fn(),
  toastError: vi.fn(),
  draft: null as unknown as Ref<{ text: string }>,
  journal: {
    pending: vi.fn(),
    subscribe: vi.fn(),
    submit: vi.fn(),
    acknowledge: vi.fn(),
    retry: vi.fn(),
  },
  history: null as unknown as { data: Ref<{ pages: History[] }> },
  visible: null as unknown as Ref<DocumentVisibilityState>,
}))
vi.mock('@/core/client', () => ({ api: { post: mocks.post } }))
vi.mock('@tanstack/vue-query', () => ({
  useInfiniteQuery: () => ({
    ...mocks.history,
    isLoadingError: ref(false),
    isPending: ref(false),
    isRefetchError: ref(false),
    hasNextPage: ref(true),
    isFetchingNextPage: ref(false),
    refetch: mocks.refetch,
  }),
  useQueryClient: () => ({ invalidateQueries: mocks.invalidateQueries }),
}))
vi.mock('@vueuse/core', async (original) => ({
  ...(await original<typeof import('@vueuse/core')>()),
  useDocumentVisibility: () => mocks.visible,
}))
vi.mock('@/core/useDraft', () => ({
  useDraft: () => {
    const value = ref({ text: '' })
    mocks.draft = value
    return { value, clear: mocks.clear }
  },
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'me', timezone: 'UTC' }, account: { journal: mocks.journal } }),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('vue-sonner', () => ({ toast: Object.assign(vi.fn(), { error: mocks.toastError }) }))

const page = (ids: string[]): History => ({
  channel: { id: 'channel' },
  messages: ids.map((id) => ({
    id,
    author: 'user',
    actor_user_id: 'other',
    text: id,
    created_at: '2026-10-08T09:00:00Z',
  })),
  next_cursor: null,
})
type Observed = { callback: IntersectionObserverCallback; element: Element | null }
let observers: Observed[]
beforeEach(() => {
  mocks.post.mockReset().mockResolvedValue({ data: { read: true } })
  mocks.invalidateQueries.mockReset().mockResolvedValue(undefined)
  mocks.clear.mockReset().mockResolvedValue(undefined)
  mocks.refetch.mockReset().mockResolvedValue(undefined)
  mocks.toastError.mockReset()
  mocks.journal.pending.mockReset().mockResolvedValue([])
  mocks.journal.subscribe.mockReset().mockReturnValue(() => undefined)
  mocks.journal.submit.mockReset().mockResolvedValue(undefined)
  mocks.journal.acknowledge.mockReset().mockResolvedValue(undefined)
  mocks.journal.retry.mockReset().mockResolvedValue(undefined)
  mocks.visible = ref('visible')
  mocks.history = { data: ref({ pages: [page(['newest'])] }) }
  observers = []
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      entry: Observed
      constructor(callback: IntersectionObserverCallback) {
        this.entry = { callback, element: null }
        observers.push(this.entry)
      }
      observe(element: Element) {
        this.entry.element = element
      }
      disconnect() {}
    },
  )
})
afterEach(() => vi.unstubAllGlobals())
function intersect(observer = observers.at(-1)!) {
  observer.callback(
    [{ target: observer.element, isIntersecting: true } as IntersectionObserverEntry],
    {} as IntersectionObserver,
  )
}
const discussion = (active = true) =>
  mount(TaskDiscussion, { props: { channelId: 'channel', canWrite: false, active } })

describe('task discussion reading', () => {
  it('waits for the visible latest message and refreshes badge projections after acknowledgement', async () => {
    const view = discussion()
    await flushPromises()
    expect(mocks.post).not.toHaveBeenCalled()
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledExactlyOnceWith('/v1/channels/channel/read', {
      through_message_id: 'newest',
    })
    expect(mocks.invalidateQueries.mock.calls).toEqual([
      [{ queryKey: ['community', 'channels'] }],
      [{ queryKey: ['tasks'] }],
    ])
    view.unmount()
  })

  it('does not acknowledge a hidden task tab or a closed panel', async () => {
    const view = discussion(false)
    await flushPromises()
    expect(observers).toHaveLength(0)
    await view.setProps({ active: true })
    const old = observers.at(-1)!
    await view.setProps({ active: false })
    intersect(old)
    await flushPromises()
    expect(mocks.post).not.toHaveBeenCalled()
    view.unmount()
  })

  it('keeps the newest-page marker when older history is loaded', async () => {
    const view = discussion()
    await flushPromises()
    mocks.history.data.value.pages = [page(['newest']), page(['older-first', 'older-last'])]
    await flushPromises()
    expect(view.findAll('article').map((item) => item.text())).toEqual([
      expect.stringContaining('older-first'),
      expect.stringContaining('older-last'),
      expect.stringContaining('newest'),
    ])
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenLastCalledWith('/v1/channels/channel/read', {
      through_message_id: 'newest',
    })
    view.unmount()
  })

  it('does not acknowledge an arriving message using the previously visible marker', async () => {
    const view = discussion()
    await flushPromises()
    const old = observers.at(-1)!
    mocks.history.data.value.pages = [page(['newest', 'arrived'])]
    await flushPromises()
    intersect(old)
    expect(mocks.post).not.toHaveBeenCalled()
    intersect()
    await flushPromises()
    expect(mocks.post).toHaveBeenLastCalledWith('/v1/channels/channel/read', {
      through_message_id: 'arrived',
    })
    view.unmount()
  })
})

describe('task discussion send identity', () => {
  const writable = () =>
    mount(TaskDiscussion, { props: { channelId: 'channel-a', canWrite: true, active: true } })

  it('clears only the originating draft after a successful send', async () => {
    const view = writable()
    await view.get('textarea').setValue('  Synthetic message  ')
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.journal.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        endpoint: '/v1/channels/channel-a/messages',
        body: { text: 'Synthetic message' },
      }),
    )
    expect(mocks.clear).toHaveBeenCalledExactlyOnceWith('channel:channel-a')
    expect(mocks.draft.value.text).toBe('')
    expect(mocks.refetch).toHaveBeenCalledOnce()
    view.unmount()
  })

  it.each(['success', 'rejected', 'uncertain'])(
    'keeps another channel draft and UI after an earlier send is %s',
    async (outcome) => {
      let finish!: () => void
      mocks.journal.submit.mockImplementationOnce(
        () =>
          new Promise<void>((resolve, reject) => {
            finish = () => {
              if (outcome === 'success') resolve()
              else if (outcome === 'rejected')
                reject(new ApiError(404, 'not_found', null, null, null))
              else reject(new Error('Connection lost'))
            }
          }),
      )
      const view = writable()
      await view.get('textarea').setValue('Channel A message')
      await view.get('form').trigger('submit')
      await view.setProps({ channelId: 'channel-b' })
      mocks.draft.value = { text: 'Keep channel B draft' }
      await flushPromises()
      finish()
      await flushPromises()
      expect(mocks.draft.value.text).toBe('Keep channel B draft')
      expect(mocks.clear).not.toHaveBeenCalled()
      expect(mocks.refetch).not.toHaveBeenCalled()
      expect(mocks.toastError).not.toHaveBeenCalled()
      expect(view.get('textarea').attributes('disabled')).toBeUndefined()
      view.unmount()
    },
  )

  it('does not clear a replacement instance draft when an unmounted send succeeds', async () => {
    let finish!: () => void
    mocks.journal.submit.mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const previous = writable()
    await previous.get('textarea').setValue('Old channel A message')
    await previous.get('form').trigger('submit')
    previous.unmount()
    // Reopening the same task creates a new draft that the old response cannot own.
    const current = writable()
    await current.get('textarea').setValue('New channel A draft')
    finish()
    await flushPromises()
    expect(mocks.draft.value.text).toBe('New channel A draft')
    expect(mocks.clear).not.toHaveBeenCalled()
    expect(mocks.refetch).not.toHaveBeenCalled()
    expect(mocks.toastError).not.toHaveBeenCalled()
    current.unmount()
  })
})
