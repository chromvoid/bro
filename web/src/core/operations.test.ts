import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { JournalChange, SubmissionJournal } from './journal'
import { ApiError } from './http'
import { useLedger } from './operations'
import { StorageUnavailableError } from './storage'

const mocks = vi.hoisted(() => ({ account: null as unknown }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ account: mocks.account }) }))
function harness(setup: () => void) {
  return defineComponent({
    setup() {
      setup()
      return () => null
    },
  })
}

describe('ledger recovery disposition', () => {
  it('returns the authoritative submission before slow read refreshes finish', async () => {
    const resolved = { state: 'resolved', key: 'known', response: { receipt_id: 'receipt' } }
    const uncertain = { state: 'uncertain', key: 'pending' }
    const journal = {
      pending: vi.fn(async () => []),
      subscribe: vi.fn(() => () => {}),
      check: vi.fn(async (key: string) => (key === 'known' ? resolved : uncertain)),
      retry: vi.fn(async () => resolved),
      acknowledge: vi.fn(async () => {}),
    }
    mocks.account = { journal: journal as unknown as SubmissionJournal }
    const queries = new QueryClient()
    let finishRefresh: (() => void) | undefined
    vi.spyOn(queries, 'invalidateQueries').mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finishRefresh = resolve
        }),
    )
    let ledger: ReturnType<typeof useLedger> | undefined
    mount(
      harness(() => {
        ledger = useLedger({
          endpoint: '/v1/activity/operations',
          lookupPrefix: '/v1/activity/operations/by-key/',
          domain: 'activity',
        })
      }),
      { global: { plugins: [[VueQueryPlugin, { queryClient: queries }]] } },
    )
    await flushPromises()
    expect(await ledger!.check('known')).toBe(resolved)
    finishRefresh?.()
    expect(await ledger!.check('pending')).toBe(uncertain)
    finishRefresh?.()
    expect(await ledger!.resend('known')).toBe(resolved)
    finishRefresh?.()
    expect(journal.acknowledge.mock.calls).toEqual([['known'], ['known']])
  })
  it('notifies an opt-in draft once when automatic recovery resolves its own invocation', async () => {
    let listener: ((change: JournalChange) => void) | undefined
    let ownKey = ''
    const journal = {
      pending: vi.fn(async () => []),
      subscribe: vi.fn((callback: typeof listener) => {
        listener = callback
        return () => {
          listener = undefined
        }
      }),
      submit: vi.fn(async (request: { key: string }) => {
        ownKey = request.key
        throw new ApiError(503, 'temporary', null, null, null)
      }),
      acknowledge: vi.fn(async () => {
        throw new Error('uncertain')
      }),
    }
    mocks.account = { journal: journal as unknown as SubmissionJournal }
    let ledger: ReturnType<typeof useLedger> | undefined
    const recovered = vi.fn()
    const wrapper = mount(
      harness(() => {
        ledger = useLedger({
          endpoint: '/v1/activity/operations',
          lookupPrefix: '/v1/activity/operations/by-key/',
          domain: 'activity',
        })
        ledger.onResolved(recovered)
      }),
      { global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] } },
    )
    await expect(
      ledger!.operate({ kind: 'activity.exercise.add', payload: { name: 'Synthetic' } }),
    ).rejects.toMatchObject({ status: 503 })
    const change = (key: string): JournalChange => ({
      entry: {
        key,
        endpoint: '/v1/activity/operations',
        state: 'resolved',
      } as import('./journal').Submission,
      removed: false,
      local: true,
    })
    listener?.(change('another-invocation'))
    listener?.(change(ownKey))
    listener?.(change(ownKey))
    await flushPromises()
    expect(recovered).toHaveBeenCalledOnce()
    expect(recovered.mock.calls[0]![0]).toMatchObject({ key: ownKey, state: 'resolved' })
    wrapper.unmount()
  })
})

describe('ledger without account storage', () => {
  it('lists no unknown writes and leaves no rejection unhandled', async () => {
    let listener: ((change: JournalChange) => void) | undefined
    const journal = {
      pending: vi.fn(async () => {
        throw new StorageUnavailableError()
      }),
      subscribe: vi.fn((callback: typeof listener) => {
        listener = callback
        return () => {}
      }),
    }
    mocks.account = { journal: journal as unknown as SubmissionJournal }
    const unhandled: unknown[] = []
    const record = (reason: unknown) => unhandled.push(reason)
    process.on('unhandledRejection', record)
    try {
      let ledger: ReturnType<typeof useLedger> | undefined
      mount(
        harness(() => {
          ledger = useLedger({
            endpoint: '/v1/tasks/operations',
            lookupPrefix: '/v1/tasks/operations/by-key/',
            domain: 'tasks',
          })
        }),
        { global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] } },
      )
      // Another tab of the account relays a change of its own journal.
      listener?.({
        entry: { key: 'relayed', endpoint: '/v1/tasks/operations', state: 'uncertain' },
        removed: false,
        local: false,
      } as JournalChange)
      await flushPromises()
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(journal.pending).toHaveBeenCalledTimes(2)
      expect(ledger!.unresolved.value).toEqual([])
      expect(unhandled).toEqual([])
    } finally {
      process.off('unhandledRejection', record)
    }
  })
})

describe('ledger receipts', () => {
  function receiving(submit: (request: { key: string }) => Promise<unknown>) {
    const journal = {
      pending: vi.fn(async () => []),
      subscribe: vi.fn(() => () => {}),
      submit: vi.fn(submit),
      acknowledge: vi.fn(async () => {}),
    }
    mocks.account = { journal: journal as unknown as SubmissionJournal }
    const order: string[] = []
    const queries = new QueryClient()
    vi.spyOn(queries, 'invalidateQueries').mockImplementation(async () => {
      order.push('refresh')
    })
    const remember = vi.fn(() => order.push('remember'))
    let ledger: ReturnType<typeof useLedger> | undefined
    mount(
      harness(() => {
        ledger = useLedger({
          endpoint: '/v1/tasks/operations',
          lookupPrefix: '/v1/tasks/operations/by-key/',
          domain: 'tasks',
          remember,
        })
      }),
      { global: { plugins: [[VueQueryPlugin, { queryClient: queries }]] } },
    )
    return { ledger: ledger!, queries, remember, order }
  }

  it('hands a receipt to its domain before the reads are refreshed', async () => {
    const receipt = { operation_id: 'operation', kind: 'task.reopen', tasks: [] }
    const { ledger, queries, remember, order } = receiving(async (request) => ({
      key: request.key,
      state: 'resolved',
      response: receipt,
    }))
    expect(await ledger.operate({ kind: 'task.reopen', payload: { task_id: 't' } })).toBe(receipt)
    expect(remember).toHaveBeenCalledWith(queries, receipt)
    expect(order).toEqual(['remember', 'refresh'])
  })
  it('hands over nothing when the write is refused', async () => {
    const { ledger, remember, order } = receiving(async () => {
      throw new ApiError(409, null, null, null, null)
    })
    await expect(
      ledger.operate({ kind: 'task.delete', payload: { task_id: 't' } }),
    ).rejects.toMatchObject({ status: 409 })
    expect(remember).not.toHaveBeenCalled()
    expect(order).toEqual(['refresh'])
  })
})
