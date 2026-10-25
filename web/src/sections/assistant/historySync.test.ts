import { InfiniteQueryObserver, QueryClient } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChangeEvent } from '@/core/events'
import { ApiError } from '@/core/http'
import { shareMessages, type HistoryData } from './historyCache'
import { HistorySync } from './historySync'
import type { History, Message } from './model'

const PAGE = 3

function message(n: number, extra: Record<string, unknown> = {}): Message {
  return {
    id: `m${String(n).padStart(3, '0')}`,
    created_at: `2026-10-06T08:${String(n).padStart(2, '0')}:00.000000+00:00`,
    text: `Synthetic ${n}`,
    state: 'completed',
    turns: [{ id: `t${n}`, state: 'completed', result: {} }],
    ...extra,
  } as Message
}

/** A synthetic server: pages of three, newest first, and one exchange by id. */
function server(count: number) {
  const messages = Array.from({ length: count }, (_, i) => message(i + 1))
  const reads: Array<string | null> = []
  const pages: Array<string | null> = []
  return {
    messages,
    reads,
    pages,
    page(before: string | null): History {
      pages.push(before)
      const end = before ? messages.findIndex((item) => item.id === before) : messages.length
      const start = Math.max(0, end - PAGE)
      return {
        messages: messages.slice(start, end),
        before: start > 0 ? messages[start]!.id : null,
      }
    },
    async read(id: string | null): Promise<History> {
      reads.push(id)
      if (id === null) return this.page(null)
      const found = messages.find((item) => item.id === id)
      if (!found) throw new ApiError(404, 'not_found', null, null, null)
      return { messages: [found], before: null }
    },
  }
}

const event = (data: Record<string, unknown> | null): ChangeEvent => ({
  id: 1,
  name: 'assistant.changed',
  topic: 'assistant.changed',
  data: data && { ...data, topic: 'assistant.changed' },
})

const cleanups: Array<() => void> = []
afterEach(() => cleanups.splice(0).forEach((stop) => stop()))

async function setup(count = 9, atHead = true) {
  const backend = server(count)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  const key = ['conversation', 'history', null]
  const observer = new InfiniteQueryObserver(client, {
    queryKey: key,
    initialPageParam: null as string | null,
    queryFn: async ({ pageParam }) => backend.page(pageParam),
    getNextPageParam: (last: History) => last.before,
    structuralSharing: shareMessages,
  })
  const unsubscribe = observer.subscribe(() => undefined)
  await vi.waitFor(() => expect(observer.getCurrentResult().isSuccess).toBe(true))
  const sync = new HistorySync({
    client,
    key: () => key,
    read: (id) => backend.read(id),
    atHead: () => atHead,
    delayMs: 5,
  })
  cleanups.push(() => {
    sync.dispose()
    unsubscribe()
    client.clear()
  })
  const data = () => client.getQueryData<HistoryData>(key)!
  const loadedIds = () => data().pages.flatMap((page) => page.messages.map((item) => item.id))
  return { backend, client, observer, sync, data, loadedIds, key }
}

describe('HistorySync', () => {
  it('reads a named exchange once for a burst of its events and leaves the rest of the cache alone', async () => {
    const { backend, sync, data } = await setup()
    const before = data().pages[0]!.messages[0]
    backend.messages[8] = message(9, { state: 'failed' })
    for (const state of ['routing', 'processing', 'processing', 'completed', 'failed'])
      expect(sync.handle(event({ message_id: 'm009', state }))).toBe(true)
    await vi.waitFor(() => expect(data().pages[0]!.messages.at(-1)!.state).toBe('failed'))
    expect(backend.reads).toEqual(['m009'])
    expect(data().pages[0]!.messages[0]).toBe(before)
    expect(backend.pages).toEqual([null])
  })

  it('takes a tool step and a topic change without reading anything', async () => {
    const { backend, sync } = await setup()
    expect(
      sync.handle(event({ message_id: 'm009', turn_id: 't9', state: 'processing', stage: 'tool' })),
    ).toBe(true)
    expect(sync.handle(event({ thread_id: 'synthetic-thread', state: 'closed' }))).toBe(true)
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(backend.reads).toEqual([])
  })

  it('adds a message from another device at the bottom', async () => {
    const { backend, sync, loadedIds } = await setup()
    backend.messages.push(message(10))
    sync.handle(event({ message_id: 'm010', state: 'queued' }))
    await vi.waitFor(() => expect(loadedIds().at(-1)).toBe('m010'))
    expect(backend.reads).toEqual(['m010'])
  })

  it('remembers a message of another chat after one 404', async () => {
    const { backend, sync } = await setup()
    sync.handle(event({ message_id: 'shared-chat', state: 'queued' }))
    await vi.waitFor(() => expect(backend.reads).toEqual(['shared-chat']))
    sync.handle(event({ message_id: 'shared-chat', state: 'completed' }))
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(backend.reads).toEqual(['shared-chat'])
  })

  it('reads the newest page for an event that names nothing or an unknown turn', async () => {
    const { backend, sync, loadedIds } = await setup()
    backend.messages.push(message(10))
    sync.handle(event(null))
    await vi.waitFor(() => expect(loadedIds().at(-1)).toBe('m010'))
    expect(backend.reads).toEqual([null])
    sync.handle(event({ turn_id: 'unknown-turn', state: 'finalized' }))
    await vi.waitFor(() => expect(backend.reads).toEqual([null, null]))
  })

  it('reads the exchange of a known turn and the sources of a clarified message', async () => {
    const { backend, sync, client, key } = await setup()
    client.setQueryData<HistoryData>(key, (data) => ({
      ...data!,
      pages: [
        {
          ...data!.pages[0]!,
          messages: data!.pages[0]!.messages.map((item) =>
            item.id === 'm008' ? { ...item, clarification_receipt: { message_id: 'm007' } } : item,
          ) as Message[],
        },
      ],
    }))
    sync.handle(event({ turn_id: 't9', result: {} }))
    sync.handle(event({ message_id: 'm007' }))
    await vi.waitFor(() =>
      expect(new Set(backend.reads)).toEqual(new Set(['m009', 'm007', 'm008'])),
    )
  })

  it('applies an update that arrived during "earlier" after the older page, keeping both', async () => {
    const { backend, sync, observer, data, loadedIds } = await setup()
    backend.messages[8] = message(9, { state: 'failed' })
    const earlier = observer.fetchNextPage()
    sync.handle(event({ message_id: 'm009', state: 'failed' }))
    await earlier
    await vi.waitFor(() => expect(data().pages[0]!.messages.at(-1)!.state).toBe('failed'))
    expect(loadedIds()).toEqual(['m007', 'm008', 'm009', 'm004', 'm005', 'm006'])
  })

  it('leaves a failed history to invalidation and reloads it when a read fails', async () => {
    const { backend, sync, client, key } = await setup()
    const query = client.getQueryCache().find({ queryKey: key, exact: true })!
    query.setState({ status: 'error' })
    expect(sync.handle(event({ message_id: 'm009', state: 'completed' }))).toBe(false)
    query.setState({ status: 'success' })
    const read = vi.spyOn(backend, 'read').mockRejectedValueOnce(new TypeError('offline'))
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    sync.handle(event({ message_id: 'm009', state: 'completed' }))
    await vi.waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: key, exact: true }))
    read.mockRestore()
  })

  it('away from the newest message, keeps new messages and the newest page out of the window', async () => {
    const { backend, sync, loadedIds } = await setup(9, false)
    backend.messages.push(message(10))
    sync.handle(event({ message_id: 'm010', state: 'queued' }))
    sync.handle(event({ turn_id: 'unknown-turn', state: 'finalized' }))
    await vi.waitFor(() => expect(backend.reads).toEqual(['m010']))
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(loadedIds()).not.toContain('m010')
  })

  it('moves the window to the newest page on request', async () => {
    const { backend, sync, data, observer } = await setup(9)
    await observer.fetchNextPage()
    expect(data().pages).toHaveLength(2)
    backend.messages.push(message(10))
    await sync.latest()
    expect(data().pages).toHaveLength(1)
    expect(data().pages[0]!.messages.map((item) => item.id)).toEqual(['m008', 'm009', 'm010'])
  })

  it('re-reads exchanges with an open proposal when another one settles', async () => {
    const { backend, sync, client, key } = await setup()
    client.setQueryData<HistoryData>(key, (data) => ({
      ...data!,
      pages: [
        {
          ...data!.pages[0]!,
          messages: data!.pages[0]!.messages.map((item) =>
            item.id === 'm007'
              ? {
                  ...item,
                  turns: [{ id: 't7', state: 'completed', result: { confirmation_pending: true } }],
                }
              : item,
          ) as Message[],
        },
      ],
    }))
    sync.handle(event({ message_id: 'm009', state: 'completed' }))
    await vi.waitFor(() => expect(new Set(backend.reads)).toEqual(new Set(['m009', 'm007'])))
  })

  it('resolves a touch once the change is in the cache', async () => {
    const { backend, sync, loadedIds } = await setup()
    backend.messages.push(message(10))
    await sync.touch({ messages: ['m010'] })
    expect(loadedIds().at(-1)).toBe('m010')
  })
})
