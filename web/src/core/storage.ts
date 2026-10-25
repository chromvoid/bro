/** Per-account browser storage. The origin is implicit in IndexedDB; the account is the database. */
import { deleteDB, openDB, type DBSchema, type IDBPDatabase } from 'idb'
import type { Submission } from './journal'

export interface Draft {
  target: string
  text: string
  revision: number
  updatedAt: number
}

interface AccountSchema extends DBSchema {
  submissions: { key: string; value: Submission }
  drafts: { key: string; value: Draft }
  meta: { key: string; value: unknown }
}

// A prefix of its own: the old pages keep pending writes in session/local storage.
const databaseName = (userId: string) => `assistant-web/${userId}`

/** Local delivery state cannot be read or persisted; an empty journal would be misleading. */
export class StorageUnavailableError extends Error {
  constructor() {
    super('Account storage is unavailable')
    this.name = 'StorageUnavailableError'
  }
}

export class AccountStore {
  private constructor(
    readonly userId: string,
    private readonly db: IDBPDatabase<AccountSchema> | null,
  ) {}

  static unavailable(userId: string): AccountStore {
    return new AccountStore(userId, null)
  }

  get available(): boolean {
    return this.db !== null
  }

  private database(): IDBPDatabase<AccountSchema> {
    if (!this.db) throw new StorageUnavailableError()
    return this.db
  }

  static async open(userId: string): Promise<AccountStore> {
    const db = await openDB<AccountSchema>(databaseName(userId), 1, {
      upgrade(db) {
        db.createObjectStore('submissions', { keyPath: 'key' })
        db.createObjectStore('drafts', { keyPath: 'target' })
        db.createObjectStore('meta')
      },
      // Another tab is deleting this account's data (logout): let it proceed.
      blocking() {
        db.close()
      },
    })
    return new AccountStore(userId, db)
  }

  async submission(key: string): Promise<Submission | undefined> {
    return this.database().get('submissions', key)
  }

  async saveSubmission(entry: Submission): Promise<void> {
    await this.database().put('submissions', entry)
  }

  async deleteSubmission(key: string): Promise<void> {
    return this.database().delete('submissions', key)
  }

  async submissions(): Promise<Submission[]> {
    return this.database().getAll('submissions')
  }

  async draft(target: string): Promise<Draft | undefined> {
    return this.database().get('drafts', target)
  }

  async saveDraft(draft: Draft): Promise<void> {
    await this.database().put('drafts', draft)
  }

  async deleteDraft(target: string): Promise<void> {
    return this.database().delete('drafts', target)
  }

  async value<T>(name: string): Promise<T | undefined> {
    return (await this.database().get('meta', name)) as T | undefined
  }

  async setValue(name: string, value: unknown): Promise<void> {
    await this.database().put('meta', value, name)
  }

  close(): void {
    this.db?.close()
  }
}

/** Logout removes drafts, the journal and cursors of the account, even while offline. */
export async function purgeAccount(userId: string): Promise<void> {
  await deleteDB(databaseName(userId))
}

/** Asks the browser not to evict the journal under storage pressure. */
export async function persistStorage(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false
  } catch {
    return false
  }
}
