import 'fake-indexeddb/auto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Submission } from './journal'
import { AccountStore, purgeAccount, StorageUnavailableError } from './storage'

const entry: Submission = {
  key: 'saved-send',
  method: 'POST',
  endpoint: '/v1/conversation/messages',
  lookup: '/v1/conversation/submissions/by-key/saved-send',
  found: 'accepted',
  body: '{"text":"synthetic"}',
  createdAt: 1,
  state: 'uncertain',
  response: null,
  failure: null,
  context: {},
}
const draft = { target: 'assistant', text: 'synthetic draft', revision: 1, updatedAt: 1 }

describe('AccountStore unavailable mode', () => {
  afterEach(() => vi.restoreAllMocks())

  it('keeps account identity and can close repeatedly without opening IndexedDB', () => {
    const opened = vi.spyOn(indexedDB, 'open')
    const store = AccountStore.unavailable('unavailable-user')
    expect(store).toBeInstanceOf(AccountStore)
    expect(store.userId).toBe('unavailable-user')
    expect(store.available).toBe(false)
    expect(() => {
      store.close()
      store.close()
    }).not.toThrow()
    expect(opened).not.toHaveBeenCalled()
  })

  const operations: Array<[string, (store: AccountStore) => Promise<unknown>]> = [
    ['submission', (store) => store.submission(entry.key)],
    ['saveSubmission', (store) => store.saveSubmission(entry)],
    ['deleteSubmission', (store) => store.deleteSubmission(entry.key)],
    ['submissions', (store) => store.submissions()],
    ['draft', (store) => store.draft(draft.target)],
    ['saveDraft', (store) => store.saveDraft(draft)],
    ['deleteDraft', (store) => store.deleteDraft(draft.target)],
    ['value', (store) => store.value('events.cursor')],
    ['setValue', (store) => store.setValue('events.cursor', 10)],
  ]
  it.each(operations)(
    '%s rejects explicitly instead of inventing empty or temporary state',
    async (_name, operation) => {
      const store = AccountStore.unavailable('unavailable-user')
      const result = operation(store)
      expect(result).toBeInstanceOf(Promise)
      await expect(result).rejects.toBeInstanceOf(StorageUnavailableError)
      await expect(store.submissions()).rejects.toBeInstanceOf(StorageUnavailableError)
    },
  )

  it('does not change normal open failures into an empty account', async () => {
    vi.spyOn(indexedDB, 'open').mockImplementationOnce(() => {
      throw new DOMException('Storage denied', 'SecurityError')
    })
    await expect(AccountStore.open('denied-user')).rejects.toMatchObject({ name: 'SecurityError' })
  })

  it('preserves the real journal, draft and cursor across an unavailable interval', async () => {
    const user = 'storage-persistence-test'
    let store = await AccountStore.open(user)
    try {
      expect(store.available).toBe(true)
      await store.saveSubmission(entry)
      await store.saveDraft(draft)
      await store.setValue('events.cursor', 7)
      store.close()

      const unavailable = AccountStore.unavailable(user)
      await expect(unavailable.deleteSubmission(entry.key)).rejects.toBeInstanceOf(
        StorageUnavailableError,
      )
      await expect(unavailable.saveDraft({ ...draft, text: 'replacement' })).rejects.toBeInstanceOf(
        StorageUnavailableError,
      )
      await expect(unavailable.setValue('events.cursor', 10)).rejects.toBeInstanceOf(
        StorageUnavailableError,
      )
      unavailable.close()

      store = await AccountStore.open(user)
      expect(await store.submission(entry.key)).toEqual(entry)
      expect(await store.submissions()).toEqual([entry])
      expect(await store.draft(draft.target)).toEqual(draft)
      expect(await store.value('events.cursor')).toBe(7)
      await store.deleteSubmission(entry.key)
      await store.deleteDraft(draft.target)
      expect(await store.submissions()).toEqual([])
      expect(await store.draft(draft.target)).toBeUndefined()
    } finally {
      store.close()
      await purgeAccount(user)
    }
  })
})
